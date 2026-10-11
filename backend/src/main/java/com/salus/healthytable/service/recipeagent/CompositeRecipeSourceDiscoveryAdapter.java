package com.salus.healthytable.service.recipeagent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 여러 출처 검색 어댑터를 합쳐 쓰는 기본(@Primary) RecipeSourceDiscoveryPort 구현입니다.
 *
 * 순서: 내부 DB → YouTube → (웹 검색이 켜진 경우) 메모리 캐시 → DB 저장 근거 → 웹 검색 + 레시피 페이지 수집
 * 결과는 URL/본문 기준으로 중복 제거한 뒤 신뢰도 높은 순으로 최대 maxSources개를 반환합니다.
 */
@Primary
@Service
class CompositeRecipeSourceDiscoveryAdapter implements RecipeSourceDiscoveryPort {

    private final InternalRecipeSourceDiscoveryAdapter internalRecipeSourceAdapter;
    private final WebRecipeSearchPort webRecipeSearchPort;
    private final StructuredRecipePageAdapter structuredRecipePageAdapter;
    private final InMemoryRecipeSourceCache sourceCache;
    private final YouTubeRecipeSourceDiscoveryAdapter youTubeRecipeSourceDiscoveryAdapter;
    // 웹 근거 DB 저장소와 단계 요약기. 없으면(테스트 등) 저장 없이 기존처럼 동작합니다.
    private final WebRecipeSourceStore webSourceStore;
    private final RecipeStepSummarizer stepSummarizer;

    @Autowired
    CompositeRecipeSourceDiscoveryAdapter(
            InternalRecipeSourceDiscoveryAdapter internalRecipeSourceAdapter,
            WebRecipeSearchPort webRecipeSearchPort,
            StructuredRecipePageAdapter structuredRecipePageAdapter,
            InMemoryRecipeSourceCache sourceCache,
            YouTubeRecipeSourceDiscoveryAdapter youTubeRecipeSourceDiscoveryAdapter,
            WebRecipeSourceStore webSourceStore,
            RecipeStepSummarizer stepSummarizer) {
        this.internalRecipeSourceAdapter = internalRecipeSourceAdapter;
        this.webRecipeSearchPort = webRecipeSearchPort;
        this.structuredRecipePageAdapter = structuredRecipePageAdapter;
        this.sourceCache = sourceCache;
        this.youTubeRecipeSourceDiscoveryAdapter = youTubeRecipeSourceDiscoveryAdapter;
        this.webSourceStore = webSourceStore;
        this.stepSummarizer = stepSummarizer;
    }

    // DB 저장과 단계 요약 없이 만드는 생성자입니다(기존 테스트용).
    CompositeRecipeSourceDiscoveryAdapter(
            InternalRecipeSourceDiscoveryAdapter internalRecipeSourceAdapter,
            WebRecipeSearchPort webRecipeSearchPort,
            StructuredRecipePageAdapter structuredRecipePageAdapter,
            InMemoryRecipeSourceCache sourceCache,
            YouTubeRecipeSourceDiscoveryAdapter youTubeRecipeSourceDiscoveryAdapter) {
        this(internalRecipeSourceAdapter, webRecipeSearchPort, structuredRecipePageAdapter, sourceCache,
                youTubeRecipeSourceDiscoveryAdapter, null, null);
    }

    // 웹 출처 검색 사용 여부(기본 false)
    @Value("${recipe.agent.web-source-enabled:false}")
    private boolean webSourceEnabled;

    @Override
    public List<RecipeSourceDocument> search(RecipeResearchPlan plan, UserRecipeContext context) {
        List<RecipeSourceDocument> documents = new ArrayList<>(internalRecipeSourceAdapter.search(plan, context));
        documents.addAll(youtubeSources(plan, context));
        // 뺀 재료 없이 만드는 레시피를 찾는 중이면 그 재료가 든 출처는 처음부터 후보에서 뺍니다.
        if (plan != null && !plan.avoidIngredients().isEmpty()) {
            documents.removeIf(document -> containsAvoided(document.content(), plan.avoidIngredients()));
        }
        if (!webSourceEnabled || plan == null) {
            return sorted(documents, plan);
        }

        String cacheKey = sourceCache.key(plan);
        sourceCache.get(cacheKey).ifPresent(cached -> documents.add(cached.source()));
        // 이미 캐시 등으로 웹 출처가 있으면 새로 웹 검색을 하지 않습니다.
        if (hasWebSource(documents)) {
            return sorted(documents, plan);
        }
        // DB에 기간이 남은 근거가 있으면 다시 검색하지 않습니다(검색 엔진 차단 방지).
        com.salus.healthytable.service.ChatProgress.report("저장된 레시피를 찾고 있어요");
        if (webSourceStore != null) {
            webSourceStore.findFresh(cacheKey).ifPresent(documents::add);
            if (hasWebSource(documents)) {
                return sorted(documents, plan);
            }
        }

        com.salus.healthytable.service.ChatProgress.report("웹에서 레시피를 검색하고 있어요");
        List<WebRecipeSearchResult> searchResults = webRecipeSearchPort.search(plan.searchQueries(), Math.max(plan.maxSources() * 2, 6));
        com.salus.healthytable.service.ChatProgress.report("찾은 레시피 페이지를 확인하고 있어요");
        List<RecipeSourceCandidate> webCandidates = structuredRecipePageAdapter.collect(plan, searchResults).stream()
                .filter(candidate -> plan.avoidIngredients().isEmpty() || candidate.originalRecipe() == null
                        || !containsAvoided(String.join("\n", candidate.originalRecipe().ingredients()), plan.avoidIngredients()))
                .toList();
        if (webCandidates.isEmpty()) {
            return sorted(documents, plan);
        }

        // 조리 단계는 원문 문장 대신 요약을 보여 주고 저장합니다. 요약이 되면 그 출처 하나만 씁니다.
        // 요약할 LLM이 없으면 저장하지 않고 기존처럼 원문 출처를 씁니다.
        RecipeSourceCandidate top = webCandidates.get(0);
        com.salus.healthytable.service.ChatProgress.report("조리 순서를 보기 쉽게 정리하고 있어요");
        Optional<List<String>> summaries = stepSummarizer == null
                ? Optional.empty()
                : stepSummarizer.summarize(plan.dishName(), top);
        if (summaries.isPresent()) {
            RecipeSourceCandidate summarized = withStepSummaries(top, summaries.get());
            sourceCache.put(cacheKey, summarized);
            if (webSourceStore != null) {
                webSourceStore.save(cacheKey, top, summaries.get());
            }
            documents.add(summarized.source());
            return sorted(documents, plan);
        }
        sourceCache.put(cacheKey, top);
        webCandidates.stream()
                .map(RecipeSourceCandidate::source)
                .forEach(documents::add);

        return sorted(documents, plan);
    }

    // 재료 이름 비교는 공백·기호를 무시합니다("돼지 고기" = "돼지고기").
    static boolean containsAvoided(String text, List<String> avoid) {
        String normalized = RecipeCandidate.normalize(text);
        return avoid.stream().map(RecipeCandidate::normalize)
                .anyMatch(term -> !term.isBlank() && normalized.contains(term));
    }

    private boolean hasWebSource(List<RecipeSourceDocument> documents) {
        return documents.stream().anyMatch(document -> document.sourceType() == RecipeSourceType.GENERAL_WEB
                || document.sourceType() == RecipeSourceType.OFFICIAL_WEB);
    }

    // 출처 문서와 원본 레시피의 조리 단계를 요약으로 바꾼 후보를 만듭니다. 재료·출처 정보는 그대로입니다.
    private RecipeSourceCandidate withStepSummaries(RecipeSourceCandidate candidate, List<String> summaries) {
        RecipeCandidate original = candidate.originalRecipe();
        RecipeCandidate recipe = new RecipeCandidate(
                original.title(), original.description(), original.ingredients(), summaries,
                original.calories(), original.difficulty(), original.cookingTime(),
                original.coreIngredients(), original.optionalIngredients(), original.healthRiskTags());
        RecipeSourceDocument source = candidate.source();
        String content = """
                title: %s
                description: %s
                creator: %s
                sourceUrl: %s
                canonicalUrl:
                ingredients:
                %s
                steps:
                %s
                core: %s
                optional: %s
                risk: %s
                """.formatted(
                recipe.title(),
                recipe.description() == null ? "" : recipe.description(),
                source.creatorName() == null ? "" : source.creatorName(),
                source.url(),
                String.join("\n", recipe.ingredients()),
                String.join("\n", recipe.steps()),
                String.join(", ", recipe.coreIngredients()),
                String.join(", ", recipe.optionalIngredients()),
                String.join(", ", recipe.healthRiskTags()));
        RecipeSourceDocument summarizedSource = new RecipeSourceDocument(
                source.sourceId(), source.sourceType(), source.title(), source.creatorName(), source.url(),
                content, source.publishedAt(), source.sourceReliability());
        return new RecipeSourceCandidate(summarizedSource, recipe, candidate.qualityScore(), candidate.evidence());
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
