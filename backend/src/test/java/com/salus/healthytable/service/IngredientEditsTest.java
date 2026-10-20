package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재료 변경 후속 요청을 규칙으로 읽는지 확인합니다. 문장은 2026-10-09 실전 평가 후속 질문에서 가져왔습니다.
 */
class IngredientEditsTest {

    @Test
    void readsRemovalSubstitutionAndAddition() {
        assertThat(IngredientEdits.parse("양파 빼줘").orElseThrow().removals()).containsExactly("양파");
        assertThat(IngredientEdits.parse("양파는 빼고 다시 알려줘").orElseThrow().removals()).containsExactly("양파");

        IngredientEdits.Edit swap = IngredientEdits.parse("그럼 깻잎 말고 상추 넣어줘").orElseThrow();
        assertThat(swap.substitutions()).containsExactly(new IngredientEdits.Substitution("깻잎", "상추"));
        assertThat(swap.additions()).isEmpty();
        assertThat(swap.removals()).isEmpty();

        assertThat(IngredientEdits.parse("두부 대신 닭가슴살 써줘").orElseThrow().substitutions())
                .containsExactly(new IngredientEdits.Substitution("두부", "닭가슴살"));
        assertThat(IngredientEdits.parse("간장을 굴소스로 바꿔줘").orElseThrow().substitutions())
                .containsExactly(new IngredientEdits.Substitution("간장", "굴소스"));
        assertThat(IngredientEdits.parse("치즈 넣어줘").orElseThrow().additions()).containsExactly("치즈");
    }

    // 새 요리 요청이나 변경이 없는 말은 후속 변경으로 읽지 않습니다.
    @Test
    void ignoresNewRecipeRequestsAndSmallTalk() {
        assertThat(IngredientEdits.parse("양파 없는 김치찌개 레시피 알려줘")).isEmpty();
        assertThat(IngredientEdits.parse("크룽지 레시피 알려줘")).isEmpty();
        assertThat(IngredientEdits.parse("고마워")).isEmpty();
        assertThat(IngredientEdits.parse("3인분으로 바꿔줘")).isEmpty();
    }

    @Test
    void followUpIncludesDetailRequestsButNotNewDishes() {
        assertThat(IngredientEdits.isFollowUpOnPreviousRecipe("좀 더 자세히 설명해줘")).isTrue();
        assertThat(IngredientEdits.isFollowUpOnPreviousRecipe("그럼 깻잎 말고 상추 넣어줘")).isTrue();
        assertThat(IngredientEdits.isFollowUpOnPreviousRecipe("크룽지 레시피 알려줘")).isFalse();
    }

    @Test
    void readsDishSwitchTargetButNotIngredientSwap() {
        assertThat(IngredientEdits.dishSwitchTarget("로제떡볶이로 바꿔줘")).contains("로제떡볶이");
        assertThat(IngredientEdits.dishSwitchTarget("그럼 참치김치찌개로 바꿔줘")).contains("참치김치찌개");
        assertThat(IngredientEdits.dishSwitchTarget("간장을 굴소스로 바꿔줘")).isEmpty();
    }

    // "레시피를 좀 더 자세히"처럼 "레시피"가 들어가도 요리 이름이 없으면 직전 레시피를 자세히 해 달라는 요청입니다.
    @Test
    void detailRequestsWithoutDishNameFollowThePreviousRecipe() {
        assertThat(IngredientEdits.isDetailRequest("레시피를 좀 더 자세히")).isTrue();
        assertThat(IngredientEdits.isDetailRequest("나 초보라서 좀더 자세히 알려줘")).isTrue();
        assertThat(IngredientEdits.isFollowUpOnPreviousRecipe("레시피를 좀 더 자세히")).isTrue();
        assertThat(IngredientEdits.isDetailRequest("김치찌개 레시피 자세히 알려줘")).isFalse();
        assertThat(IngredientEdits.isDetailRequest("김치전 레시피 알려줘")).isFalse();
    }
}
