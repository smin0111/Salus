package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class RecipeDraftMapper {

    public Recipe toRecipe(GeneratedRecipeDraft draft) {
        Recipe recipe = new Recipe();
        recipe.setTitle(nullToBlank(draft.title()).trim());
        recipe.setDescription(nullToBlank(draft.description()).trim());
        recipe.setIngredients(toIngredientLines(draft.ingredients()));
        recipe.setSteps(toStepLines(draft.steps()));
        recipe.setCalories(draft.caloriesKcal());
        recipe.setDifficulty(draft.difficulty());
        recipe.setCookingTime(draft.cookingTimeMinutes());
        return recipe;
    }

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

    public String formatStep(GeneratedCookingStep step) {
        StringBuilder builder = new StringBuilder();
        appendSentence(builder, step.instruction());
        String heatLevel = step.normalizedHeatLevel();
        if (!heatLevel.isBlank() && !"무가열".equals(heatLevel) && !"해당 없음".equals(heatLevel)) {
            appendSentence(builder, "불 세기는 " + heatLevel + "로 맞추세요");
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
