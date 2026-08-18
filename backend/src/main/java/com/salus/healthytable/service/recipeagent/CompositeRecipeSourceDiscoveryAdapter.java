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

@Primary
@Service
@RequiredArgsConstructor
class CompositeRecipeSourceDiscoveryAdapter implements RecipeSourceDiscoveryPort {

    private final InternalRecipeSourceDiscoveryAdapter internalRecipeSourceAdapter;
    private final WebRecipeSearchPort webRecipeSearchPort;
    private final StructuredRecipePageAdapter structuredRecipePageAdapter;
    private final InMemoryRecipeSourceCache sourceCache;
    private final YouTubeRecipeSourceDiscoveryAdapter youTubeRecipeSourceDiscoveryAdapter;

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
