package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.service.ExternalSearchQueryPolicy;
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
    // 레시피 본문을 싣지 않는 도메인. 위키·SNS·Q&A가 후보 자리를 차지해 실제 레시피 페이지가
    // 밀려나던 문제를 막습니다(2026-10-01 실측: 로제떡볶이 → 가수 문서, 쇠고기무국 → 영문 위키).
    // 유튜브는 YouTube 전용 어댑터가 따로 처리합니다.
    private static final List<String> NON_RECIPE_DOMAINS = List.of(
            "namu.wiki", "wikipedia.org", "instagram.com", "tiktok.com", "facebook.com",
            "youtube.com", "youtu.be", "x.com", "twitter.com", "zhihu.com");

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
            // 계획 단계에서 거르지만, 다른 경로로 만든 검색어도 외부로 나가기 전에 한 번 더 확인합니다. 검색어 내용은 로그에 남기지 않습니다.
            if (!ExternalSearchQueryPolicy.isAllowed(query)) {
                log.info("[RecipeAgentWebSearch] Query blocked by external search policy. length={}", query.length());
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
                    if (domain.isBlank() || isNonRecipeDomain(domain)) {
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

    static boolean isNonRecipeDomain(String domain) {
        return NON_RECIPE_DOMAINS.stream()
                .anyMatch(excluded -> domain.equals(excluded) || domain.endsWith("." + excluded));
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
