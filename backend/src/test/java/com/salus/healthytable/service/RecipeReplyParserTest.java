package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecipeReplyParser} 테스트입니다. 답변 텍스트의 인분/열량 의미가 파싱 후에도 유지되는지 확인합니다.
 */
class RecipeReplyParserTest {

    private final RecipeReplyParser parser = new RecipeReplyParser(new RecipeResponseSanitizer());

    // "[재료 - 2인분]"과 "1인분당 약 150kcal"은 기준 인분 2, 1인분 열량 150으로 파싱되어야 합니다.
    @Test
    void parsesServingsAndPerServingCaloriesWithoutLosingTheirMeaning() {
        Recipe recipe = parser.parseRecipeFromReply("계란찜", """
                계란찜 2인분 레시피입니다.

                조리 시간: 20분 / 열량: 1인분당 약 150kcal / 난이도: 1

                [재료 - 2인분]
                - 달걀 150g

                [조리 순서]
                1. 달걀을 중탕한다.
                """);

        assertThat(recipe).isNotNull();
        assertThat(recipe.getBaseServings()).isEqualTo(2);
        assertThat(recipe.getCalories()).isEqualTo(150);
        assertThat(recipe.getCaloriesPerServing()).isEqualTo(150);
    }

    // 기준이 표시되지 않은 예전 열량 값은 1인분 열량으로 단정하지 않아야 합니다(caloriesPerServing = null).
    @Test
    void legacyCaloriesWithoutBasisAreNotClaimedAsPerServing() {
        Recipe recipe = parser.parseRecipeFromReply("감자볶음", """
                감자볶음 레시피입니다.

                조리 시간: 10분 / 열량: 200kcal / 난이도: 1

                [재료]
                - 감자 200g

                [조리 순서]
                1. 감자를 볶는다.
                """);

        assertThat(recipe).isNotNull();
        assertThat(recipe.getCalories()).isEqualTo(200);
        assertThat(recipe.getCaloriesPerServing()).isNull();
    }
}
