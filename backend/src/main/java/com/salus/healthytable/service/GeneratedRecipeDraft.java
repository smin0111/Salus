package com.salus.healthytable.service;

import java.util.List;

public record GeneratedRecipeDraft(
        String title,
        String description,
        Integer servings,
        Integer cookingTimeMinutes,
        Integer caloriesKcal,
        Integer difficulty,
        List<GeneratedIngredient> ingredients,
        List<GeneratedCookingStep> steps,
        List<RecipeAdjustment> adjustments,
        List<String> safetyNotes
) {
    public GeneratedRecipeDraft(
            String title,
            String description,
            Integer servings,
            Integer cookingTimeMinutes,
            Integer caloriesKcal,
            Integer difficulty,
            List<GeneratedIngredient> ingredients,
            List<GeneratedCookingStep> steps,
            List<String> safetyNotes) {
        this(title, description, servings, cookingTimeMinutes, caloriesKcal, difficulty, ingredients, steps, List.of(), safetyNotes);
    }
}
