package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecipePromptFactory} 테스트입니다.
 */
@SuppressWarnings("unchecked")
class RecipePromptFactoryTest {

    private final RecipePromptFactory promptFactory = new RecipePromptFactory(new ObjectMapper());

    // 생성 프롬프트에 여러 출처의 실제 근거 내용과 URL이 빠지지 않고 들어가야 합니다.
    @Test
    void generationPromptKeepsActualEvidenceContentFromMultipleSources() {
        RecipeGenerationRequest request = new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE,
                "김치찌개 레시피 알려줘",
                "김치찌개",
                List.of(),
                """
                        검색어: 김치찌개
                        - 출처: https://example.com/one
                          제목: 김치찌개 레시피
                          내용: 김치 200g과 돼지고기 150g을 먼저 볶는다.
                        - 출처: https://example.org/two
                          제목: 김치찌개 만드는 법
                          내용: 물 500ml를 붓고 15분 끓인 뒤 두부 반 모를 넣는다.
                        """,
                "test",
                List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "",
                List.of(),
                List.of(),
                List.of());

        String prompt = promptFactory.buildGenerationPrompt(request);

        assertThat(prompt)
                .contains("김치 200g과 돼지고기 150g")
                .contains("물 500ml를 붓고 15분")
                .contains("https://example.com/one")
                .contains("https://example.org/two");
    }

    // 복구 프롬프트의 근거는 최초 생성 프롬프트보다 훨씬 짧게(1,800자) 잘려야 합니다.
    @Test
    void repairEvidenceIsMuchSmallerThanInitialEvidence() {
        String oversizedEvidence = "A".repeat(10_000);
        RecipeGenerationRequest request = new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE,
                "감자구이 레시피 알려줘",
                "감자구이",
                List.of(),
                oversizedEvidence,
                "test",
                List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "",
                List.of(),
                List.of(),
                List.of());
        GeneratedRecipeDraft invalidDraft = new GeneratedRecipeDraft(
                "감자구이", "감자를 굽는 요리", 1, 20, null, 1,
                List.of(new GeneratedIngredient("감자", "200g")),
                List.of(new GeneratedCookingStep(
                        1, "감자를 굽는다", "중불", 10, "속까지 익은 상태", null, List.of("감자"))),
                List.of());

        String generationPrompt = promptFactory.buildGenerationPrompt(request);
        String repairPrompt = promptFactory.buildRepairPrompt(
                request,
                invalidDraft,
                List.of("완료 기준을 수정하세요."));

        assertThat(generationPrompt)
                .contains("A".repeat(5_000))
                .doesNotContain("A".repeat(5_001));
        assertThat(repairPrompt)
                .contains("A".repeat(1_800))
                .doesNotContain("A".repeat(1_801));
        assertThat(repairPrompt.length()).isLessThan(generationPrompt.length());
    }

    // 대체 요청이면 adjustments를 스키마에서 강제해야 합니다.
    // 프롬프트로만 부탁하면 모델이 빈 배열을 내고 ADJUSTMENT_REASON_MISSING으로 실패합니다.
    @Test
    void substituteRequestRequiresAdjustmentsInSchema() {
        RecipeGenerationRequest request = substituteRequest();

        Map<String, Object> schema = promptFactory.jsonSchema(request);

        assertThat((List<String>) schema.get("required")).contains("adjustments");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat((Map<String, Object>) properties.get("adjustments")).containsEntry("minItems", 1);
    }

    // 일반 생성 요청에는 adjustments를 강제하지 않아야 합니다. 빈 값이 정상입니다.
    @Test
    void createRequestDoesNotRequireAdjustments() {
        RecipeGenerationRequest request = new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE, "김치찌개 알려줘", "김치찌개",
                List.of(), "근거", "searxng", List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "", List.of(), List.of(), List.of());

        Map<String, Object> schema = promptFactory.jsonSchema(request);

        assertThat((List<String>) schema.get("required")).doesNotContain("adjustments");
    }

    // 모드가 SUBSTITUTE여도 실제 대체 목록이 없으면 강제하지 않아야 합니다.
    @Test
    void substituteModeWithoutSubstitutionsDoesNotRequireAdjustments() {
        RecipeGenerationRequest request = new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.SUBSTITUTE, "바꿔줘", "김치찌개",
                List.of(), "근거", "searxng", List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "", List.of(), List.of(), List.of());

        Map<String, Object> schema = promptFactory.jsonSchema(request);

        assertThat((List<String>) schema.get("required")).doesNotContain("adjustments");
    }

    // 인자 없는 기존 스키마는 그대로여야 합니다.
    @Test
    void schemaWithoutRequestKeepsOriginalRequiredFields() {
        assertThat((List<String>) promptFactory.jsonSchema().get("required")).doesNotContain("adjustments");
    }

    private RecipeGenerationRequest substituteRequest() {
        return new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.SUBSTITUTE,
                "돼지고기를 두부로 바꿔줘", "김치찌개",
                List.of(), "근거", "searxng", List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "", List.of(), List.of(),
                List.of(new RecipeGenerationRequest.IngredientSubstitution("돼지고기", "두부")));
    }

    // 대체가 한 건이면 방향과 분량을 스키마로 고정해야 합니다.
    // minItems만으로는 모델이 from/to를 뒤집고 quantityAdjustment에 null을 넣습니다.
    @Test
    void singleSubstitutionPinsDirectionAndQuantityInSchema() {
        Map<String, Object> schema = promptFactory.jsonSchema(substituteRequest());

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> adjustments = (Map<String, Object>) properties.get("adjustments");
        Map<String, Object> item = (Map<String, Object>) adjustments.get("items");
        Map<String, Object> itemProperties = (Map<String, Object>) item.get("properties");

        assertThat((Map<String, Object>) itemProperties.get("fromIngredient"))
                .containsEntry("const", "돼지고기").containsEntry("type", "string");
        assertThat((Map<String, Object>) itemProperties.get("toIngredient"))
                .containsEntry("const", "두부");
        // required는 키 존재만 보장하므로 타입에서 null을 빼야 실제로 값이 들어옵니다.
        assertThat((Map<String, Object>) itemProperties.get("quantityAdjustment"))
                .containsEntry("type", "string");
        assertThat((Map<String, Object>) itemProperties.get("reason"))
                .containsEntry("type", "string");
    }

    // 대체가 여러 건이면 항목별 값을 고정할 수 없으므로 적용하지 않습니다.
    @Test
    void multipleSubstitutionsDoNotPinDirection() {
        RecipeGenerationRequest request = new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.SUBSTITUTE, "바꿔줘", "김치찌개",
                List.of(), "근거", "searxng", List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "", List.of(), List.of(),
                List.of(new RecipeGenerationRequest.IngredientSubstitution("돼지고기", "두부"),
                        new RecipeGenerationRequest.IngredientSubstitution("대파", "양파")));

        Map<String, Object> schema = promptFactory.jsonSchema(request);
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> adjustments = (Map<String, Object>) properties.get("adjustments");
        Map<String, Object> item = (Map<String, Object>) adjustments.get("items");
        Map<String, Object> itemProperties = (Map<String, Object>) item.get("properties");

        assertThat((Map<String, Object>) itemProperties.get("fromIngredient")).doesNotContainKey("const");
    }

    // 에어프라이어 요리면 최소 한 단계에 온도를 요구해야 합니다.
    // 프롬프트 안내만으로는 모델이 온도를 문장에만 쓰고 필드를 비웁니다.
    @Test
    void airFryerRequestRequiresTemperatureInAtLeastOneStep() {
        RecipeGenerationRequest request = new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE, "에어프라이어 감자구이 알려줘", "에어프라이어 감자구이",
                List.of(), "근거", "searxng", List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "", List.of(), List.of(), List.of());

        Map<String, Object> properties = (Map<String, Object>) promptFactory.jsonSchema(request).get("properties");
        Map<String, Object> steps = (Map<String, Object>) properties.get("steps");

        assertThat(steps).containsKey("contains").containsEntry("minContains", 1);
        Map<String, Object> contains = (Map<String, Object>) steps.get("contains");
        assertThat((List<String>) contains.get("required")).contains("temperatureC");
    }

    // 오븐을 쓰지 않는 요리에는 온도를 요구하지 않아야 합니다. 팬 조리는 temperatureC가 null입니다.
    @Test
    void stovetopRequestDoesNotRequireTemperature() {
        RecipeGenerationRequest request = new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE, "김치찌개 알려줘", "김치찌개",
                List.of(), "근거", "searxng", List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "", List.of(), List.of(), List.of());

        Map<String, Object> properties = (Map<String, Object>) promptFactory.jsonSchema(request).get("properties");
        assertThat((Map<String, Object>) properties.get("steps")).doesNotContainKey("contains");
    }

    // Ollama는 스키마 properties 순서대로 필드를 생성합니다. Map.of/Map.copyOf를 쓰면 이 순서가
    // JVM마다 바뀌어 같은 코드의 평가 결과가 실행마다 달라졌습니다. 요청 종류와 상관없이 고정돼야 합니다.
    @Test
    void schemaPropertyOrderIsFixedForEveryRequestKind() throws Exception {
        RecipeGenerationRequest airFryer = new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE, "에어프라이어 감자구이 알려줘", "에어프라이어 감자구이",
                List.of(), "근거", "searxng", List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "", List.of(), List.of(), List.of());
        List<Map<String, Object>> schemas = List.of(
                promptFactory.jsonSchema(), promptFactory.jsonSchema(substituteRequest()), promptFactory.jsonSchema(airFryer));

        for (Map<String, Object> schema : schemas) {
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties.keySet()).containsExactly(
                    "title", "description", "servings", "cookingTimeMinutes", "caloriesKcal", "difficulty",
                    "ingredients", "steps", "adjustments", "safetyNotes");
            Map<String, Object> ingredient = (Map<String, Object>) ((Map<String, Object>) properties.get("ingredients")).get("items");
            assertThat(((Map<String, Object>) ingredient.get("properties")).keySet())
                    .containsExactly("unit", "name", "amount", "preparation");
            Map<String, Object> adjustment = (Map<String, Object>) ((Map<String, Object>) properties.get("adjustments")).get("items");
            assertThat(((Map<String, Object>) adjustment.get("properties")).keySet())
                    .containsExactly("type", "fromIngredient", "toIngredient", "reason", "quantityAdjustment");
            // 실제로 Ollama에 보내는 JSON 문자열에서도 순서가 유지돼야 합니다.
            String json = new ObjectMapper().writeValueAsString(schema);
            assertThat(json).contains("\"properties\":{\"title\":");
            assertThat(json).contains("\"properties\":{\"unit\":");
        }
    }
}
