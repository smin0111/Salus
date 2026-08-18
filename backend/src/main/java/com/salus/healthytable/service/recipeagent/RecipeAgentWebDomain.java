package com.salus.healthytable.service.recipeagent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

interface WebRecipeSearchPort {

    List<WebRecipeSearchResult> search(List<String> queries, int maxResults);
}

record WebRecipeSearchResult(
        String title,
        String url,
        String snippet,
        String domain,
        Integer rank
) {
}

interface SafeWebPageFetcher {

    WebPageFetchResult fetch(String url);
}

record WebPageFetchResult(
        String finalUrl,
        int statusCode,
        String contentType,
        String body,
        LocalDateTime fetchedAt,
        String contentHash
) {
}

enum RecipeResearchStatus {
    VERIFIED_SOURCE_FOUND,
    SOURCE_FOUND_BUT_INCOMPLETE,
    NO_RELIABLE_SOURCE,
    FETCH_FAILED,
    EXTRACTION_FAILED
}

record ExtractedRecipeEvidence(
        String title,
        String creatorName,
        String description,
        Integer servings,
        Duration prepTime,
        Duration cookTime,
        Duration totalTime,
        LocalDateTime publishedAt,
        List<ExtractedIngredientLine> ingredients,
        List<ExtractedInstructionStep> steps,
        ExtractedNutrition nutrition,
        List<String> suitableForDiets,
        RecipeEvidenceProvenance provenance
) {
    ExtractedRecipeEvidence {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        steps = steps == null ? List.of() : List.copyOf(steps);
        suitableForDiets = suitableForDiets == null ? List.of() : List.copyOf(suitableForDiets);
    }
}

record ExtractedIngredientLine(
        String originalText,
        String normalizedName,
        Double amount,
        String unit,
        String preparation,
        IngredientParseStatus parseStatus
) {
}

enum IngredientParseStatus {
    FULL,
    PARTIAL,
    UNPARSED
}

record ExtractedInstructionStep(
        Integer position,
        String name,
        String text
) {
}

record ExtractedNutrition(
        String calories,
        String carbohydrateContent,
        String proteinContent,
        String fatContent,
        String sodiumContent,
        String sugarContent
) {
}

record RecipeEvidenceProvenance(
        String sourceUrl,
        String canonicalUrl,
        String sourceDomain,
        String extractionMethod,
        LocalDateTime fetchedAt,
        String contentHash,
        List<String> extractedJsonPaths
) {
    RecipeEvidenceProvenance {
        extractedJsonPaths = extractedJsonPaths == null ? List.of() : List.copyOf(extractedJsonPaths);
    }
}

record RecipeSourceQualityScore(
        double totalScore,
        boolean structuredRecipePresent,
        boolean ingredientsPresent,
        boolean instructionsPresent,
        boolean creatorMatched,
        boolean dishMatched,
        List<String> warnings,
        List<String> blockingReasons
) {
    RecipeSourceQualityScore {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    }

    boolean usable() {
        return blockingReasons.isEmpty();
    }
}

record CachedRecipeEvidence(
        RecipeSourceDocument source,
        RecipeCandidate originalRecipe,
        RecipeSourceQualityScore qualityScore,
        String contentHash,
        LocalDateTime cachedAt,
        LocalDateTime expiresAt
) {
}

record RecipeSourceCandidate(
        RecipeSourceDocument source,
        RecipeCandidate originalRecipe,
        RecipeSourceQualityScore qualityScore,
        ExtractedRecipeEvidence evidence
) {
}
