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

    boolean creatorMatches(String requestedCreator, String actualCreator) {
        String requested = RecipeCandidate.normalize(requestedCreator);
        if (requested.isBlank()) {
            return true;
        }
        String actual = RecipeCandidate.normalize(actualCreator);
        return !actual.isBlank() && (actual.contains(requested) || requested.contains(actual));
    }

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

    private double completenessScore(ExtractedRecipeEvidence evidence) {
        int ingredientScore = Math.min(5, evidence.ingredients().size());
        int stepScore = Math.min(5, evidence.steps().size());
        return (ingredientScore + stepScore) / 10.0;
    }

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

    private boolean isIngredientsOnlyEvidence(RecipeSourceQualityScore qualityScore) {
        return qualityScore.structuredRecipePresent()
                && qualityScore.ingredientsPresent()
                && !qualityScore.instructionsPresent()
                && qualityScore.creatorMatched()
                && qualityScore.dishMatched()
                && qualityScore.blockingReasons().stream().allMatch("조리 단계가 없습니다."::equals);
    }

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

    Map<String, CachedRecipeEvidence> snapshot() {
        return new LinkedHashMap<>(cache);
    }
}
