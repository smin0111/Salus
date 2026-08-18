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

@Slf4j
@Service
@ConditionalOnProperty(prefix = "search", name = "provider", havingValue = "tavily")
public class TavilySearchEngine implements SearchEngine {

    private static final int MAX_RESULTS = 3;
    private static final int MAX_RAW_CONTENT_LENGTH = 4_000;

    private final WebClient webClient;
    private final String apiKey;

    public TavilySearchEngine(WebClient.Builder webClientBuilder,
                              @Value("${tavily.api-key:}") String apiKey) {
        this.webClient = webClientBuilder
                .baseUrl("https://api.tavily.com")
                .build();
        this.apiKey = apiKey;
    }

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
                "include_raw_content", "text",
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

    private String bestAvailableContent(TavilyResult result) {
        String rawContent = nullToBlank(result.rawContent()).replaceAll("\\s+", " ").trim();
        String content = nullToBlank(result.content()).replaceAll("\\s+", " ").trim();
        String selected = rawContent.isBlank() ? content : rawContent;
        if (selected.length() <= MAX_RAW_CONTENT_LENGTH) {
            return selected;
        }
        return selected.substring(0, MAX_RAW_CONTENT_LENGTH);
    }

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
