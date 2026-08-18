package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
public class MfdsRecipeSearchClient {

    private static final String SOURCE_NAME = "식품의약품안전처 레시피 DB";
    private static final String SOURCE_URL =
            "https://www.foodsafetykorea.go.kr/api/newDatasetDetail.do?svc_no=COOKRCP01";

    private final WebClient webClient;
    private final boolean enabled;
    private final String apiKey;
    private final String baseUrl;
    private final long timeoutSeconds;

    public MfdsRecipeSearchClient(
            WebClient.Builder webClientBuilder,
            @Value("${recipe.official-source.enabled:true}") boolean enabled,
            @Value("${recipe.official-source.api-key:}") String apiKey,
            @Value("${recipe.official-source.base-url:https://openapi.foodsafetykorea.go.kr}") String baseUrl,
            @Value("${recipe.official-source.timeout-seconds:10}") long timeoutSeconds) {
        this.webClient = webClientBuilder.build();
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.timeoutSeconds = timeoutSeconds;
    }

    public Mono<SearchEngine.SearchResponse> search(String requestedTitle) {
        if (!enabled || apiKey.isBlank() || requestedTitle == null || requestedTitle.isBlank()) {
            return Mono.just(emptyResponse());
        }

        String requestUrl = baseUrl
                + "/api/" + encode(apiKey)
                + "/COOKRCP01/json/1/20/RCP_NM=" + encode(requestedTitle.trim());
        return webClient.get()
                .uri(requestUrl)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .map(root -> toSearchResponse(requestedTitle, root))
                .onErrorResume(error -> {
                    log.warn("[공식 레시피 검색] 조회 실패. failureCategory={}", error.getClass().getSimpleName());
                    return Mono.just(new SearchEngine.SearchResponse(
                            SearchEngine.SearchStatus.FAILED,
                            List.of(),
                            SOURCE_NAME));
                });
    }

    private SearchEngine.SearchResponse toSearchResponse(String requestedTitle, JsonNode root) {
        JsonNode serviceNode = root == null ? null : root.get("COOKRCP01");
        JsonNode rows = serviceNode == null ? null : serviceNode.get("row");
        if (rows == null || !rows.isArray()) {
            return emptyResponse();
        }

        List<SearchEngine.SearchResult> results = new ArrayList<>();
        for (JsonNode row : rows) {
            String title = text(row, "RCP_NM");
            if (!dishMatches(requestedTitle, title)) {
                continue;
            }
            String evidence = evidenceText(row);
            if (evidence.isBlank()) {
                continue;
            }
            results.add(new SearchEngine.SearchResult(
                    title + " 공식 레시피",
                    SOURCE_URL,
                    evidence));
            if (results.size() >= 3) {
                break;
            }
        }
        return results.isEmpty()
                ? emptyResponse()
                : new SearchEngine.SearchResponse(SearchEngine.SearchStatus.SUCCESS, results, SOURCE_NAME);
    }

    private String evidenceText(JsonNode row) {
        StringBuilder evidence = new StringBuilder();
        append(evidence, "요리명", text(row, "RCP_NM"));
        append(evidence, "조리 방법", text(row, "RCP_WAY2"));
        append(evidence, "요리 종류", text(row, "RCP_PAT2"));
        append(evidence, "1인분 중량", text(row, "INFO_WGT"));
        append(evidence, "열량", text(row, "INFO_ENG"));
        append(evidence, "재료", text(row, "RCP_PARTS_DTLS"));
        for (int i = 1; i <= 20; i++) {
            String step = text(row, "MANUAL%02d".formatted(i));
            if (!step.isBlank()) {
                append(evidence, "조리 단계 " + i, step);
            }
        }
        append(evidence, "조리 참고", text(row, "RCP_NA_TIP"));
        return evidence.toString().trim();
    }

    private boolean dishMatches(String requestedTitle, String actualTitle) {
        String requested = normalize(requestedTitle);
        String actual = normalize(actualTitle);
        return !requested.isBlank() && !actual.isBlank()
                && (requested.equals(actual) || requested.contains(actual) || actual.contains(requested));
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row == null ? null : row.get(field);
        return value == null || value.isNull() ? "" : value.asText("").replaceAll("\\s+", " ").trim();
    }

    private void append(StringBuilder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append(label).append(": ").append(value).append("\n");
    }

    private SearchEngine.SearchResponse emptyResponse() {
        return new SearchEngine.SearchResponse(SearchEngine.SearchStatus.EMPTY, List.of(), SOURCE_NAME);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.replaceAll("[^가-힣a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://openapi.foodsafetykorea.go.kr";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
