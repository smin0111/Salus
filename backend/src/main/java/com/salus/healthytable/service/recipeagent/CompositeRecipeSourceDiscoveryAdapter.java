package com.salus.healthytable.service.recipeagent;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 여러 출처 검색 어댑터를 합쳐 쓰는 기본(@Primary) RecipeSourceDiscoveryPort 구현입니다.
 *
 * 순서: 내부 DB → YouTube → (웹 검색이 켜진 경우) 캐시 확인 → 웹 검색 + 구조화 레시피 페이지 수집
 * 결과는 URL/본문 기준으로 중복 제거한 뒤 신뢰도 높은 순으로 최대 maxSources개를 반환합니다.
 */
@Primary
@Service
@RequiredArgsConstructor
class CompositeRecipeSourceDiscoveryAdapter implements RecipeSourceDiscoveryPort {

    private final InternalRecipeSourceDiscoveryAdapter internalRecipeSourceAdapter;
    private final WebRecipeSearchPort webRecipeSearchPort;
    private final StructuredRecipePageAdapter structuredRecipePageAdapter;
    private final InMemoryRecipeSourceCache sourceCache;
    private final YouTubeRecipeSourceDiscoveryAdapter youTubeRecipeSourceDiscoveryAdapter;

    // 웹 출처 검색 사용 여부(기본 false)
    @Value("${recipe.agent.web-source-enabled:false}")
    private boolean webSourceEnabled;

    @Override
    public List<RecipeSourceDocument> search(RecipeResearchPlan plan, UserRecipeContext context) {
        List<RecipeSourceDocument> documents = new ArrayList<>(internalRecipeSourceAdapter.search(plan, context));
        documents.addAll(youtubeSources(plan, context));
        if (!webSourceEnabled || plan == null) {
            return sorted(documents, plan);
        }

        String cacheKey = sourceCache.key(plan);
        sourceCache.get(cacheKey).ifPresent(cached -> documents.add(cached.source()));
        // 이미 캐시 등으로 웹 출처가 있으면 새로 웹 검색을 하지 않습니다.
        if (documents.stream().anyMatch(document -> document.sourceType() == RecipeSourceType.GENERAL_WEB
                || document.sourceType() == RecipeSourceType.OFFICIAL_WEB)) {
            return sorted(documents, plan);
        }

        List<WebRecipeSearchResult> searchResults = webRecipeSearchPort.search(plan.searchQueries(), Math.max(plan.maxSources() * 2, 6));
        List<RecipeSourceCandidate> webCandidates = structuredRecipePageAdapter.collect(plan, searchResults);
        if (!webCandidates.isEmpty()) {
            sourceCache.put(cacheKey, webCandidates.get(0));
        }
        webCandidates.stream()
                .map(RecipeSourceCandidate::source)
                .forEach(documents::add);

        return sorted(documents, plan);
    }

    // YouTube 검색 실패는 전체 검색을 실패시키지 않고 빈 결과로 처리합니다.
    private List<RecipeSourceDocument> youtubeSources(RecipeResearchPlan plan, UserRecipeContext context) {
        try {
            return youTubeRecipeSourceDiscoveryAdapter.search(plan, context);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<RecipeSourceDocument> sorted(List<RecipeSourceDocument> documents, RecipeResearchPlan plan) {
        int limit = plan == null ? 5 : plan.maxSources();
        return distinctByUrlAndContent(documents).stream()
                .sorted(Comparator.comparing(RecipeSourceDocument::sourceReliability).reversed())
                .limit(limit)
                .toList();
    }

    // URL이 있으면 URL로, 없으면 제목+본문 해시로 중복을 판단합니다.
    private List<RecipeSourceDocument> distinctByUrlAndContent(List<RecipeSourceDocument> documents) {
        Map<String, RecipeSourceDocument> unique = new LinkedHashMap<>();
        for (RecipeSourceDocument document : documents) {
            if (document == null) {
                continue;
            }
            String key = document.url() != null && !document.url().isBlank()
                    ? "url:" + normalize(document.url())
                    : "content:" + Integer.toHexString((document.title() + document.content()).hashCode());
            unique.putIfAbsent(key, document);
        }
        return new ArrayList<>(new LinkedHashSet<>(unique.values()));
    }

    private String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
