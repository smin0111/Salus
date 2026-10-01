package com.salus.healthytable.service.recipeagent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 웹에서 수집한 레시피 출처를 평가·정리·캐시하는 클래스들을 모아 둔 파일입니다.
 */

/**
 * 웹 페이지에서 추출한 레시피 근거의 품질을 0~1 점수와 차단 사유로 평가합니다.
 * 차단 사유: 재료 없음, 조리 단계 없음, 요청 요리와 제목 불일치, 요청한 크리에이터와 작성자 불일치, 재료 파싱 85% 이상 실패
 */
@Component
class RecipeSourceQualityAssessor {

    RecipeSourceQualityScore assess(RecipeResearchPlan plan, ExtractedRecipeEvidence evidence) {
        if (evidence == null) {
            return new RecipeSourceQualityScore(0.0, false, false, false, false, false,
                    List.of(), List.of("구조화 레시피 데이터가 없습니다."));
        }
        List<String> warnings = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        boolean ingredientsPresent = !evidence.ingredients().isEmpty();
        boolean instructionsPresent = !evidence.steps().isEmpty();
        boolean dishMatched = dishMatches(plan == null ? "" : plan.dishName(), evidence.title());
        String implicitCreator = implicitCreatorPrefix(plan, evidence);
        boolean creatorMatched = creatorMatches(plan == null ? "" : plan.creatorName(), evidence.creatorName())
                && creatorMatches(implicitCreator, evidence.creatorName());

        if (!ingredientsPresent) {
            blocking.add("재료가 없습니다.");
        }
        if (!instructionsPresent) {
            blocking.add("조리 단계가 없습니다.");
        }
        if (!dishMatched) {
            blocking.add("제목과 요청 요리가 명백히 다릅니다.");
        }
        if (!creatorMatched) {
            blocking.add(implicitCreator.isBlank()
                    ? "작성자 지정 요청과 출처 author가 일치하지 않습니다."
                    : "요청의 제작자 접두어와 출처 author가 일치하지 않습니다.");
        }
        if (evidence.creatorName() == null || evidence.creatorName().isBlank()) {
            warnings.add("작성자 표시가 없습니다.");
        }
        if (evidence.publishedAt() == null) {
            warnings.add("게시일 표시가 없습니다.");
        }
        double unparsedRatio = unparsedRatio(evidence.ingredients());
        if (unparsedRatio > 0.5) {
            warnings.add("재료 문자열 파싱이 일부 불완전합니다.");
        }
        if (unparsedRatio >= 0.85 && ingredientsPresent) {
            blocking.add("파싱 결과가 지나치게 불완전합니다.");
        }
        if (evidence.provenance() != null
                && evidence.provenance().canonicalUrl() != null
                && !evidence.provenance().canonicalUrl().isBlank()
                && !sameUrlWithoutTrailingSlash(evidence.provenance().sourceUrl(), evidence.provenance().canonicalUrl())) {
            warnings.add("페이지 URL과 canonical URL이 다릅니다.");
        }

        // 항목별 가중치를 더해 점수를 만들고, 0~1 범위로 자릅니다.
        double score = 0.0;
        score += 0.22; // JSON-LD Recipe 존재
        score += ingredientsPresent ? 0.18 : 0.0;
        score += instructionsPresent ? 0.20 : 0.0;
        score += !isBlank(evidence.creatorName()) ? 0.08 : 0.0;
        score += evidence.publishedAt() != null ? 0.06 : 0.0;
        score += completenessScore(evidence) * 0.12;
        score += dishMatched ? 0.10 : 0.0;
        score += creatorMatched ? 0.04 : 0.0;
        score = Math.max(0.0, Math.min(1.0, score));

        return new RecipeSourceQualityScore(
                score,
                true,
                ingredientsPresent,
                instructionsPresent,
                creatorMatched,
                dishMatched,
                warnings,
                blocking);
    }

    // 요청 요리 이름이 비어 있으면 통과, 아니면 제목과 서로 포함 관계인지 확인합니다.
    boolean dishMatches(String requestedDish, String title) {
        String requested = RecipeCandidate.normalize(requestedDish);
        String normalizedTitle = RecipeCandidate.normalize(title);
        if (requested.isBlank()) {
            return true;
        }
        if (normalizedTitle.isBlank()) {
            return false;
        }
        return normalizedTitle.contains(requested) || requested.contains(normalizedTitle);
    }

    // 요청한 크리에이터가 없으면 통과, 있으면 출처 작성자와 서로 포함 관계인지 확인합니다.
    boolean creatorMatches(String requestedCreator, String actualCreator) {
        String requested = RecipeCandidate.normalize(requestedCreator);
        if (requested.isBlank()) {
            return true;
        }
        String actual = RecipeCandidate.normalize(actualCreator);
        return !actual.isBlank() && (actual.contains(requested) || requested.contains(actual));
    }

    /**
     * "백종원김치찌개"처럼 요청 요리 이름 앞에 붙은 접두어가 크리에이터 이름일 수 있는지 추정합니다.
     * 접두어가 재료 이름("돼지김치찌개"의 돼지)이면 크리에이터로 보지 않습니다.
     */
    private String implicitCreatorPrefix(RecipeResearchPlan plan, ExtractedRecipeEvidence evidence) {
        if (plan == null || evidence == null || (plan.creatorName() != null && !plan.creatorName().isBlank())) {
            return "";
        }
        String requested = RecipeCandidate.normalize(plan.dishName());
        String title = RecipeCandidate.normalize(evidence.title());
        if (requested.isBlank() || title.isBlank() || requested.equals(title) || !requested.endsWith(title)) {
            return "";
        }
        String prefix = requested.substring(0, requested.length() - title.length());
        if (prefix.length() < 2) {
            return "";
        }
        boolean ingredientQualifier = evidence.ingredients().stream()
                .map(ingredient -> RecipeCandidate.normalize(
                        ingredient.normalizedName() == null || ingredient.normalizedName().isBlank()
                                ? ingredient.originalText()
                                : ingredient.normalizedName()))
                .anyMatch(ingredient -> ingredient.contains(prefix) || prefix.contains(ingredient));
        return ingredientQualifier ? "" : prefix;
    }

    // 재료 최대 5개, 조리 단계 최대 5개를 기준으로 완성도를 0~1로 계산합니다.
    private double completenessScore(ExtractedRecipeEvidence evidence) {
        int ingredientScore = Math.min(5, evidence.ingredients().size());
        int stepScore = Math.min(5, evidence.steps().size());
        return (ingredientScore + stepScore) / 10.0;
    }

    // 파싱하지 못한 재료 줄의 비율
    private double unparsedRatio(List<ExtractedIngredientLine> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) {
            return 0.0;
        }
        long unparsed = ingredients.stream()
                .filter(ingredient -> ingredient.parseStatus() == IngredientParseStatus.UNPARSED)
                .count();
        return (double) unparsed / ingredients.size();
    }

    private boolean sameUrlWithoutTrailingSlash(String first, String second) {
        return normalizeUrl(first).equals(normalizeUrl(second));
    }

    private String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String normalized = url.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

/**
 * 웹 검색 결과 페이지를 안전하게 가져와 schema.org 레시피를 추출하고, 품질 평가를 통과한 출처 후보를 만드는 어댑터입니다.
 * 페이지 하나가 실패해도 다음 결과로 넘어가며, 결과는 중복 제거 후 품질 점수 순으로 정렬합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class StructuredRecipePageAdapter {

    private final SafeWebPageFetcher fetcher;
    private final SchemaOrgRecipeJsonLdExtractor extractor;
    private final RecipeSourceQualityAssessor qualityAssessor;

    List<RecipeSourceCandidate> collect(RecipeResearchPlan plan, List<WebRecipeSearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return List.of();
        }
        List<RecipeSourceCandidate> candidates = new ArrayList<>();
        for (WebRecipeSearchResult result : searchResults) {
            if (result == null || result.url() == null || result.url().isBlank()) {
                continue;
            }
            try {
                WebPageFetchResult page = fetcher.fetch(result.url());
                List<ExtractedRecipeEvidence> evidenceList = extractor.extract(page);
                if (evidenceList.isEmpty()) {
                    continue;
                }
                for (ExtractedRecipeEvidence evidence : evidenceList) {
                    RecipeSourceQualityScore qualityScore = qualityAssessor.assess(plan, evidence);
                    if (!qualityScore.usable() && !isIngredientsOnlyEvidence(qualityScore)) {
                        continue;
                    }
                    candidates.add(toSourceCandidate(evidence, qualityScore));
                }
            } catch (SafeWebPageFetchException e) {
                log.debug("[RecipeAgentWebSource] Fetch skipped. domain={}, failureCategory={}", result.domain(), e.getClass().getSimpleName());
            } catch (Exception e) {
                log.debug("[RecipeAgentWebSource] Extraction skipped. domain={}, failureCategory={}", result.domain(), e.getClass().getSimpleName());
            }
        }
        return deduplicate(candidates).stream()
                .sorted(Comparator.comparing((RecipeSourceCandidate candidate) -> candidate.qualityScore().totalScore()).reversed())
                .toList();
    }

    // 조리 단계만 없고 나머지는 모두 맞는 출처는 재료 근거로는 쓸 수 있으므로 예외적으로 허용합니다.
    private boolean isIngredientsOnlyEvidence(RecipeSourceQualityScore qualityScore) {
        return qualityScore.structuredRecipePresent()
                && qualityScore.ingredientsPresent()
                && !qualityScore.instructionsPresent()
                && qualityScore.creatorMatched()
                && qualityScore.dishMatched()
                && qualityScore.blockingReasons().stream().allMatch("조리 단계가 없습니다."::equals);
    }

    /**
     * 중복 출처를 제거합니다(품질 점수 높은 것을 우선 유지).
     * - 완전 중복: canonical URL, 본문 해시, 제목+작성자 중 하나가 같음
     * - 유사 중복: 같은 작성자이면서 핵심 재료 유사도(자카드 유사도)가 0.8 이상
     */
    List<RecipeSourceCandidate> deduplicate(List<RecipeSourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RecipeSourceCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparing((RecipeSourceCandidate candidate) -> candidate.qualityScore().totalScore()).reversed())
                .toList();
        List<RecipeSourceCandidate> unique = new ArrayList<>();
        Set<String> exactKeys = new LinkedHashSet<>();
        for (RecipeSourceCandidate candidate : sorted) {
            String exactKey = exactDuplicateKey(candidate);
            if (!exactKey.isBlank() && !exactKeys.add(exactKey)) {
                continue;
            }
            boolean similarDuplicate = unique.stream().anyMatch(existing -> sameCreator(existing, candidate)
                    && coreIngredientSimilarity(existing.originalRecipe(), candidate.originalRecipe()) >= 0.8);
            if (!similarDuplicate) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    // 추출한 근거를 출처 문서와 후보 레시피로 변환합니다. 신뢰도에는 품질 점수를 사용합니다.
    private RecipeSourceCandidate toSourceCandidate(ExtractedRecipeEvidence evidence, RecipeSourceQualityScore qualityScore) {
        RecipeCandidate recipe = toRecipeCandidate(evidence);
        RecipeEvidenceProvenance provenance = evidence.provenance();
        RecipeSourceDocument source = new RecipeSourceDocument(
                "web:" + blank(provenance.contentHash(), Integer.toHexString(recipe.hashCode())),
                RecipeSourceType.GENERAL_WEB,
                recipe.title(),
                evidence.creatorName(),
                provenance.sourceUrl(),
                toSourceContent(evidence, recipe),
                evidence.publishedAt() == null ? provenance.fetchedAt() : evidence.publishedAt(),
                qualityScore.totalScore());
        return new RecipeSourceCandidate(source, recipe, qualityScore, evidence);
    }

    // 근거의 재료/조리 단계로 후보 레시피를 만듭니다. 제목에 들어간 재료를 우선으로 최대 3개를 핵심 재료로 추정합니다.
    private RecipeCandidate toRecipeCandidate(ExtractedRecipeEvidence evidence) {
        List<String> ingredients = evidence.ingredients().stream()
                .map(ExtractedIngredientLine::originalText)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        List<String> steps = evidence.steps().stream()
                .map(ExtractedInstructionStep::text)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        List<String> names = evidence.ingredients().stream()
                .map(this::ingredientName)
                .filter(value -> !value.isBlank())
                .toList();
        List<String> core = inferCoreIngredients(evidence.title(), names);
        List<String> optional = names.stream()
                .filter(name -> core.stream().noneMatch(coreName -> RecipeCandidate.normalize(coreName).equals(RecipeCandidate.normalize(name))))
                .toList();
        return new RecipeCandidate(
                blank(evidence.title(), "웹 레시피"),
                blank(evidence.description(), ""),
                ingredients,
                steps,
                calories(evidence.nutrition()),
                null,
                minutes(evidence.totalTime() == null ? evidence.prepTime() : evidence.totalTime()),
                core,
                optional,
                healthRiskTags(ingredients));
    }

    // RecipeCandidateBuilder가 다시 파싱할 수 있는 "key: value" 텍스트 형식으로 출처 본문을 만듭니다.
    private String toSourceContent(ExtractedRecipeEvidence evidence, RecipeCandidate recipe) {
        return """
                title: %s
                description: %s
                creator: %s
                sourceUrl: %s
                canonicalUrl: %s
                ingredients:
                %s
                steps:
                %s
                core: %s
                optional: %s
                risk: %s
                """.formatted(
                blank(evidence.title(), ""),
                blank(evidence.description(), ""),
                blank(evidence.creatorName(), ""),
                evidence.provenance().sourceUrl(),
                blank(evidence.provenance().canonicalUrl(), ""),
                String.join("\n", recipe.ingredients()),
                String.join("\n", recipe.steps()),
                String.join(", ", recipe.coreIngredients()),
                String.join(", ", recipe.optionalIngredients()),
                String.join(", ", recipe.healthRiskTags()));
    }

    private String exactDuplicateKey(RecipeSourceCandidate candidate) {
        RecipeEvidenceProvenance provenance = candidate.evidence().provenance();
        String canonical = provenance.canonicalUrl();
        if (canonical != null && !canonical.isBlank()) {
            return normalizeUrl(canonical);
        }
        if (provenance.contentHash() != null && !provenance.contentHash().isBlank()) {
            return "hash:" + provenance.contentHash();
        }
        String titleCreator = RecipeCandidate.normalize(candidate.originalRecipe().title() + " " + candidate.evidence().creatorName());
        return titleCreator.isBlank() ? "" : "title:" + titleCreator;
    }

    private boolean sameCreator(RecipeSourceCandidate first, RecipeSourceCandidate second) {
        String firstCreator = RecipeCandidate.normalize(first.evidence().creatorName());
        String secondCreator = RecipeCandidate.normalize(second.evidence().creatorName());
        return firstCreator.isBlank() || secondCreator.isBlank() || firstCreator.equals(secondCreator);
    }

    // 두 레시피의 핵심 재료 집합 유사도 = 교집합 크기 / 합집합 크기(자카드 유사도)
    private double coreIngredientSimilarity(RecipeCandidate first, RecipeCandidate second) {
        Set<String> left = normalizedSet(first.coreIngredients().isEmpty() ? first.ingredients() : first.coreIngredients());
        Set<String> right = normalizedSet(second.coreIngredients().isEmpty() ? second.ingredients() : second.coreIngredients());
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        long intersection = left.stream().filter(right::contains).count();
        Set<String> unionSet = new LinkedHashSet<>();
        unionSet.addAll(left);
        unionSet.addAll(right);
        long union = unionSet.size();
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private Set<String> normalizedSet(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = RecipeCandidate.normalize(value);
            if (!item.isBlank()) {
                normalized.add(item);
            }
        }
        return normalized;
    }

    // 영양 정보의 칼로리 문자열("250 calories")에서 첫 숫자를 읽습니다.
    private Integer calories(ExtractedNutrition nutrition) {
        if (nutrition == null || nutrition.calories() == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(nutrition.calories());
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private Integer minutes(Duration duration) {
        if (duration == null) {
            return null;
        }
        return Math.max(1, (int) duration.toMinutes());
    }

    // 당류 재료가 있으면 "high_added_sugar" 위험 태그를 붙입니다(만성질환 정책에서 사용).
    private List<String> healthRiskTags(List<String> ingredients) {
        String text = String.join(" ", ingredients);
        if (AgentText.containsAnyNormalized(text, List.of("설탕", "시럽", "꿀", "올리고당", "물엿", "연유", "캐러멜"))) {
            return List.of("high_added_sugar");
        }
        return List.of();
    }

    private String ingredientName(ExtractedIngredientLine ingredient) {
        if (ingredient == null) {
            return "";
        }
        return blank(ingredient.normalizedName(), ingredient.originalText());
    }

    private List<String> inferCoreIngredients(String title, List<String> ingredientNames) {
        LinkedHashSet<String> core = new LinkedHashSet<>();
        String normalizedTitle = RecipeCandidate.normalize(title);
        for (String name : ingredientNames) {
            String normalizedName = RecipeCandidate.normalize(name);
            if (!normalizedName.isBlank() && normalizedTitle.contains(normalizedName)) {
                core.add(name);
            }
        }
        for (String name : ingredientNames) {
            if (core.size() >= 3) {
                break;
            }
            core.add(name);
        }
        return List.copyOf(core);
    }

    private String normalizeUrl(String url) {
        String normalized = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

/**
 * 웹 출처 수집 결과를 서버 메모리에 6시간 동안 보관하는 간단한 캐시입니다.
 * 같은 요리/크리에이터/모드 요청이 반복될 때 외부 웹 요청을 줄입니다. 서버를 재시작하면 비워집니다.
 */
@Component
class InMemoryRecipeSourceCache {

    private static final Duration DEFAULT_TTL = Duration.ofHours(6);

    private final Map<String, CachedRecipeEvidence> cache = new ConcurrentHashMap<>();

    Optional<CachedRecipeEvidence> get(String key) {
        CachedRecipeEvidence cached = cache.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.expiresAt().isBefore(LocalDateTime.now())) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(cached);
    }

    void put(String key, RecipeSourceCandidate candidate) {
        if (key == null || key.isBlank() || candidate == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        cache.put(key, new CachedRecipeEvidence(
                candidate.source(),
                candidate.originalRecipe(),
                candidate.qualityScore(),
                candidate.evidence().provenance().contentHash(),
                now,
                now.plus(DEFAULT_TTL)));
    }

    // 캐시 키: 요리 이름|크리에이터|요청 모드|언어
    String key(RecipeResearchPlan plan) {
        if (plan == null) {
            return "";
        }
        return String.join("|",
                RecipeCandidate.normalize(plan.dishName()),
                RecipeCandidate.normalize(plan.creatorName()),
                plan.mode() == null ? "" : plan.mode().name().toLowerCase(Locale.ROOT),
                "ko");
    }

    // 현재 캐시 내용을 복사해 반환합니다(테스트/점검용).
    Map<String, CachedRecipeEvidence> snapshot() {
        return new LinkedHashMap<>(cache);
    }
}
