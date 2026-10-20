package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecipeDraftMapper} 테스트입니다.
 */
class RecipeDraftMapperTest {

    private final RecipeDraftMapper mapper = new RecipeDraftMapper();

    // 손질 단계처럼 오븐을 쓰지 않는 단계에는 LLM이 넣은 온도가 있어도 표시하지 않아야 합니다.
    @Test
    void defensiveFormattingHidesTemperatureFromPreparationStep() {
        GeneratedCookingStep step = new GeneratedCookingStep(
                1,
                "감자와 애호박을 고르게 썹니다",
                "무가열",
                250,
                7,
                "채소 크기가 고른 상태",
                null,
                java.util.List.of("감자", "애호박"));

        assertThat(mapper.formatStep(step))
                .doesNotContain("250℃")
                .doesNotContain("조리 온도");
    }

    // 오븐 단계의 온도는 표시해야 합니다.
    @Test
    void ovenTemperatureIsStillRendered() {
        GeneratedCookingStep step = new GeneratedCookingStep(
                1,
                "감자를 오븐에서 굽습니다",
                "해당 없음",
                200,
                20,
                "표면이 노릇하고 중심이 부드러운 상태",
                null,
                java.util.List.of("감자"));

        assertThat(mapper.formatStep(step)).contains("조리 온도는 200℃로 맞추세요");
    }

    // 초안의 인분 수와 1인분 열량이 Recipe 엔티티까지 그대로 전달되어야 합니다.
    @Test
    void structuredServingsAndPerServingCaloriesReachRecipeEntity() {
        GeneratedRecipeDraft draft = new GeneratedRecipeDraft(
                "계란찜",
                "부드러운 계란찜",
                2,
                20,
                150,
                1,
                List.of(new GeneratedIngredient("달걀", "150g")),
                List.of(new GeneratedCookingStep(
                        1, "달걀을 약불에서 익힙니다", "약불", 10,
                        "전체가 푸딩처럼 함께 흔들리는 상태", null, List.of("달걀"))),
                List.of());

        Recipe recipe = mapper.toRecipe(draft);

        assertThat(recipe.getBaseServings()).isEqualTo(2);
        assertThat(recipe.getCalories()).isEqualTo(150);
        assertThat(recipe.getCaloriesPerServing()).isEqualTo(150);
    }
}
