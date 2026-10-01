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

/**
 * 식품의약품안전처(식품안전나라) 조리식품 레시피 공개 API(COOKRCP01) 클라이언트입니다.
 *
 * 공식 기관 데이터라 웹 검색보다 먼저 조회합니다. API 키가 없거나 기능이 꺼져 있으면 EMPTY를 반환해
 * 웹 검색 엔진으로 넘어가게 합니다.
 */
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

    // 요리 이름(RCP_NM)으로 최대 20건을 조회합니다. 호출 실패는 FAILED로 반환합니다.
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

    // 응답 JSON의 COOKRCP01.row 배열에서 요청한 요리와 이름이 맞는 레시피만 최대 3개 근거로 만듭니다.
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

    // API 필드(요리명, 재료, MANUAL01~20 조리 단계, 조리 팁 등)를 "라벨: 값" 형태의 근거 텍스트로 만듭니다.
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

    // 기호/공백을 제거한 이름이 같거나 한쪽이 다른 쪽을 포함하면 같은 요리로 봅니다.
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

    // URL 경로에 넣을 수 있게 인코딩합니다. 공백은 +가 아니라 %20으로 바꿉니다.
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
