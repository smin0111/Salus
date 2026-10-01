package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;

import java.util.List;

/**
 * LLM에 레시피 생성을 요청할 때 필요한 모든 입력을 묶은 객체입니다.
 *
 * - mode: 새로 만들기, 상세 설명, 재료 대체, 재료 제외 중 어떤 작업인지
 * - trustedRecipes / searchContext / searchSource: LLM이 참고할 근거(신뢰 레시피, 검색 결과, 출처)
 * - fridgeItems: 사용자의 냉장고 재료
 * - safetyConditions: 반드시 지켜야 할 알레르기/건강 조건
 * - previousRecipeText, modifiers, excludedIngredients, substitutions: 기존 레시피를 수정할 때 사용하는 값
 */
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
    // CREATE: 새 레시피 생성, DETAIL: 기존 레시피 상세화, SUBSTITUTE: 재료 대체, EXCLUDE: 재료 제외
    public enum Mode {
        CREATE,
        DETAIL,
        SUBSTITUTE,
        EXCLUDE
    }

    // 레시피 생성 시 반드시 반영해야 하는 사용자 건강 조건
    public record SafetyConditions(
            List<String> allergies,
            List<String> chronicConditions,
            List<String> dietaryRestrictions,
            List<String> medications,
            List<String> goals
    ) {
    }

    // 재료 대체 요청 한 건 (from → to)
    public record IngredientSubstitution(
            String from,
            String to
    ) {
    }
}
