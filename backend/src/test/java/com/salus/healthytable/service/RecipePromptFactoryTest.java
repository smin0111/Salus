package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipePromptFactoryTest {

    private final RecipePromptFactory promptFactory = new RecipePromptFactory(new ObjectMapper());

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
}
