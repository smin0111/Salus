package com.salus.healthytable.service;

import java.util.List;

/**
 * LLM이 생성한 구조화 레시피 초안입니다.
 *
 * "초안"인 이유: LLM 출력은 그대로 믿을 수 없으므로, 이 객체는 검증기(RecipeDraftValidator)와
 * 알레르기 검사를 통과한 뒤에야 사용자에게 보여 줄 레시피로 사용됩니다.
 * adjustments는 재료 대체/제외 같은 변경 내역, safetyNotes는 주의 문구입니다.
 */
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
    // 변경 내역(adjustments) 없이 만드는 보조 생성자입니다.
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
