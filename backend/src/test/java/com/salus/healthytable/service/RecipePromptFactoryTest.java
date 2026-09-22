package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecipePromptFactory} 테스트입니다.
 */
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
}
