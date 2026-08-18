package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "레시피정확도실행", matches = "true")
class RecipeAccuracyLiveTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RecipePromptFactory promptFactory = new RecipePromptFactory(objectMapper);
    private final RecipeDraftValidator draftValidator = new RecipeDraftValidator();
    private final RecipeDraftMapper mapper = new RecipeDraftMapper();
    private final RecipeReplyFormatter formatter = new RecipeReplyFormatter(mapper);
    private final RecipeValidator evidenceValidator = new RecipeValidator();

    @Test
    void representativeKoreanRecipesPassEveryAccuracyGate() {
        OllamaRecipeGenerationClient client = new OllamaRecipeGenerationClient(
                WebClient.builder().build(),
                objectMapper,
                promptFactory);
        ReflectionTestUtils.setField(client, "recipeModel", System.getProperty("레시피모델", "qwen3:8b"));
        ReflectionTestUtils.setField(client, "recipeTemperature", 0.0);
        ReflectionTestUtils.setField(client, "recipeTopP", 0.3);
        ReflectionTestUtils.setField(client, "recipeNumPredict", 1_400);
        ReflectionTestUtils.setField(client, "recipeTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(client, "primaryUrl", "http://localhost:11434/api/chat");
        ReflectionTestUtils.setField(client, "secondaryUrl", "http://localhost:11434/api/chat");

        String selectedTitle = System.getProperty("평가음식", "").trim();
        List<AccuracyCase> selectedCases = cases().stream()
                .filter(accuracyCase -> selectedTitle.isBlank() || accuracyCase.title().equals(selectedTitle))
                .toList();
        assertThat(selectedCases).as("평가할 음식이 존재해야 합니다.").isNotEmpty();

        for (AccuracyCase accuracyCase : selectedCases) {
            RecipeGenerationRequest request = request(accuracyCase);
            GeneratedRecipeDraft draft = client.generate(request).block();
            RecipeDraftValidator.ValidationResult draftResult = draftValidator.validate(request, draft);
            if (!draftResult.valid() && draftResult.retryable()) {
                draft = client.repair(request, draft, draftResult.reasons()).block();
                draftResult = draftValidator.validate(request, draft);
            }

            assertThat(draftResult.valid())
                    .as("%s 구조 검증 실패: %s", accuracyCase.title(), draftResult.reasons())
                    .isTrue();

            String reply = formatter.format(draft, List.of());
            RecipeValidator.ValidationResult evidenceResult = evidenceValidator.validateStructured(
                    mapper.toRecipe(draft),
                    accuracyCase.evidence(),
                    reply,
                    draft);
            assertThat(evidenceResult.valid())
                    .as("%s 근거 검증 실패: %s / %s",
                            accuracyCase.title(), evidenceResult.reasons(), evidenceResult.dataQualityWarnings())
                    .isTrue();
        }
    }

    private RecipeGenerationRequest request(AccuracyCase accuracyCase) {
        return new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE,
                accuracyCase.title() + " 레시피 알려줘",
                accuracyCase.title(),
                List.of(),
                accuracyCase.evidence(),
                "고정 평가 근거",
                List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "",
                List.of(),
                List.of(),
                List.of());
    }

    private List<AccuracyCase> cases() {
        return List.of(
                new AccuracyCase(
                        "김치찌개",
                        """
                                검색어: 김치찌개
                                출처: 고정 평가 근거 1
                                2인분 재료는 김치 200g, 돼지고기 150g, 두부 0.5모, 물 500ml, 대파 0.5대,
                                고춧가루 1큰술, 다진 마늘 1작은술이다.
                                김치와 돼지고기를 중불에서 5분 볶고 물을 부어 15분 끓인다.
                                돼지고기가 중심까지 완전히 익었는지 확인한 뒤 두부와 대파를 넣고 3분 더 끓인다.
                                총 조리 시간은 약 25분이다.
                                """),
                new AccuracyCase(
                        "된장찌개",
                        """
                                검색어: 된장찌개
                                출처: 고정 평가 근거 2
                                2인분 재료는 된장 2큰술, 두부 0.5모, 애호박 0.5개, 감자 1개, 양파 0.5개,
                                대파 0.5대, 다진 마늘 1작은술, 물 500ml이다.
                                감자와 물을 중불에서 8분 끓이고 된장을 풀어 애호박과 양파를 넣어 7분 더 끓인다.
                                감자가 젓가락으로 찔렀을 때 부드럽게 들어가면 두부와 대파를 넣고 3분 끓인다.
                                총 조리 시간은 약 22분이다.
                                """),
                new AccuracyCase(
                        "제육볶음",
                        """
                                검색어: 제육볶음
                                출처: 고정 평가 근거 3
                                2인분 재료는 돼지고기 400g, 양파 0.5개, 대파 1대, 고추장 2큰술, 간장 1큰술,
                                고춧가루 1큰술, 설탕 1큰술, 다진 마늘 1큰술, 식용유 1큰술이다.
                                고추장, 간장, 고춧가루, 설탕, 마늘을 섞어 돼지고기에 버무린다.
                                팬에 식용유를 두르고 중강불에서 돼지고기를 8분 볶은 뒤 양파와 대파를 넣어 4분 더 볶는다.
                                가장 두꺼운 고기 조각이 중심까지 완전히 익고 분홍색이 남지 않았는지 확인한다.
                                총 조리 시간은 약 20분이다.
                                """));
    }

    private record AccuracyCase(String title, String evidence) {
    }
}
