package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일반 대화 답이 "근거 없이 쓴 레시피"인지 모양으로 판단하는지 확인합니다.
 */
class RecipeResponseSanitizerShapeTest {

    private final RecipeResponseSanitizer sanitizer = new RecipeResponseSanitizer();

    @Test
    void recipeShapedRepliesAreDetected() {
        assertThat(sanitizer.looksLikeRecipeResponse("""
                **재료:**
                - 김치 200g
                1. 김치를 볶는다.
                2. 물을 붓는다.""")).isTrue();
        assertThat(sanitizer.looksLikeRecipeResponse("""
                돼지고기 200g, 간장 2큰술, 대파 1개를 준비하세요.
                1) 고기를 볶는다
                2) 간장을 넣는다""")).isTrue();
        assertThat(sanitizer.looksLikeRecipeResponse("- 요리명: 김치찌개\n[재료]\n- 김치")).isTrue();
    }

    @Test
    void explanationsAndMenuListsAreNotRecipes() {
        assertThat(sanitizer.looksLikeRecipeResponse("로메인은 샐러드 재료로 많이 쓰는 상추예요. 100g에 15kcal 정도예요.")).isFalse();
        assertThat(sanitizer.looksLikeRecipeResponse("""
                아침 메뉴 3가지를 추천해 드릴게요!
                1. 계란말이 – 단백질이 풍부해요.
                2. 오트밀 – 포만감이 오래가요.
                3. 요거트 – 간단해요.""")).isFalse();
        assertThat(sanitizer.looksLikeRecipeResponse("레시피가 궁금하면 음식 이름과 함께 물어봐 주세요.")).isFalse();
        assertThat(sanitizer.looksLikeRecipeResponse(null)).isFalse();
    }
}
