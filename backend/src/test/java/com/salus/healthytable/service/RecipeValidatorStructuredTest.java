package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeValidatorStructuredTest {

    private final RecipeDraftMapper mapper = new RecipeDraftMapper();
    private final RecipeReplyFormatter formatter = new RecipeReplyFormatter(mapper);
    private final RecipeValidator validator = new RecipeValidator();

    @Test
    void structuredPathDoesNotExtractInstructionTokensAsIngredients() {
        GeneratedRecipeDraft draft = new GeneratedRecipeDraft(
                "감자볶음",
                "간단한 볶음입니다.",
                1,
                10,
                120,
                1,
                List.of(new GeneratedIngredient("감자", "2개")),
                List.of(
                        new GeneratedCookingStep(1, "감자를 잘게 썰어 5분 볶습니다", "중불", 5, "감자가 익은 상태", "타면 불을 낮추세요", List.of("감자")),
                        new GeneratedCookingStep(2, "접시에 담습니다", "해당 없음", 1, "먹기 좋은 상태", "간이 싱거우면 소금을 약간 더하세요", List.of("감자"))),
                List.of());
        Recipe recipe = mapper.toRecipe(draft);
        String reply = formatter.format(draft, List.of());

        RecipeValidator.ValidationResult result = validator.validateStructured(
                recipe,
                "감자볶음 핵심 재료: 감자. 조리 방식: 볶음.",
                reply,
                draft);

        assertThat(result.dataQualityWarnings())
                .noneMatch(warning -> warning.contains("조리 순서에만 등장"));
        assertThat(result.dataQualityWarnings())
                .noneMatch(warning -> warning.contains("잘게") || warning.contains("썰어") || warning.contains("5분"));
    }
}
