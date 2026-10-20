package com.salus.healthytable.service.recipeagent;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 웹 레시피 재료 양을 인분 비율대로 바꾸는 규칙을 확인합니다(2026-10-17 베타: "1인분용으로"에 식빵 6장 그대로).
 */
class RecipeServingsScalerTest {

    @Test
    void scalesAmountsWithUnitsAndRoundsByKind() {
        double third = 1.0 / 3;
        assertThat(RecipeServingsScaler.scaleLine("식빵 6장", third)).isEqualTo("식빵 2장");
        assertThat(RecipeServingsScaler.scaleLine("계란 4개", third)).isEqualTo("계란 1.5개");
        assertThat(RecipeServingsScaler.scaleLine("마요네즈 2T", third)).isEqualTo("마요네즈 3/4T");
        assertThat(RecipeServingsScaler.scaleLine("설탕 1/2작은술", third)).isEqualTo("설탕 1/8작은술");
        assertThat(RecipeServingsScaler.scaleLine("돼지고기 300g", third)).isEqualTo("돼지고기 100g");
        assertThat(RecipeServingsScaler.scaleLine("물 1과 1/2컵", 2)).isEqualTo("물 3컵");
        // 작은 양은 줄어들어야 합니다(1/4개의 절반이 1/2개로 늘어나던 문제).
        assertThat(RecipeServingsScaler.scaleLine("양파 1/4개", 0.5)).isEqualTo("양파 1/8개");
        assertThat(RecipeServingsScaler.scaleLine("달걀 1개", 0.5)).isEqualTo("달걀 1/2개");
        assertThat(RecipeServingsScaler.scaleLine("햄 1/2컵", 0.5)).isEqualTo("햄 1/4컵");
        assertThat(RecipeServingsScaler.scaleLine("간장 1~2큰술", 2)).isEqualTo("간장 2~4큰술");
        assertThat(RecipeServingsScaler.scaleLine("홀그레인머스타드 1T, 마요네즈 2T을 섞는다", 0.5))
                .isEqualTo("홀그레인머스타드 1/2T, 마요네즈 1T을 섞는다");
    }

    @Test
    void leavesTimesTemperaturesSizesAndVagueAmountsAlone() {
        assertThat(RecipeServingsScaler.scaleLine("180도에서 15분 굽는다", 0.5)).isEqualTo("180도에서 15분 굽는다");
        assertThat(RecipeServingsScaler.scaleLine("대파 2cm 길이로 썬다", 0.5)).isEqualTo("대파 2cm 길이로 썬다");
        assertThat(RecipeServingsScaler.scaleLine("소금 약간", 0.5)).isEqualTo("소금 약간");
        assertThat(RecipeServingsScaler.scaleLine("식빵 6장", 1.0)).isEqualTo("식빵 6장");
    }

    @Test
    void readsRequestedAndSourceServings() {
        assertThat(RecipeServingsScaler.requestedServings("1인분용으로 다시 알려줘")).contains(1);
        assertThat(RecipeServingsScaler.requestedServings("두 인분으로")).contains(2);
        assertThat(RecipeServingsScaler.requestedServings("혼자 먹을 거야")).contains(1);
        assertThat(RecipeServingsScaler.requestedServings("100인분")).isEmpty();
        assertThat(RecipeServingsScaler.requestedServings("맛있게 알려줘")).isEmpty();

        RecipeSourceDocument withServings = new RecipeSourceDocument("s1", RecipeSourceType.GENERAL_WEB, "샌드위치", "",
                "https://example.com/a", "title: 샌드위치\nservings: 3\ningredients:\n식빵 6장\n", LocalDateTime.now(), 0.8);
        RecipeSourceDocument unknown = new RecipeSourceDocument("s2", RecipeSourceType.GENERAL_WEB, "샌드위치", "",
                "https://example.com/b", "title: 샌드위치\nservings: \ningredients:\n식빵 4장\n", LocalDateTime.now(), 0.7);
        assertThat(RecipeServingsScaler.sourceServings(List.of(withServings, unknown))).contains(3);
        assertThat(RecipeServingsScaler.sourceServings(List.of(unknown))).isEmpty();
        assertThat(RecipeServingsScaler.validServings(0)).isNull();
    }
}
