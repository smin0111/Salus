package com.salus.healthytable.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class RecipeDraftValidator {

    private final IngredientAliasNormalizer ingredientAliasNormalizer = new IngredientAliasNormalizer();
    private static final Set<String> ALLOWED_UNITS = Set.of(
            "g", "kg", "ml", "L", "개", "장", "대", "모", "컵", "큰술", "작은술", "약간");
    private static final Set<String> NO_HEAT_LEVELS = Set.of("", "무가열", "해당 없음");

    public ValidationResult validate(RecipeGenerationRequest request, GeneratedRecipeDraft draft) {
        List<String> codes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        if (draft == null) {
            add(codes, reasons, "DRAFT_NULL", "레시피 JSON 객체가 비어 있습니다.");
            return ValidationResult.retryable(codes, reasons);
        }

        validateRequiredFields(draft, codes, reasons);
        validateStepOrder(draft, codes, reasons);
        validateIngredientUnits(draft, codes, reasons);
        validateHeatLevels(draft, codes, reasons);
        validateNoHeatRecipe(request, draft, codes, reasons);
        validateStepIngredients(draft, codes, reasons);
        validateRequestedSubstitutions(request, draft, codes, reasons);
        validateExcludedIngredients(request, draft, codes, reasons);
        validateCookingTime(draft, codes, reasons);

        if (violatesDietaryRestriction(request, draft, reasons)) {
            codes.add("DIETARY_RESTRICTION_CONFLICT");
            return ValidationResult.blocking(codes, reasons);
        }

        return codes.isEmpty()
                ? ValidationResult.ok()
                : ValidationResult.retryable(codes, reasons);
    }

    private void validateRequiredFields(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        if (isBlank(draft.title())) {
            add(codes, reasons, "TITLE_REQUIRED", "title은 필수입니다.");
        }
        if (draft.difficulty() == null || draft.difficulty() < 1 || draft.difficulty() > 3) {
            add(codes, reasons, "DIFFICULTY_INVALID", "difficulty는 1~3 사이여야 합니다.");
        }
        if (draft.ingredients() == null || draft.ingredients().isEmpty()) {
            add(codes, reasons, "INGREDIENTS_REQUIRED", "ingredients는 빈 배열이면 안 됩니다.");
        } else {
            for (int i = 0; i < draft.ingredients().size(); i++) {
                GeneratedIngredient ingredient = draft.ingredients().get(i);
                if (ingredient == null || isBlank(ingredient.name()) || isBlank(ingredient.quantity())) {
                    add(codes, reasons, "INGREDIENT_QUANTITY_REQUIRED",
                            "ingredients[" + i + "]에는 name, amount, unit이 필요합니다.");
                }
            }
        }
        if (draft.steps() == null || draft.steps().isEmpty()) {
            add(codes, reasons, "STEPS_REQUIRED", "steps는 빈 배열이면 안 됩니다.");
        } else {
            for (int i = 0; i < draft.steps().size(); i++) {
                GeneratedCookingStep step = draft.steps().get(i);
                if (step == null || step.order() == null || isBlank(step.instruction())) {
                    add(codes, reasons, "STEP_REQUIRED",
                            "steps[" + i + "]에는 order와 instruction이 필요합니다.");
                } else if (step.ingredientNames() == null) {
                    add(codes, reasons, "STEP_INGREDIENT_NAMES_REQUIRED",
                            "steps[" + i + "].ingredientNames는 필수입니다. 재료를 쓰지 않는 단계면 빈 배열을 넣으세요.");
                } else if (step.ingredientNames().stream().anyMatch(this::isBlank)) {
                    add(codes, reasons, "STEP_INGREDIENT_NAME_BLANK",
                            "steps[" + i + "].ingredientNames에는 빈 값을 넣을 수 없습니다.");
                }
            }
        }
    }

    private void validateIngredientUnits(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        if (draft.ingredients() == null) {
            return;
        }
        for (int i = 0; i < draft.ingredients().size(); i++) {
            GeneratedIngredient ingredient = draft.ingredients().get(i);
            if (ingredient == null || isBlank(ingredient.name())) {
                continue;
            }
            if (ingredient.hasUnknownUnit()) {
                add(codes, reasons, "INGREDIENT_UNIT_UNKNOWN",
                        "알 수 없는 단위입니다: " + ingredient.unit());
                continue;
            }
            String normalizedUnit = ingredient.normalizedUnit();
            if (isBlank(normalizedUnit)) {
                continue;
            }
            if (!ALLOWED_UNITS.contains(normalizedUnit)) {
                add(codes, reasons, "INGREDIENT_UNIT_NOT_ALLOWED",
                        "허용되지 않은 단위입니다: " + ingredient.unit());
            }
            if (!"약간".equals(normalizedUnit) && ingredient.amount() == null) {
                add(codes, reasons, "INGREDIENT_AMOUNT_REQUIRED",
                        "약간이 아닌 단위에는 amount가 필요합니다: " + ingredient.name());
            }
        }
    }

    private void validateHeatLevels(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        for (GeneratedCookingStep step : safeSteps(draft)) {
            if (step.hasUnknownHeatLevel()) {
                add(codes, reasons, "HEAT_LEVEL_INVALID",
                        "허용되지 않은 heatLevel입니다: " + step.heatLevel());
            }
        }
    }

    private void validateStepOrder(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        if (draft.steps() == null || draft.steps().isEmpty()) {
            return;
        }
        Set<Integer> seen = new LinkedHashSet<>();
        for (GeneratedCookingStep step : draft.steps()) {
            if (step == null || step.order() == null) {
                return;
            }
            seen.add(step.order());
        }
        for (int expected = 1; expected <= draft.steps().size(); expected++) {
            if (!seen.contains(expected)) {
                add(codes, reasons, "STEP_ORDER_INVALID", "조리 단계 order는 1부터 연속되어야 합니다.");
                return;
            }
        }
    }

    private void validateNoHeatRecipe(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        if (!isNoHeatRecipe(request, draft)) {
            return;
        }
        for (GeneratedCookingStep step : safeSteps(draft)) {
            String heatLevel = step.normalizedHeatLevel();
            if (!NO_HEAT_LEVELS.contains(heatLevel)) {
                add(codes, reasons, "NO_HEAT_HAS_HEAT_LEVEL",
                        "무가열 메뉴에는 heatLevel을 넣지 마세요. 문제 단계: " + step.order());
            }
            String instruction = normalize(step.instruction() + " " + step.completionCue() + " " + step.recoveryTip());
            if (containsAny(instruction, "중불", "약불", "강불", "센불", "불에", "불로", "가열", "끓", "볶", "굽", "튀")) {
                add(codes, reasons, "NO_HEAT_HAS_HEAT_INSTRUCTION",
                        "무가열 메뉴에 불 세기나 가열 지시가 들어갔습니다. 문제 단계: " + step.order());
            }
        }
    }

    private void validateStepIngredients(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        List<String> declared = declaredIngredientNames(draft);
        if (declared.isEmpty()) {
            return;
        }
        for (GeneratedCookingStep step : safeSteps(draft)) {
            if (step.ingredientNames() == null) {
                continue;
            }
            for (String candidate : step.ingredientNames()) {
                if (!ingredientAliasNormalizer.matchesAnyDeclared(candidate, declared)) {
                    add(codes, reasons, "STEP_INGREDIENT_NOT_DECLARED",
                            "steps[].ingredientNames에 있지만 ingredients[].name에는 없는 재료가 있습니다: " + candidate);
                    return;
                }
            }
        }
    }

    private void validateRequestedSubstitutions(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        if (request == null || request.substitutions() == null || request.substitutions().isEmpty()) {
            return;
        }
        for (RecipeGenerationRequest.IngredientSubstitution substitution : request.substitutions()) {
            String from = normalizeIngredient(substitution.from());
            String to = normalizeIngredient(substitution.to());
            if (!from.isBlank() && ingredientExists(substitution.from(), declaredIngredientNames(draft))) {
                add(codes, reasons, "FROM_REMAINED_IN_INGREDIENTS",
                        "대체 전 재료가 ingredients에 남아 있습니다: " + substitution.from());
            }
            if (!from.isBlank() && stepIngredientExists(substitution.from(), draft)) {
                add(codes, reasons, "FROM_REMAINED_IN_STEPS",
                        "대체 전 재료가 steps[].ingredientNames에 남아 있습니다: " + substitution.from());
            }
            if (!to.isBlank() && !ingredientExists(substitution.to(), declaredIngredientNames(draft))) {
                add(codes, reasons, "TO_MISSING_IN_INGREDIENTS",
                        "대체 재료가 ingredients에 반영되지 않았습니다: " + substitution.to());
            }
            if (!to.isBlank() && !stepIngredientExists(substitution.to(), draft)) {
                add(codes, reasons, "TO_MISSING_IN_STEPS",
                        "대체 재료가 steps[].ingredientNames에 반영되지 않았습니다: " + substitution.to());
            }
            validateSubstitutionAdjustment(substitution, draft, codes, reasons);
        }
    }

    private void validateSubstitutionAdjustment(
            RecipeGenerationRequest.IngredientSubstitution substitution,
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        RecipeAdjustment adjustment = safeAdjustments(draft).stream()
                .filter(candidate -> "SUBSTITUTION".equalsIgnoreCase(nullToBlank(candidate.type())))
                .filter(candidate -> ingredientAliasNormalizer.matchesAnyDeclared(substitution.from(), safeSingleton(candidate.fromIngredient()))
                        || normalizeIngredient(substitution.from()).equals(normalizeIngredient(candidate.fromIngredient())))
                .filter(candidate -> ingredientAliasNormalizer.matchesAnyDeclared(substitution.to(), safeSingleton(candidate.toIngredient()))
                        || normalizeIngredient(substitution.to()).equals(normalizeIngredient(candidate.toIngredient())))
                .findFirst()
                .orElse(null);
        if (adjustment == null || isBlank(adjustment.reason())) {
            add(codes, reasons, "ADJUSTMENT_REASON_MISSING",
                    "대체 이유와 조정 설명이 adjustments에 필요합니다: " + substitution.to());
        }
        if (adjustment == null || !quantityAdjustmentMeaningful(adjustment.quantityAdjustment())) {
            add(codes, reasons, "QUANTITY_ADJUSTMENT_MISSING",
                    "대체 수량 조정 설명이 adjustments에 필요합니다: " + substitution.to());
        }
    }

    private boolean quantityAdjustmentMeaningful(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank() || "substitute".equals(normalized) || "substitution".equals(normalized)) {
            return false;
        }
        return containsAny(normalized, "대신", "부터", "조절", "줄", "늘", "맛", "확인", "큰술", "작은술", "g", "ml")
                || normalized.matches(".*\\d+.*");
    }

    private void validateExcludedIngredients(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        if (request == null || request.excludedIngredients() == null || request.excludedIngredients().isEmpty()) {
            return;
        }
        for (String excluded : request.excludedIngredients()) {
            String normalized = normalizeIngredient(excluded);
            if (normalized.isBlank()) {
                continue;
            }
            if (ingredientExists(excluded, declaredIngredientNames(draft))) {
                add(codes, reasons, "EXCLUDED_INGREDIENT_REMAINED",
                        "제외 요청한 재료가 ingredients에 남아 있습니다: " + excluded);
            }
            if (stepIngredientExists(excluded, draft)) {
                add(codes, reasons, "EXCLUDED_INGREDIENT_REMAINED",
                        "제외 요청한 재료가 steps[].ingredientNames에 남아 있습니다: " + excluded);
            }
            for (GeneratedCookingStep step : safeSteps(draft)) {
                if (instructionUsesExcludedIngredient(step.instruction(), excluded)) {
                    add(codes, reasons, "EXCLUDED_INGREDIENT_REMAINED",
                            "제외 요청한 재료가 조리 지시에서 사용되고 있습니다: " + excluded);
                    break;
                }
            }
            if (adjustmentUsesExcludedIngredient(draft, excluded)) {
                add(codes, reasons, "EXCLUDED_INGREDIENT_REMAINED",
                        "제외 요청한 재료가 adjustments의 사용 대상으로 남아 있습니다: " + excluded);
            }
        }
    }

    private void validateCookingTime(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        if (draft.cookingTimeMinutes() == null || draft.cookingTimeMinutes() <= 0 || draft.steps() == null) {
            return;
        }
        int stepMinuteSum = draft.steps().stream()
                .filter(step -> step != null && step.minutes() != null && step.minutes() > 0)
                .mapToInt(GeneratedCookingStep::minutes)
                .sum();
        if (stepMinuteSum > Math.round(draft.cookingTimeMinutes() * 1.4)) {
            add(codes, reasons, "TIME_CONFLICT",
                    "상단 조리 시간보다 단계별 시간 합계가 지나치게 큽니다. 상단: "
                            + draft.cookingTimeMinutes() + "분 / 단계 합계: " + stepMinuteSum + "분");
        }
    }

    private boolean violatesDietaryRestriction(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft draft,
            List<String> reasons) {
        if (request == null || request.safetyConditions() == null
                || request.safetyConditions().dietaryRestrictions() == null) {
            return false;
        }
        String restrictions = normalize(String.join(" ", request.safetyConditions().dietaryRestrictions()));
        if (!(restrictions.contains("비건") || restrictions.contains("채식") || restrictions.contains("육류제외"))) {
            return false;
        }
        String recipeText = recipeText(draft);
        if (containsAny(recipeText, "돼지고기", "소고기", "쇠고기", "닭고기", "생선", "오징어", "새우")) {
            reasons.add("명시적인 채식/육류 제한 조건과 충돌하는 재료가 있습니다.");
            return true;
        }
        return false;
    }

    private boolean isNoHeatRecipe(RecipeGenerationRequest request, GeneratedRecipeDraft draft) {
        String text = normalize((request != null ? request.requestedTitle() : "") + " " + draft.title());
        return containsAny(text, "화채", "스무디", "요거트", "샐러드", "빙수", "주스", "에이드", "파르페", "음료");
    }

    private List<String> declaredIngredientNames(GeneratedRecipeDraft draft) {
        List<String> names = new ArrayList<>();
        if (draft.ingredients() == null) {
            return names;
        }
        for (GeneratedIngredient ingredient : draft.ingredients()) {
            if (ingredient == null || isBlank(ingredient.name())) {
                continue;
            }
            names.add(ingredient.name());
        }
        return names;
    }

    private boolean ingredientExists(String ingredient, List<String> declaredIngredients) {
        return ingredientAliasNormalizer.matchesAnyDeclared(ingredient, declaredIngredients);
    }

    private boolean stepIngredientExists(String ingredient, GeneratedRecipeDraft draft) {
        for (GeneratedCookingStep step : safeSteps(draft)) {
            if (step.ingredientNames() == null) {
                continue;
            }
            if (ingredientAliasNormalizer.matchesAnyDeclared(ingredient, step.ingredientNames())) {
                return true;
            }
        }
        return false;
    }

    private List<GeneratedCookingStep> safeSteps(GeneratedRecipeDraft draft) {
        return draft.steps() == null ? List.of() : draft.steps();
    }

    private List<RecipeAdjustment> safeAdjustments(GeneratedRecipeDraft draft) {
        return draft.adjustments() == null ? List.of() : draft.adjustments();
    }

    private boolean instructionUsesExcludedIngredient(String instruction, String excluded) {
        String compactInstruction = normalize(instruction);
        String compactExcluded = normalize(excluded);
        if (compactInstruction.isBlank() || compactExcluded.isBlank() || !compactInstruction.contains(compactExcluded)) {
            return false;
        }
        if (containsAny(compactInstruction,
                compactExcluded + "없이",
                compactExcluded + "는사용하지",
                compactExcluded + "은사용하지",
                compactExcluded + "를사용하지",
                compactExcluded + "을사용하지",
                compactExcluded + "제외",
                compactExcluded + "빼고")) {
            return false;
        }
        return containsAny(compactInstruction,
                compactExcluded + "를넣",
                compactExcluded + "을넣",
                compactExcluded + "넣",
                compactExcluded + "볶",
                compactExcluded + "썰",
                compactExcluded + "준비",
                compactExcluded + "사용",
                compactExcluded + "익");
    }

    private boolean adjustmentUsesExcludedIngredient(GeneratedRecipeDraft draft, String excluded) {
        return safeAdjustments(draft).stream()
                .map(RecipeAdjustment::toIngredient)
                .anyMatch(value -> ingredientAliasNormalizer.matchesAnyDeclared(excluded, safeSingleton(value))
                        || normalizeIngredient(excluded).equals(normalizeIngredient(value)));
    }

    private List<String> safeSingleton(String value) {
        return value == null ? List.of() : List.of(value);
    }

    private String recipeText(GeneratedRecipeDraft draft) {
        StringBuilder builder = new StringBuilder();
        if (draft.ingredients() != null) {
            draft.ingredients().forEach(ingredient -> builder.append(" ")
                    .append(ingredient == null ? "" : ingredient.name())
                    .append(" ")
                    .append(ingredient == null ? "" : ingredient.quantity()));
        }
        if (draft.steps() != null) {
            draft.steps().forEach(step -> builder.append(" ")
                    .append(step == null ? "" : step.instruction())
                    .append(" ")
                    .append(step == null || step.ingredientNames() == null ? "" : String.join(" ", step.ingredientNames()))
                    .append(" ")
                    .append(step == null ? "" : step.completionCue())
                    .append(" ")
                    .append(step == null ? "" : step.recoveryTip()));
        }
        return normalizeIngredient(builder.toString());
    }

    private void add(List<String> codes, List<String> reasons, String code, String reason) {
        codes.add(code);
        reasons.add(reason);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean containsAny(String value, String... keywords) {
        String normalized = normalize(value);
        for (String keyword : keywords) {
            if (normalized.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeIngredient(String value) {
        return value == null ? "" : value.replaceAll("[^가-힣a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    public record ValidationResult(
            boolean valid,
            boolean retryable,
            boolean blocking,
            List<String> codes,
            List<String> reasons
    ) {
        private static ValidationResult ok() {
            return new ValidationResult(true, false, false, List.of(), List.of());
        }

        private static ValidationResult retryable(List<String> codes, List<String> reasons) {
            return new ValidationResult(false, true, false, List.copyOf(codes), List.copyOf(reasons));
        }

        private static ValidationResult blocking(List<String> codes, List<String> reasons) {
            return new ValidationResult(false, false, true, List.copyOf(codes), List.copyOf(reasons));
        }
    }
}
