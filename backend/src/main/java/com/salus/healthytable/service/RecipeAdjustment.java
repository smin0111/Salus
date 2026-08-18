package com.salus.healthytable.service;

public record RecipeAdjustment(
        String type,
        String fromIngredient,
        String toIngredient,
        String reason,
        String quantityAdjustment
) {
}
