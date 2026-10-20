package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * LLM이 생성한 구조화 초안(GeneratedRecipeDraft)을 화면/DB에서 쓰는 Recipe 엔티티 형태로 변환합니다.
 * 재료와 조리 단계를 사람이 읽을 수 있는 한 줄 문자열 목록으로 만듭니다.
 */
@Component
public class RecipeDraftMapper {

    // 초안을 Recipe로 변환합니다. 초안의 caloriesKcal은 1인분 기준 열량으로 저장합니다.
    public Recipe toRecipe(GeneratedRecipeDraft draft) {
        Recipe recipe = new Recipe();
        recipe.setTitle(nullToBlank(draft.title()).trim());
        recipe.setDescription(nullToBlank(draft.description()).trim());
        recipe.setIngredients(toIngredientLines(draft.ingredients()));
        recipe.setSteps(toStepLines(draft.steps()));
        recipe.setBaseServings(draft.servings());
        recipe.setCalories(draft.caloriesKcal());
        recipe.setCaloriesPerServing(draft.caloriesKcal());
        recipe.setDifficulty(draft.difficulty());
        recipe.setCookingTime(draft.cookingTimeMinutes());
        return recipe;
    }

    // 재료를 "이름 수량 (손질 방법)" 형태의 문자열로 만듭니다. 예) "양파 1개 (채 썰기)"
    public List<String> toIngredientLines(List<GeneratedIngredient> ingredients) {
        if (ingredients == null) {
            return List.of();
        }
        return ingredients.stream()
                .filter(ingredient -> ingredient != null && !nullToBlank(ingredient.name()).isBlank())
                .map(ingredient -> {
                    String quantity = nullToBlank(ingredient.quantity()).trim();
                    String line = quantity.isBlank()
                            ? ingredient.name().trim()
                            : ingredient.name().trim() + " " + quantity;
                    String preparation = nullToBlank(ingredient.preparation()).trim();
                    return preparation.isBlank() ? line : line + " (" + preparation + ")";
                })
                .toList();
    }

    // 설명이 있는 단계만 order 순서대로 정렬해 문장으로 만듭니다. order가 없는 단계는 맨 뒤로 보냅니다.
    public List<String> toStepLines(List<GeneratedCookingStep> steps) {
        if (steps == null) {
            return List.of();
        }
        return steps.stream()
                .filter(step -> step != null && !nullToBlank(step.instruction()).isBlank())
                .sorted(Comparator.comparing(step -> step.order() == null ? Integer.MAX_VALUE : step.order()))
                .map(this::formatStep)
                .toList();
    }

    /**
     * 조리 단계 하나를 초보자용 문장으로 조립합니다.
     * 예) "양파를 볶으세요. 불 세기는 중불로 맞추세요. 3분 정도 진행하세요. 갈색이 되면 다음 단계로 넘어가세요."
     */
    public String formatStep(GeneratedCookingStep step) {
        StringBuilder builder = new StringBuilder();
        appendSentence(builder, step.instruction());
        String heatLevel = step.normalizedHeatLevel();
        if (!heatLevel.isBlank() && !"무가열".equals(heatLevel) && !"해당 없음".equals(heatLevel)) {
            appendSentence(builder, "불 세기는 " + heatLevel + "로 맞추세요");
        }
        // 온도(℃)는 오븐/에어프라이어처럼 온도를 설정하는 기구를 쓰는 단계에서만 표시합니다.
        if (usesControlledTemperatureAppliance(step.instruction())
                && step.temperatureC() != null && step.temperatureC() > 0) {
            appendSentence(builder, "조리 온도는 " + step.temperatureC() + "℃로 맞추세요");
        }
        if (step.minutes() != null && step.minutes() > 0) {
            appendSentence(builder, step.minutes() + "분 정도 진행하세요");
        }
        if (!nullToBlank(step.completionCue()).isBlank()) {
            appendSentence(builder, step.completionCue().trim() + "가 되면 다음 단계로 넘어가세요");
        }
        if (!nullToBlank(step.recoveryTip()).isBlank()) {
            appendSentence(builder, step.recoveryTip());
        }
        return builder.toString().trim();
    }

    private boolean usesControlledTemperatureAppliance(String instruction) {
        String normalized = nullToBlank(instruction).replaceAll("\\s+", "").toLowerCase();
        return normalized.contains("오븐")
                || normalized.contains("에어프라이어")
                || normalized.contains("에프에")
                || normalized.contains("베이크");
    }

    // 문장 끝에 마침표가 없으면 붙이고, 앞 문장과 공백으로 이어 붙입니다.
    private void appendSentence(StringBuilder builder, String value) {
        String trimmed = nullToBlank(value).trim();
        if (trimmed.isBlank()) {
            return;
        }
        if (!trimmed.endsWith(".") && !trimmed.endsWith("!") && !trimmed.endsWith("?")) {
            trimmed += ".";
        }
        if (builder.length() > 0) {
            builder.append(" ");
        }
        builder.append(trimmed);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
