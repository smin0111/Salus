package com.salus.healthytable.service.recipeagent;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    // 처음 보는 요리를 웹에서 찾는 하루 횟수 제한. 없으면(테스트 등) 제한하지 않습니다.
    private NewDishSearchQuota newDishSearchQuota;

    @Autowired(required = false)
    void setNewDishSearchQuota(NewDishSearchQuota newDishSearchQuota) {
        this.newDishSearchQuota = newDishSearchQuota;
    }

    // 웹 출처 검색 사용 여부(기본 false)
    @Value("${recipe.agent.web-source-enabled:false}")
    private boolean webSourceEnabled;

    @Override
    public List<RecipeSourceDocument> search(RecipeResearchPlan plan, UserRecipeContext context) {
        List<RecipeSourceDocument> documents = new ArrayList<>(internalRecipeSourceAdapter.search(plan, context));
        // 유튜브는 저장된 레시피가 없을 때만 부릅니다(아래). 먼저 부르면 저장된 요리를 다시 물어도 매번 할당량 201단위를 쓰고
        // 1초 넘게 늦어집니다(2026-10-14 로그: 저장된 레시피로 답한 요청마다 유튜브 검색 3회).
        // 뺀 재료 없이 만드는 레시피를 찾는 중이면 그 재료가 든 출처는 처음부터 후보에서 뺍니다.
        if (plan != null && !plan.avoidIngredients().isEmpty()) {
            documents.removeIf(document -> containsAvoided(document.content(), plan.avoidIngredients()));
        }
        // "다른 레시피" 요청이면 이미 보여 준 출처는 후보에서 뺍니다.
        if (plan != null && !plan.excludedSourceUrls().isEmpty()) {
            documents.removeIf(document -> isExcludedSource(document.url(), plan.excludedSourceUrls()));
        }
        if (!webSourceEnabled || plan == null) {
            documents.addAll(filtered(youtubeSources(plan, context), plan));
            return sorted(documents, plan);
        }

        String cacheKey = sourceCache.key(plan);
        sourceCache.get(cacheKey).ifPresent(cached -> {
            documents.add(cached.source());
            documents.addAll(sourceCache.supporting(cacheKey));
        });
        // 이미 캐시 등으로 웹 출처가 있으면 새로 웹 검색을 하지 않습니다.
        if (hasWebSource(documents)) {
            return sorted(documents, plan);
        }
        // DB에 기간이 남은 근거가 있으면 다시 검색하지 않습니다(검색 엔진 차단 방지).
        com.salus.healthytable.service.ChatProgress.report("저장된 레시피를 찾고 있어요");
        if (webSourceStore != null) {
            documents.addAll(webSourceStore.findFreshWithSupporting(cacheKey));
            if (hasWebSource(documents)) {
                return sorted(documents, plan);
            }
        }

        // 저장된 근거가 없어 새로 찾는 경우만 하루 횟수를 씁니다. 다 썼으면 여기서 멈춥니다(유튜브·웹 모두 부르지 않음).
        if (newDishSearchQuota != null) {
            newDishSearchQuota.consumeForCurrentRequest();
        }
        documents.addAll(filtered(youtubeSources(plan, context), plan));
        com.salus.healthytable.service.ChatProgress.report("웹에서 레시피를 검색하고 있어요");
        List<WebRecipeSearchResult> searchResults = webRecipeSearchPort.search(plan.searchQueries(), Math.max(plan.maxSources() * 2, 6))
                .stream()
                // 이미 보여 준 페이지는 가져오기 전에 뺍니다. 나중에 빼면 그 페이지가 구조화 출처 자리를 차지해
                // 다른 블로그 본문 추출이 건너뛰어지고 후보가 0개가 됩니다(2026-10-12 "다른 짬뽕탕 레시피").
                .filter(result -> !isExcludedSource(result.url(), plan.excludedSourceUrls()))
                .toList();
        com.salus.healthytable.service.ChatProgress.report("찾은 레시피 페이지를 확인하고 있어요");
        List<RecipeSourceCandidate> webCandidates = structuredRecipePageAdapter.collect(plan, searchResults).stream()
                .filter(candidate -> plan.avoidIngredients().isEmpty() || candidate.originalRecipe() == null
                        || !containsAvoided(String.join("\n", candidate.originalRecipe().ingredients()), plan.avoidIngredients()))
                .filter(candidate -> candidate.source() == null || !isExcludedSource(candidate.source().url(), plan.excludedSourceUrls()))
                .toList();
        if (webCandidates.isEmpty()) {
            return sorted(documents, plan);
        }

        // 조리 단계는 원문 문장 대신 요약을 보여 주고 저장합니다. 요약이 되면 그 출처 하나만 씁니다.
        // 요약할 LLM이 없으면 저장하지 않고 기존처럼 원문 출처를 씁니다.
        RecipeSourceCandidate top = webCandidates.get(0);
        com.salus.healthytable.service.ChatProgress.report("조리 순서를 보기 쉽게 정리하고 있어요");
        SummaryOutcome outcome = stepSummarizer == null
                ? SummaryOutcome.ofUnavailable()
                : stepSummarizer.summarizeOutcome(plan.dishName(), top);
        Optional<List<String>> summaries = outcome.summary();
        // 요약을 품질 문제로 버렸으면 원문 단계를 그대로 저장합니다. 같은 원문·모델로 다시 해도 대개 또 버려져
        // 서버를 재시작할 때마다 70~90초를 되풀이했습니다(2026-10-13 실전 평가: 잡채·참치김치찌개·차돌된장찌개).
        // 규칙 버전(SELECTION_VERSION)을 올리거나 저장 기간이 끝나면 다시 요약합니다. LLM이 없거나 실패한 경우는 저장하지 않습니다.
        if (summaries.isEmpty() && outcome.rejected()) {
            log.info("[RecipeStepSummary] Keeping original steps. stepCount={}", top.originalRecipe().steps().size());
            summaries = Optional.of(top.originalRecipe().steps());
        }
        // 표시용 정리: 다른 변형 레시피 단계("김치비빔국수는 …")를 빼고 원문 소제목 번호("오이 초무침①")를 정리합니다.
        String sourceTitle = top.source() == null ? "" : top.source().title();
        summaries = summaries.map(steps -> RecipeStepSummarizer.tidySteps(plan.dishName(), sourceTitle, steps));
        if (summaries.isPresent()) {
            RecipeSourceCandidate summarized = withStepSummaries(top, summaries.get());
            sourceCache.put(cacheKey, summarized);
            // 대표와 함께 비교한 출처(최대 2곳)도 재료와 링크만 남겨 같이 보여 줍니다(2026-10-12 사용자 요청:
            // 레시피 한 곳만이 아니라 2~3곳에서 찾아 답하기). 대표는 이미 여러 출처와 겹치는 재료 구성을 우대해 골랐습니다.
            List<RecipeSourceCandidate> supporting = webCandidates.stream()
                    .skip(1)
                    .filter(candidate -> candidate.qualityScore() != null && candidate.qualityScore().usable())
                    .filter(candidate -> candidate.source() != null && candidate.source().url() != null)
                    // 다른 사이트를 먼저, 모자라면 같은 사이트의 다른 글로 채웁니다(검색 순위는 유지).
                    .sorted(Comparator.comparing((RecipeSourceCandidate candidate) ->
                            sameSite(candidate.source().url(), top.source().url())))
                    .limit(2)
                    .toList();
            List<RecipeSourceDocument> supportingDocuments = supporting.stream()
                    .map(candidate -> supportingDocument(candidate, summarized.source().sourceReliability()))
                    .toList();
            sourceCache.putSupporting(cacheKey, supportingDocuments);
            if (webSourceStore != null) {
                webSourceStore.save(cacheKey, top, summaries.get());
                supporting.forEach(candidate -> webSourceStore.saveSupporting(cacheKey, candidate));
            }
            documents.add(summarized.source());
            documents.addAll(supportingDocuments);
            return sorted(documents, plan);
        }
        sourceCache.put(cacheKey, top);
        webCandidates.stream()
                .map(RecipeSourceCandidate::source)
                .forEach(documents::add);

        return sorted(documents, plan);
    }

    // 비교 출처 문서: 재료와 링크만 담고(조리 단계 없음), 대표보다 앞에 서지 않게 신뢰도를 낮춥니다.
    private RecipeSourceDocument supportingDocument(RecipeSourceCandidate candidate, double primaryReliability) {
        RecipeSourceDocument source = candidate.source();
        String content = "title: " + RecipeDisplayTitle.of(source.title(), candidate.originalRecipe().title()) + "\n"
                + "ingredients:\n" + String.join("\n", candidate.originalRecipe().ingredients()) + "\n";
        return WebRecipeSourceStore.belowPrimary(new RecipeSourceDocument(
                source.sourceId(), source.sourceType(), source.title(), source.creatorName(), source.url(), content,
                source.publishedAt(), source.sourceReliability()), primaryReliability);
    }

    // 같은 사이트인지 봅니다(비교 출처는 다른 사이트를 먼저 고릅니다).
    private static boolean sameSite(String url, String other) {
        return sourceKey(url).split("/", 2)[0].equals(sourceKey(other).split("/", 2)[0]);
    }

    // 같은 글의 주소 차이(https/http, www·m. 접두어, 끝의 /)는 무시하고 비교합니다
    // (네이버 블로그는 모바일 주소로 가져오므로 검색 주소와 저장 주소가 다를 수 있음).
    static boolean isExcludedSource(String url, List<String> excluded) {
        if (url == null || excluded.isEmpty()) {
            return false;
        }
        String key = sourceKey(url);
        return excluded.stream().map(CompositeRecipeSourceDiscoveryAdapter::sourceKey).anyMatch(key::equals);
    }

    private static String sourceKey(String url) {
        return url.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceFirst("^https?://", "")
                .replaceFirst("^(?:www\\.|m\\.)", "")
                .replaceFirst("/+$", "");
    }

    // 재료 이름 비교는 공백·기호를 무시합니다("돼지 고기" = "돼지고기"). 사전에 있는 재료는 파생 재료도 봅니다
    // ("돼지고기" 대안을 찾는데 "삼겹살" 레시피를 고르지 않도록, 2026-10-13). 판정은 알레르기와 같은 매처를 씁니다.
    static boolean containsAvoided(String text, List<String> avoid) {
        String normalized = RecipeCandidate.normalize(text);
        boolean literal = avoid.stream().map(RecipeCandidate::normalize)
                .anyMatch(term -> !term.isBlank() && normalized.contains(term));
        return literal || !AvoidMatcherHolder.MATCHER.findConflicts(avoid, List.of(text == null ? "" : text)).isEmpty();
    }

    // 정적 검사에서 쓰는 매처입니다. 운영 Bean과 같은 사전 파일(allergens/ko-allergens.yaml)을 읽습니다.
    private static final class AvoidMatcherHolder {
        private static final com.salus.healthytable.service.allergen.AllergenMatcher MATCHER = create();

        private static com.salus.healthytable.service.allergen.AllergenMatcher create() {
            com.salus.healthytable.service.allergen.AllergenDictionary dictionary =
                    new com.salus.healthytable.service.allergen.AllergenDictionary();
            dictionary.load();
            return new com.salus.healthytable.service.allergen.AllergenMatcher(dictionary);
        }
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
        // 뺀 재료 없이 다시 찾는 대안 검색에서는 유튜브를 쓰지 않습니다(할당량·시간 절약, 블로그 본문 추출 생략과 같은 이유).
        if (plan != null && !plan.avoidIngredients().isEmpty()) {
            return List.of();
        }
        try {
            return youTubeRecipeSourceDiscoveryAdapter.search(plan, context);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    // 뺀 재료가 든 출처와 이미 보여 준 출처를 뺍니다.
    private List<RecipeSourceDocument> filtered(List<RecipeSourceDocument> sources, RecipeResearchPlan plan) {
        if (plan == null) {
            return sources;
        }
        return sources.stream()
                .filter(document -> plan.avoidIngredients().isEmpty() || !containsAvoided(document.content(), plan.avoidIngredients()))
                .filter(document -> !isExcludedSource(document.url(), plan.excludedSourceUrls()))
                .toList();
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
