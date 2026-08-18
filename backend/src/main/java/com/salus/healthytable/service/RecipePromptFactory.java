package com.salus.healthytable.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.Recipe;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RecipePromptFactory {

    private final ObjectMapper objectMapper;
    private static final List<String> ALLOWED_UNITS = List.of(
            "g", "kg", "ml", "L", "개", "장", "대", "모", "컵", "큰술", "작은술", "약간");
    private static final List<String> ALLOWED_HEAT_LEVELS = List.of(
            "강불", "중강불", "중불", "중약불", "약불", "무가열", "해당 없음");

    public String buildGenerationPrompt(RecipeGenerationRequest request) {
        return """
                역할: Salus 구조화 레시피 생성 엔진

                사용자 요청:
                %s

                요리명 또는 목표:
                %s

                검증된 내부 레시피:
                %s

                외부 근거(최대 3개):
                %s

                냉장고 재료:
                %s

                건강 및 안전 제한:
                %s

                대체·제외·상세 조건:
                %s

                핵심 생성 규칙:
                - 요청한 요리 정체성과 검증 근거의 핵심 재료를 유지하세요.
                - 근거 없는 핵심 재료를 추가하지 말고, 필요한 기본 양념은 소량만 쓰세요.
                - ingredients의 단위는 g, kg, ml, L, 개, 장, 대, 모, 컵, 큰술, 작은술, 약간 중 하나만 쓰세요.
                - steps[].ingredientNames는 ingredients[].name에 존재하는 실제 재료명만 쓰세요.
                - 대체 요청은 ingredients, steps[].ingredientNames, adjustments에 모두 반영하세요.
                - 제외 재료는 ingredients, steps[].ingredientNames, 실제 사용 지시에서 제거하세요.
                - 각 단계에 시간, 완료 상태, 복구 팁을 가능한 한 구체적으로 쓰세요.
                - 무가열 메뉴의 heatLevel은 무가열 또는 해당 없음만 사용하세요.

                출력 형식:
                API format 필드로 전달된 JSON Schema를 엄격히 따르세요.
                """.formatted(
                nullToBlank(request.userMessage()),
                nullToBlank(request.requestedTitle()),
                formatTrustedRecipes(request.trustedRecipes()),
                summarizeSearchContext(request.searchContext()),
                formatList(request.fridgeItems()),
                formatSafetyConditions(request.safetyConditions()),
                formatPreviousRecipeContext(request));
    }

    public String buildRepairPrompt(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft invalidDraft,
            List<String> validationReasons) {
        return """
                JSON 객체 하나만 다시 출력하세요. 마크다운 코드 블록, 인사말, 설명 문장은 절대 출력하지 마세요.

                [최초 요청]
                %s

                [요청한 요리명]
                %s

                [최초 근거 요약]
                %s

                [생성된 JSON]
                %s

                [검증 실패 코드와 이유]
                %s

                [허용된 재료 목록]
                %s

                [금지된 재료 목록]
                %s

                [반드시 유지할 제목과 핵심 재료]
                - 제목: %s
                - 현재 핵심 재료: %s

                [수정 범위]
                - 원본 GeneratedRecipeDraft의 정상 필드는 최대한 유지하세요.
                - 실패한 필드만 최소 범위로 수정하세요.
                - ingredients에 없는 재료를 steps[].ingredientNames에 쓰지 마세요.
                - 대체/제외 요청은 ingredients, steps[].ingredientNames, adjustments에 정확히 반영하세요.
                - heatLevel과 unit은 Schema enum 값만 사용하세요.
                - order는 1부터 연속되게 고치세요.

                [출력 형식]
                API format 필드로 전달된 JSON Schema를 엄격히 따르세요.
                """.formatted(
                nullToBlank(request.userMessage()),
                nullToBlank(request.requestedTitle()),
                summarizeSearchContext(request.searchContext()),
                toJson(invalidDraft),
                formatList(validationReasons),
                formatIngredientNames(invalidDraft),
                formatForbiddenIngredients(request),
                nullToBlank(request.requestedTitle()),
                formatIngredientNames(invalidDraft));
    }

    public Map<String, Object> jsonSchema() {
        Map<String, Object> ingredient = new LinkedHashMap<>();
        ingredient.put("type", "object");
        ingredient.put("additionalProperties", false);
        ingredient.put("required", List.of("name", "amount", "unit"));
        ingredient.put("properties", Map.of(
                "name", Map.of("type", "string"),
                "amount", Map.of("type", List.of("number", "null")),
                "unit", Map.of("type", "string", "enum", ALLOWED_UNITS),
                "preparation", Map.of("type", List.of("string", "null"))));

        Map<String, Object> stepProperties = new LinkedHashMap<>();
        stepProperties.put("order", Map.of("type", "integer"));
        stepProperties.put("instruction", Map.of("type", "string"));
        stepProperties.put("heatLevel", Map.of("type", List.of("string", "null"), "enum", heatLevelEnumWithNull()));
        stepProperties.put("minutes", Map.of("type", List.of("integer", "null")));
        stepProperties.put("completionCue", Map.of("type", List.of("string", "null")));
        stepProperties.put("recoveryTip", Map.of("type", List.of("string", "null")));
        stepProperties.put("ingredientNames", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", "object");
        step.put("additionalProperties", false);
        step.put("required", List.of("order", "instruction", "ingredientNames"));
        step.put("properties", stepProperties);

        Map<String, Object> adjustment = new LinkedHashMap<>();
        adjustment.put("type", "object");
        adjustment.put("additionalProperties", false);
        adjustment.put("required", List.of("type", "fromIngredient", "toIngredient", "reason", "quantityAdjustment"));
        adjustment.put("properties", Map.of(
                "type", Map.of("type", "string", "enum", List.of("SUBSTITUTION", "EXCLUSION", "DETAIL")),
                "fromIngredient", Map.of("type", List.of("string", "null")),
                "toIngredient", Map.of("type", List.of("string", "null")),
                "reason", Map.of("type", List.of("string", "null")),
                "quantityAdjustment", Map.of("type", List.of("string", "null"))));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", Map.of("type", "string"));
        properties.put("description", Map.of("type", "string"));
        properties.put("servings", Map.of("type", List.of("integer", "null")));
        properties.put("cookingTimeMinutes", Map.of("type", List.of("integer", "null")));
        properties.put("caloriesKcal", Map.of("type", List.of("integer", "null")));
        properties.put("difficulty", Map.of("type", "integer", "minimum", 1, "maximum", 3));
        properties.put("ingredients", Map.of("type", "array", "minItems", 1, "items", ingredient));
        properties.put("steps", Map.of("type", "array", "minItems", 1, "items", step));
        properties.put("adjustments", Map.of("type", "array", "items", adjustment));
        properties.put("safetyNotes", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of("title", "ingredients", "steps", "difficulty"));
        schema.put("properties", properties);
        return schema;
    }

    private List<Object> heatLevelEnumWithNull() {
        List<Object> values = new java.util.ArrayList<>();
        values.addAll(ALLOWED_HEAT_LEVELS);
        values.add(null);
        return values;
    }

    private String formatTrustedRecipes(List<Recipe> recipes) {
        if (recipes == null || recipes.isEmpty()) {
            return "없음";
        }
        return recipes.stream()
                .map(recipe -> "- " + nullToBlank(recipe.getTitle())
                        + "\n  설명: " + nullToBlank(recipe.getDescription())
                        + "\n  재료: " + formatList(recipe.getIngredients())
                        + "\n  조리 순서: " + formatList(recipe.getSteps()))
                .collect(Collectors.joining("\n"));
    }

    private String formatSafetyConditions(RecipeGenerationRequest.SafetyConditions safety) {
        if (safety == null) {
            return "없음";
        }
        return """
                알레르기: %s
                만성질환: %s
                식단 제한: %s
                복용 약물: %s
                건강 목표: %s
                """.formatted(
                formatList(safety.allergies()),
                formatList(safety.chronicConditions()),
                formatList(safety.dietaryRestrictions()),
                formatList(safety.medications()),
                formatList(safety.goals())).trim();
    }

    private String formatPreviousRecipeContext(RecipeGenerationRequest request) {
        boolean hasPrevious = request.previousRecipeText() != null && !request.previousRecipeText().isBlank();
        boolean hasModifiers = request.modifiers() != null && !request.modifiers().isEmpty();
        boolean hasExcluded = request.excludedIngredients() != null && !request.excludedIngredients().isEmpty();
        boolean hasSubstitutions = request.substitutions() != null && !request.substitutions().isEmpty();
        if (!hasPrevious && !hasModifiers && !hasExcluded && !hasSubstitutions) {
            return "없음";
        }
        return """
                모드: %s
                직전 레시피: %s
                수정 요청: %s
                제외 재료: %s
                대체 재료: %s
                """.formatted(
                request.mode(),
                nullToBlank(request.previousRecipeText()),
                formatList(request.modifiers()),
                formatList(request.excludedIngredients()),
                formatSubstitutions(request.substitutions())).trim();
    }

    private String formatSubstitutions(List<RecipeGenerationRequest.IngredientSubstitution> substitutions) {
        if (substitutions == null || substitutions.isEmpty()) {
            return "없음";
        }
        return substitutions.stream()
                .map(substitution -> nullToBlank(substitution.from()) + " -> " + nullToBlank(substitution.to()))
                .collect(Collectors.joining(", "));
    }

    private String summarizeSearchContext(String searchContext) {
        if (searchContext == null || searchContext.isBlank()) {
            return "없음";
        }
        return List.of(searchContext.split("\\R+")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(3)
                .collect(Collectors.joining("\n"));
    }

    private String formatIngredientNames(GeneratedRecipeDraft draft) {
        if (draft == null || draft.ingredients() == null || draft.ingredients().isEmpty()) {
            return "없음";
        }
        return draft.ingredients().stream()
                .filter(ingredient -> ingredient != null && ingredient.name() != null && !ingredient.name().isBlank())
                .map(GeneratedIngredient::name)
                .collect(Collectors.joining(", "));
    }

    private String formatForbiddenIngredients(RecipeGenerationRequest request) {
        if (request == null) {
            return "없음";
        }
        java.util.LinkedHashSet<String> forbidden = new java.util.LinkedHashSet<>();
        if (request.excludedIngredients() != null) {
            forbidden.addAll(request.excludedIngredients());
        }
        if (request.substitutions() != null) {
            request.substitutions().stream()
                    .map(RecipeGenerationRequest.IngredientSubstitution::from)
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(forbidden::add);
        }
        if (request.safetyConditions() != null && request.safetyConditions().allergies() != null) {
            forbidden.addAll(request.safetyConditions().allergies());
        }
        return forbidden.isEmpty() ? "없음" : String.join(", ", forbidden);
    }

    private String formatList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "없음";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(", "));
    }

    private String schemaJson() {
        return toJson(jsonSchema());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RecipeGenerationException("레시피 JSON Schema 직렬화에 실패했습니다.", e);
        }
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
