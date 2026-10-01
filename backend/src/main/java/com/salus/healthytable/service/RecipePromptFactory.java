package com.salus.healthytable.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.Recipe;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 구조화 레시피 생성에 쓰는 LLM 프롬프트와 JSON Schema를 만드는 클래스입니다.
 *
 * - buildGenerationPrompt: 새 레시피 생성용 프롬프트 (요청, 근거, 냉장고 재료, 건강 제한, 대체/제외 조건 포함)
 * - buildRepairPrompt: 검증에 실패한 JSON을 최소 범위로 고치게 하는 복구 프롬프트
 * - jsonSchema: Ollama format 옵션으로 넘겨 출력 JSON 구조를 강제하는 스키마
 * 프롬프트 문장을 바꾸면 LLM 출력 품질이 크게 달라질 수 있으므로, 변경 시 평가(eval) 결과를 함께 확인해야 합니다.
 */
@Component
@RequiredArgsConstructor
public class RecipePromptFactory {

    private final ObjectMapper objectMapper;
    // 스키마 enum으로 강제하는 재료 단위와 불 세기 값 (GeneratedIngredient/GeneratedCookingStep의 표준 표기와 일치)
    private static final List<String> ALLOWED_UNITS = List.of(
            "g", "kg", "ml", "L", "개", "장", "대", "모", "컵", "큰술", "작은술", "약간");
    private static final List<String> ALLOWED_HEAT_LEVELS = List.of(
            "강불", "중강불", "중불", "중약불", "약불", "무가열", "해당 없음");

    /**
     * 새 레시피 생성 프롬프트를 만듭니다.
     * 텍스트 블록의 %s 자리에 formatted(...) 인자가 순서대로 들어갑니다. 검색 근거는 최대 5,000자로 줄여 넣습니다.
     */
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
                - servings는 전체 재료로 완성되는 인분 수이고, caloriesKcal은 반드시 1인분 기준 열량으로 쓰세요.
                - 현재 영양 DB 검증은 후순위이므로 caloriesKcal은 추측하지 말고 null로 쓰세요.
                - steps[].ingredientNames는 ingredients[].name에 존재하는 실제 재료명만 쓰세요.
                - 모든 ingredients 항목은 실제 사용하는 steps[].ingredientNames에 최소 한 번 포함하세요.
                - 대체 요청은 ingredients, steps[].ingredientNames, adjustments에 모두 반영하세요.
                - 제외 재료는 ingredients, steps[].ingredientNames, 실제 사용 지시에서 제거하세요.
                - 팬·냄비 조리 단계는 heatLevel, minutes, completionCue를 반드시 쓰고 temperatureC는 null로 두세요.
                - instruction에 끓·볶·굽·삶·데치·튀·졸·찌·익히 중 하나라도 쓰면 가열 단계로 판정됩니다. 그 단계에는 무가열이 아닌 heatLevel과 1분 이상의 minutes, completionCue가 모두 필요합니다. 가열하지 않는 단계라면 이 표현들을 쓰지 마세요.
                - 재료 손질·혼합·무가열 단계는 temperatureC를 반드시 null로 두세요.
                - 오븐·에어프라이어 단계는 temperatureC 필드에 40~300 사이 숫자를 반드시 채우세요. 온도를 instruction 문장에만 적고 필드를 비우면 실패합니다. minutes와 completionCue도 함께 쓰세요.
                - 생고기·가금류·달걀·생선·해산물이 들어가면 completionCue에 다음 표현 중 하나를 그대로 쓰세요: 중심까지, 속까지, 완전히 익, 충분히 익, 핏물이 없, 분홍색이 없, 불투명, 살이 하얗. '익고', '잘 익으면'처럼 모호한 표현은 안 됩니다.
                - completionCue는 색·질감·농도·중심 익힘처럼 사용자가 직접 관찰 가능한 상태로 쓰세요.
                - recoveryTip은 실제로 되돌리거나 완화할 수 있을 때만 쓰고, 복구할 수 없으면 null로 두세요.
                - '너무 부드럽다/탔다/말랐다' 같은 과조리 상태에 더 오래 가열하거나 불을 높이라고 지시하지 마세요.
                - 일반 채소나 두부에 육류식 중심 온도·완전 가열 안전문구를 붙이지 마세요.
                - 무가열 메뉴의 heatLevel은 무가열 또는 해당 없음만 사용하세요.
                - 근거에서 확인할 수 없는 재료, 수량, 조리법은 추측하지 마세요. 근거가 부족하면 핵심 재료를 임의로 추가하지 마세요.
                - 조리 단계는 중복 없이 최대 6개로 합치고, instruction·completionCue·recoveryTip은 각각 한 문장으로 간결하게 쓰세요.
                - 선택 필드 preparation, recoveryTip은 실제 값이 있을 때만 출력하고 null이면 생략하세요.
                - 신규 기본 레시피에서 adjustments와 safetyNotes가 비어 있으면 해당 필드를 생략하세요.
                - JSON은 의미 없는 공백과 줄바꿈 없이 간결하게 출력하세요.

                출력 형식:
                API format 필드로 전달된 JSON Schema를 엄격히 따르세요.
                """.formatted(
                nullToBlank(request.userMessage()),
                nullToBlank(request.requestedTitle()),
                formatTrustedRecipes(request.trustedRecipes()),
                summarizeSearchContext(request.searchContext(), 5_000),
                formatList(request.fridgeItems()),
                formatSafetyConditions(request.safetyConditions()),
                formatPreviousRecipeContext(request));
    }

    /**
     * 복구 프롬프트를 만듭니다.
     * 실패 이유와 원래 JSON을 함께 보여 주고, "허용된 재료"와 "금지된 재료(제외/대체 원재료/알레르기)"를 명시해
     * 실패한 필드만 고치도록 지시합니다. 프롬프트 길이를 줄이기 위해 근거는 1,800자까지만 넣습니다.
     */
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
                - 오븐·에어프라이어 단계는 temperatureC 필드에 40~300 사이 숫자를 채우고, 그 외 단계의 temperatureC는 null로 고치세요. 온도를 instruction 문장에만 적고 필드를 비우면 실패합니다.
                - 모든 가열 단계의 minutes와 관찰 가능한 completionCue를 채우세요. instruction에 끓·볶·굽·삶·데치·튀·졸·찌·익히가 있으면 가열 단계입니다.
                - 생고기·가금류·달걀·생선·해산물이 있으면 completionCue에 다음 표현 중 하나를 그대로 쓰세요: 중심까지, 속까지, 완전히 익, 충분히 익, 핏물이 없, 분홍색이 없, 불투명, 살이 하얗.
                - recoveryTip은 실제 복구가 가능할 때만 쓰고, 과조리 상태에 추가 가열을 지시한 값은 올바르게 고치거나 null로 두세요.
                - 일반 채소나 두부에 육류식 중심 익힘 안전문구를 붙이지 마세요.
                - order는 1부터 연속되게 고치세요.
                - 조리 단계는 중복 없이 최대 6개로 유지하고 각 문자열 필드의 핵심만 한 문장으로 쓰세요.

                [출력 형식]
                API format 필드로 전달된 JSON Schema를 엄격히 따르세요.
                """.formatted(
                nullToBlank(request.userMessage()),
                nullToBlank(request.requestedTitle()),
                summarizeSearchContext(request.searchContext(), 1_800),
                toJson(invalidDraft),
                formatList(validationReasons),
                formatIngredientNames(invalidDraft),
                formatForbiddenIngredients(request),
                nullToBlank(request.requestedTitle()),
                formatIngredientNames(invalidDraft));
    }

    /**
     * LLM 출력 JSON의 구조를 정의한 JSON Schema를 Map으로 만듭니다.
     * additionalProperties=false로 정의하지 않은 필드를 막고, 길이/개수/값 범위로 비정상 출력을 줄입니다.
     * (스키마를 지켰더라도 내용의 정확성은 이후 검증기에서 따로 확인합니다.)
     */
    /**
     * 요청에 맞춘 JSON Schema를 만듭니다.
     *
     * <p>대체·제외 요청은 무엇을 왜 바꿨는지가 결과의 일부입니다. 그런데 프롬프트로만
     * "adjustments에 반영하세요"라고 하면 모델이 빈 배열을 냅니다. 실측에서 대체는 정확히
     * 했는데(돼지고기 제거, 두부 추가) adjustments가 비어 ADJUSTMENT_REASON_MISSING으로
     * 실패했습니다. 프롬프트 규칙을 늘리는 대신 스키마에서 구조적으로 강제합니다.
     */
    public Map<String, Object> jsonSchema(RecipeGenerationRequest request) {
        Map<String, Object> schema = jsonSchema();
        if (request == null) {
            return schema;
        }
        if (requiresOvenTemperature(request)) {
            schema = withOvenTemperatureConstraint(schema);
        }
        if (!requiresAdjustmentRecord(request)) {
            return schema;
        }
        Map<String, Object> adjusted = new LinkedHashMap<>(schema);
        List<?> baseRequired = (List<?>) schema.get("required");
        List<Object> required = new java.util.ArrayList<>(baseRequired);
        if (!required.contains("adjustments")) {
            required.add("adjustments");
        }
        adjusted.put("required", List.copyOf(required));

        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
        Map<String, Object> adjustmentsProperty = new LinkedHashMap<>((Map<String, Object>) properties.get("adjustments"));
        adjustmentsProperty.put("minItems", 1);
        strictAdjustmentItem(request).ifPresent(item -> adjustmentsProperty.put("items", item));
        properties.put("adjustments", frozen(adjustmentsProperty));
        adjusted.put("properties", frozen(properties));
        return frozen(adjusted);
    }

    /**
     * 대체 요청이 하나일 때 adjustments 항목의 방향과 분량을 스키마로 고정합니다.
     *
     * <p>minItems만 걸면 모델이 배열을 채우긴 하지만 내용이 틀립니다. 실측에서 돼지고기→두부
     * 요청에 fromIngredient=두부, toIngredient=돼지고기로 방향을 뒤집었고 quantityAdjustment는
     * null이었습니다. 검증기는 요청의 from과 일치하는 항목을 찾으므로 둘 다 실패합니다.
     *
     * <p>{@code required}는 키의 존재만 보장하고 null을 막지 않으므로 타입에서 null을 뺍니다.
     * 대체가 여러 건이면 항목별로 값을 고정할 수 없어 적용하지 않습니다.
     */
    private java.util.Optional<Map<String, Object>> strictAdjustmentItem(RecipeGenerationRequest request) {
        if (request.mode() != RecipeGenerationRequest.Mode.SUBSTITUTE
                || request.substitutions() == null || request.substitutions().size() != 1) {
            return java.util.Optional.empty();
        }
        RecipeGenerationRequest.IngredientSubstitution substitution = request.substitutions().get(0);
        if (substitution == null || substitution.from() == null || substitution.to() == null) {
            return java.util.Optional.empty();
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("additionalProperties", false);
        item.put("required", List.of("type", "fromIngredient", "toIngredient", "reason", "quantityAdjustment"));
        item.put("properties", ordered(
                "type", Map.of("type", "string", "const", "SUBSTITUTION"),
                "fromIngredient", Map.of("type", "string", "const", substitution.from()),
                "toIngredient", Map.of("type", "string", "const", substitution.to()),
                "reason", Map.of("type", "string", "minLength", 2, "maxLength", 160),
                "quantityAdjustment", Map.of("type", "string", "minLength", 2, "maxLength", 160)));
        return java.util.Optional.of(frozen(item));
    }

    /**
     * 오븐·에어프라이어 요리면 최소 한 단계에 temperatureC를 요구합니다.
     *
     * <p>어느 단계가 가열인지는 생성 결과에 달려 있어 특정 단계에 값을 박을 수 없습니다.
     * 대신 JSON Schema의 {@code contains}로 "온도를 가진 단계가 하나는 있어야 한다"를 겁니다.
     *
     * <p>프롬프트로 세 번 안내했는데도 모델이 온도를 instruction 문장에만 적고 필드를 비웠습니다
     * ("에어프라이어를 190도에서 20분간 구워줍니다" / temperatureC=null).
     */
    private Map<String, Object> withOvenTemperatureConstraint(Map<String, Object> schema) {
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
        Map<String, Object> steps = new LinkedHashMap<>((Map<String, Object>) properties.get("steps"));
        steps.put("contains", ordered(
                "type", "object",
                "required", List.of("temperatureC"),
                "properties", Map.of(
                        "temperatureC", Map.of("type", "integer", "minimum", 40, "maximum", 300))));
        steps.put("minContains", 1);
        properties.put("steps", frozen(steps));
        Map<String, Object> adjusted = new LinkedHashMap<>(schema);
        adjusted.put("properties", frozen(properties));
        return frozen(adjusted);
    }

    /**
     * 선언한 순서를 유지하는 불변 Map을 만듭니다. 키와 값을 번갈아 넘깁니다.
     *
     * <p>Ollama는 스키마 properties에 적힌 순서대로 필드를 생성합니다. {@code Map.of}와
     * {@code Map.copyOf}는 반복 순서가 JVM을 띄울 때마다 무작위로 바뀌어, 같은 코드인데도
     * 실행마다 필드 생성 순서가 달라졌습니다. 평가 10회에서 결과가 갈린 케이스는 모두 이 순서로
     * 설명됐습니다(우유 감자스프: name→unit→amount 순서에서만 3/3 누출).
     * properties와 그 상위 Map은 반드시 이 메서드나 {@link #frozen}으로 만드세요.
     */
    private static Map<String, Object> ordered(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("키와 값은 짝수 개여야 합니다.");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    // 순서를 유지한 채 불변으로 감쌉니다. Map.copyOf는 순서를 잃으므로 쓰지 않습니다.
    private static Map<String, Object> frozen(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    // 요청 문구에 오븐·에어프라이어가 있으면 온도 단계가 있어야 하는 요리로 봅니다.
    private boolean requiresOvenTemperature(RecipeGenerationRequest request) {
        String text = (nullToBlank(request.requestedTitle()) + " " + nullToBlank(request.userMessage()))
                .toLowerCase(java.util.Locale.ROOT);
        return text.contains("오븐") || text.contains("에어프라이어") || text.contains("에어 프라이어")
                || text.contains("베이크");
    }

    // 대체·제외 요청이 실제로 있을 때만 adjustments를 강제합니다.
    private boolean requiresAdjustmentRecord(RecipeGenerationRequest request) {
        boolean substituteMode = request.mode() == RecipeGenerationRequest.Mode.SUBSTITUTE
                && request.substitutions() != null && !request.substitutions().isEmpty();
        boolean excludeMode = request.mode() == RecipeGenerationRequest.Mode.EXCLUDE
                && request.excludedIngredients() != null && !request.excludedIngredients().isEmpty();
        return substituteMode || excludeMode;
    }

    public Map<String, Object> jsonSchema() {
        // 재료 한 개: name(필수), amount(숫자 또는 null), unit(허용 단위), preparation(선택)
        Map<String, Object> ingredient = new LinkedHashMap<>();
        ingredient.put("type", "object");
        ingredient.put("additionalProperties", false);
        ingredient.put("required", List.of("name", "amount", "unit"));
        // 생성 순서는 unit → name → amount입니다. 순서를 고정한 뒤 6가지 순열을 비교한 결과
        // (2026-09-30, live 12케이스) unit 먼저가 10/12로 가장 좋았고, name이 맨 앞인 두 순서에서만
        // 우유 감자스프 알레르겐 누출이 났습니다(범위 6~10/12). 바꾸려면 평가를 다시 돌리세요.
        ingredient.put("properties", ordered(
                "unit", Map.of("type", "string", "enum", ALLOWED_UNITS),
                "name", Map.of("type", "string", "maxLength", 40),
                "amount", Map.of("type", List.of("number", "null")),
                "preparation", Map.of("type", List.of("string", "null"), "maxLength", 80)));

        // 조리 단계 한 개: 순서, 설명, 불 세기, 온도(40~300℃), 시간, 완료 기준, 복구 팁, 사용 재료
        Map<String, Object> stepProperties = new LinkedHashMap<>();
        stepProperties.put("order", Map.of("type", "integer"));
        // 길이 상한을 100/70/80자로 조여 보았으나 역효과였습니다(9/12 -> 6/12). 완료 기준을 짧게
        // 쓰게 되면서 TIME_CONFLICT와 COMPLETION_CUE_REQUIRED가 늘었습니다. "중심까지 완전히
        // 익어 핏물이 없는 상태" 같은 표현에는 여유가 필요합니다. 원래 값을 유지합니다.
        stepProperties.put("instruction", Map.of("type", "string", "maxLength", 180));
        // heatLevel을 instruction 앞으로 옮기면 불 세기 누락은 사라지지만 TIME_CONFLICT가 늘어
        // 전체로는 나빠졌습니다(2026-10-01, 폴백 없음 7 -> 6/12, 폴백 포함 10 -> 9/12).
        stepProperties.put("heatLevel", Map.of("type", List.of("string", "null"), "enum", heatLevelEnumWithNull()));
        stepProperties.put("temperatureC", Map.of("type", List.of("integer", "null"), "minimum", 40, "maximum", 300));
        stepProperties.put("minutes", Map.of("type", List.of("integer", "null")));
        stepProperties.put("completionCue", Map.of(
                "type", List.of("string", "null"), "maxLength", 120));
        stepProperties.put("recoveryTip", Map.of(
                "type", List.of("string", "null"), "maxLength", 120));
        stepProperties.put("ingredientNames", Map.of(
                "type", "array", "maxItems", 12,
                "items", Map.of("type", "string", "maxLength", 40)));

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", "object");
        step.put("additionalProperties", false);
        step.put("required", List.of(
                "order", "instruction", "heatLevel", "minutes", "completionCue", "ingredientNames"));
        step.put("properties", stepProperties);

        // 변경 내역 한 건: 대체(SUBSTITUTION) / 제외(EXCLUSION) / 상세화(DETAIL)
        Map<String, Object> adjustment = new LinkedHashMap<>();
        adjustment.put("type", "object");
        adjustment.put("additionalProperties", false);
        adjustment.put("required", List.of("type", "fromIngredient", "toIngredient", "reason", "quantityAdjustment"));
        adjustment.put("properties", ordered(
                "type", Map.of("type", "string", "enum", List.of("SUBSTITUTION", "EXCLUSION", "DETAIL")),
                "fromIngredient", Map.of("type", List.of("string", "null")),
                "toIngredient", Map.of("type", List.of("string", "null")),
                "reason", Map.of("type", List.of("string", "null")),
                "quantityAdjustment", Map.of("type", List.of("string", "null"))));

        // 레시피 최상위 필드: 인분 1~50, 조리 시간 1~1440분, 난이도 1~3, 재료 최대 20개, 단계 최대 6개
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", Map.of("type", "string", "maxLength", 40));
        properties.put("description", Map.of("type", "string", "maxLength", 160));
        properties.put("servings", Map.of("type", "integer", "minimum", 1, "maximum", 50));
        // cookingTimeMinutes를 steps 뒤로 옮겨 보았으나 역효과였습니다(2026-09-30, 폴백 없음 7 -> 5/12,
        // 폴백 포함 10 -> 8/12). 순서 변경은 다른 케이스에도 영향을 주므로 평가 없이 바꾸지 마세요.
        properties.put("cookingTimeMinutes", Map.of("type", "integer", "minimum", 1, "maximum", 1440));
        properties.put("caloriesKcal", Map.of("type", List.of("integer", "null")));
        properties.put("difficulty", Map.of("type", "integer", "minimum", 1, "maximum", 3));
        properties.put("ingredients", Map.of(
                "type", "array", "minItems", 1, "maxItems", 20, "items", ingredient));
        properties.put("steps", Map.of(
                "type", "array", "minItems", 1, "maxItems", 6, "items", step));
        properties.put("adjustments", Map.of(
                "type", "array", "maxItems", 10, "items", adjustment));
        properties.put("safetyNotes", Map.of(
                "type", "array", "maxItems", 5,
                "items", Map.of("type", "string", "maxLength", 180)));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of(
                "title", "description", "servings", "cookingTimeMinutes", "caloriesKcal",
                "difficulty", "ingredients", "steps"));
        schema.put("properties", properties);
        return schema;
    }

    // 불 세기 enum에 null도 허용합니다. List.of()는 null을 넣을 수 없어 ArrayList로 만듭니다.
    private List<Object> heatLevelEnumWithNull() {
        List<Object> values = new java.util.ArrayList<>();
        values.addAll(ALLOWED_HEAT_LEVELS);
        values.add(null);
        return values;
    }

    // 신뢰 레시피(DB) 목록을 제목/설명/재료/조리 순서가 담긴 텍스트로 만듭니다.
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

    // 건강 제한 조건을 항목별 한 줄 텍스트로 만듭니다.
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

    // 수정 모드에서 쓰는 직전 레시피/수정 요청/제외/대체 정보를 텍스트로 만듭니다. 아무것도 없으면 "없음"입니다.
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

    // 검색 근거를 줄 단위로 공백/중복 제거 후 최대 80줄, maxLength 글자까지만 남겨 프롬프트 길이를 제한합니다.
    private String summarizeSearchContext(String searchContext, int maxLength) {
        if (searchContext == null || searchContext.isBlank()) {
            return "없음";
        }
        String summarized = List.of(searchContext.split("\\R+")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(80)
                .collect(Collectors.joining("\n"));
        return summarized.length() <= maxLength
                ? summarized
                : summarized.substring(0, maxLength);
    }

    // 초안의 재료 이름만 쉼표로 이어 붙입니다.
    private String formatIngredientNames(GeneratedRecipeDraft draft) {
        if (draft == null || draft.ingredients() == null || draft.ingredients().isEmpty()) {
            return "없음";
        }
        return draft.ingredients().stream()
                .filter(ingredient -> ingredient != null && ingredient.name() != null && !ingredient.name().isBlank())
                .map(GeneratedIngredient::name)
                .collect(Collectors.joining(", "));
    }

    // 복구 시 절대 쓰면 안 되는 재료: 제외 재료 + 대체 요청의 원래 재료 + 사용자 알레르기
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

    // 스키마를 JSON 문자열로 만듭니다.
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
