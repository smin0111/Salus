package com.salus.healthytable.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tavily 검색 API를 사용하는 웹 검색 엔진입니다.
 * {@code @ConditionalOnProperty}: search.provider=tavily로 설정했을 때만 Bean으로 등록됩니다(기본은 DuckDuckGo).
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "search", name = "provider", havingValue = "tavily")
public class TavilySearchEngine implements SearchEngine {

    // 가져올 최대 결과 수와, 결과 하나에서 근거로 쓸 최대 글자 수
    private static final int MAX_RESULTS = 3;
    private static final int MAX_EVIDENCE_LENGTH = 1_200;

    private final WebClient webClient;
    private final String apiKey;

    public TavilySearchEngine(WebClient.Builder webClientBuilder,
                              @Value("${tavily.api-key:}") String apiKey) {
        this.webClient = webClientBuilder
                .baseUrl("https://api.tavily.com")
                .build();
        this.apiKey = apiKey;
    }

    /**
     * 요리 이름에 "레시피 재료 분량 조리 순서"를 붙여 검색합니다.
     * API 키가 없거나 호출이 실패하면 FAILED, 결과가 없으면 EMPTY를 반환합니다.
     */
    @Override
    public Mono<SearchResponse> search(String query) {
        if (query == null || query.isBlank()) {
            return Mono.just(new SearchResponse(SearchStatus.EMPTY, List.of(), sourceName()));
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[TavilySearch] Missing Tavily API key. Set TAVILY_API_KEY or tavily.api-key.");
            return Mono.just(new SearchResponse(SearchStatus.FAILED, List.of(), sourceName()));
        }

        Map<String, Object> request = Map.of(
                "query", query + " 레시피 재료 분량 조리 순서",
                "search_depth", "advanced",
                "topic", "general",
                "country", "south korea",
                "max_results", MAX_RESULTS,
                "chunks_per_source", 3,
                "include_answer", false,
                "include_raw_content", false,
                "include_usage", true
        );

        return webClient.post()
                .uri("/search")
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(TavilyResponse.class)
                .timeout(Duration.ofSeconds(15))
                .map(this::toSearchResponse)
                .onErrorResume(e -> {
                    log.error("[TavilySearch] Search request failed", e);
                    return Mono.just(new SearchResponse(SearchStatus.FAILED, List.of(), sourceName()));
                });
    }

    @Override
    public String sourceName() {
        return "tavily";
    }

    // Tavily 응답을 공통 SearchResponse로 바꿉니다. 제목이나 본문이 비어 있는 결과는 버립니다.
    private SearchResponse toSearchResponse(TavilyResponse response) {
        if (response == null || response.results() == null || response.results().isEmpty()) {
            log.info("[TavilySearch] Search completed with empty results.");
            return new SearchResponse(SearchStatus.EMPTY, List.of(), sourceName());
        }

        List<SearchResult> results = response.results().stream()
                .filter(Objects::nonNull)
                .map(result -> new SearchResult(
                        nullToBlank(result.title()),
                        nullToBlank(result.url()),
                        bestAvailableContent(result)
                ))
                .filter(result -> !result.title().isBlank() && !result.snippet().isBlank())
                .limit(MAX_RESULTS)
                .toList();

        if (results.isEmpty()) {
            log.info("[TavilySearch] Search returned results without usable content.");
            return new SearchResponse(SearchStatus.EMPTY, List.of(), sourceName());
        }

        log.info("[TavilySearch] Successfully retrieved {} results.", results.size());
        return new SearchResponse(SearchStatus.SUCCESS, results, sourceName());
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    // 근거로 쓸 본문을 고르고 최대 길이로 자릅니다.
    private String bestAvailableContent(TavilyResult result) {
        String content = nullToBlank(result.content()).replaceAll("\\s+", " ").trim();
        String rawContent = nullToBlank(result.rawContent()).replaceAll("\\s+", " ").trim();
        // Tavily content는 검색 질의와 관련된 chunk만 모은 값이다. 페이지 원문 전체보다 먼저 사용해
        // 메뉴/광고/댓글이 프롬프트를 채우는 일을 막고, 원문은 content가 없을 때만 보조로 쓴다.
        String selected = content.isBlank() ? rawContent : content;
        if (selected.length() <= MAX_EVIDENCE_LENGTH) {
            return selected;
        }
        return selected.substring(0, MAX_EVIDENCE_LENGTH);
    }

    // Tavily 응답 JSON 중 필요한 필드만 읽기 위한 record입니다. 모르는 필드는 무시합니다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TavilyResponse(List<TavilyResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TavilyResult(
            String title,
            String url,
            String content,
            @JsonProperty("raw_content") String rawContent) {
    }
}
