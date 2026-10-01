package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.service.SearchEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기존 SearchEngine(DuckDuckGo/Tavily)을 Recipe Agent의 WebRecipeSearchPort 형태로 감싼 어댑터입니다.
 * 검색어를 차례로 실행해 URL 기준으로 중복 없이 최대 maxResults개를 모읍니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SearchEngineWebRecipeSearchAdapter implements WebRecipeSearchPort {

    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(18);

    private final SearchEngine searchEngine;

    @Override
    public List<WebRecipeSearchResult> search(List<String> queries, int maxResults) {
        if (queries == null || queries.isEmpty() || maxResults <= 0) {
            return List.of();
        }
        Map<String, WebRecipeSearchResult> resultsByUrl = new LinkedHashMap<>();
        int rank = 1;
        for (String query : queries) {
            if (query == null || query.isBlank() || resultsByUrl.size() >= maxResults) {
                continue;
            }
            try {
                // 검색어 하나당 최대 18초까지만 기다립니다.
                SearchEngine.SearchResponse response = searchEngine.search(query).block(SEARCH_TIMEOUT);
                if (response == null || response.status() != SearchEngine.SearchStatus.SUCCESS) {
                    continue;
                }
                for (SearchEngine.SearchResult result : response.results()) {
                    if (result == null || result.url() == null || result.url().isBlank()) {
                        continue;
                    }
                    String domain = domain(result.url());
                    if (domain.isBlank()) {
                        continue;
                    }
                    resultsByUrl.putIfAbsent(result.url(), new WebRecipeSearchResult(
                            nullToBlank(result.title()),
                            result.url(),
                            nullToBlank(result.snippet()),
                            domain,
                            rank++));
                    if (resultsByUrl.size() >= maxResults) {
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("[RecipeAgentWebSearch] Search failed. source={}, failureCategory={}", searchEngine.sourceName(), e.getClass().getSimpleName());
            }
        }
        return List.copyOf(resultsByUrl.values());
    }

    private String domain(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value.trim();
    }
}
