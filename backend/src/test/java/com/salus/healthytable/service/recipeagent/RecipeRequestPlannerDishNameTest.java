package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.service.RecipeNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 계획의 요리 이름에 제외 구절이 섞이지 않는지 확인합니다. 섞이면 웹 검색과 "원문에 요리 이름이 있는가" 확인이
 * 모두 실패합니다(2026-10-09 "김치찌개 레시피 알려줘, 돼지고기는 빼줘").
 */
class RecipeRequestPlannerDishNameTest {

    private final DefaultRecipeRequestPlanner planner = new DefaultRecipeRequestPlanner(new RecipeNormalizer());

    @Test
    void exclusionClauseIsRemovedFromDishName() {
        assertThat(planner.plan("김치찌개 레시피 알려줘, 돼지고기는 빼줘", false, false).dishName()).isEqualTo("김치찌개");
        assertThat(planner.plan("양파 빼고 된장찌개 레시피 알려줘", false, false).dishName()).isEqualTo("된장찌개");
        assertThat(planner.plan("돼지고기 없는 김치찌개 레시피", false, false).dishName()).isEqualTo("김치찌개");
    }

    @Test
    void dishNameWithoutExclusionIsUnchanged() {
        assertThat(planner.plan("두쫀쿠 레시피 알려줘", false, false).dishName()).isEqualTo("두쫀쿠");
        assertThat(planner.plan("참치김치찌개 레시피 알려줘", false, false).dishName()).isEqualTo("참치김치찌개");
    }

    // 제외 재료는 요리 이름에서 빠져도 따로 그대로 뽑혀야 합니다.
    @Test
    void exclusionIsStillExtractedSeparately() {
        assertThat(planner.extractExplicitExclusions("김치찌개 레시피 알려줘, 돼지고기는 빼줘")).contains("돼지고기");
    }

    // "다른 두쫀쿠 레시피"의 "다른"은 요리 이름이 아닙니다. 이름이 남지 않으면 빈 이름(직전 요리의 다른 레시피)입니다.
    @Test
    void anotherWordsAreNotPartOfDishName() {
        assertThat(planner.plan("다른 두쫀쿠 레시피 알려줘", false, true).dishName()).isEqualTo("두쫀쿠");
        assertThat(planner.plan("두쫀쿠 다른 레시피 알려줘", false, true).dishName()).isEqualTo("두쫀쿠");
        assertThat(planner.plan("다른 레시피 알려줘", false, true).dishName()).isEmpty();
    }
}
