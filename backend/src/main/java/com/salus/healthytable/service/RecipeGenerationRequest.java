package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;

import java.util.List;

public record RecipeGenerationRequest(
        Mode mode,
        String userMessage,
        String requestedTitle,
        List<Recipe> trustedRecipes,
        String searchContext,
        String searchSource,
        List<String> fridgeItems,
        SafetyConditions safetyConditions,
        String previousRecipeText,
        List<String> modifiers,
        List<String> excludedIngredients,
        List<IngredientSubstitution> substitutions
) {
    public enum Mode {
        CREATE,
        DETAIL,
        SUBSTITUTE,
        EXCLUDE
    }

    public record SafetyConditions(
            List<String> allergies,
            List<String> chronicConditions,
            List<String> dietaryRestrictions,
            List<String> medications,
            List<String> goals
    ) {
    }

    public record IngredientSubstitution(
            String from,
            String to
    ) {
    }
}
