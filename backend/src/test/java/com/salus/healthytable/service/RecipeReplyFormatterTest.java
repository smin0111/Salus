package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeReplyFormatterTest {

    private final RecipeReplyFormatter formatter = new RecipeReplyFormatter(new RecipeDraftMapper());

    @Test
    void formatterKeepsFrontendCompatibleRecipeTextShape() {
        GeneratedRecipeDraft draft = new GeneratedRecipeDraft(
                "김치짜글이",
                "돼지고기와 김치를 자작하게 끓여 밥과 곁들이기 좋은 메뉴예요.",
                1,
                35,
                520,
                2,
                List.of(new GeneratedIngredient("김치", "150g"), new GeneratedIngredient("돼지고기", "100g")),
                List.of(new GeneratedCookingStep(1, "김치와 돼지고기를 냄비에 넣습니다", "중불", 3, "재료에 윤기가 도는 상태", "타면 물을 2큰술 넣으세요")),
                List.of("나트륨 조절이 필요하면 김치 양을 줄이세요"));

        String reply = formatter.format(draft, List.of());

        assertThat(reply)
                .startsWith("김치짜글이 레시피입니다.")
                .contains("조리 시간: 35분 / 열량: 520kcal / 난이도: 2")
                .contains("[건강 주의]")
                .contains("[재료]")
                .contains("- 김치 150g")
                .contains("[조리 순서]")
                .contains("1. 김치와 돼지고기를 냄비에 넣습니다.");
    }

    @Test
    void structuredFormatterDoesNotApplyLegacyQualityGuard() {
        GeneratedRecipeDraft draft = new GeneratedRecipeDraft(
                "비프 웰링턴",
                "구조화 응답을 그대로 카드 형태로 변환합니다.",
                1,
                60,
                700,
                3,
                List.of(new GeneratedIngredient("소고기", "300g")),
                List.of(new GeneratedCookingStep(
                        1,
                        "버터를 넣지 않고 소고기를 굽습니다",
                        "강불",
                        3,
                        "겉면이 갈색으로 변한 상태",
                        "타면 바로 팬에서 빼세요",
                        List.of("소고기"))),
                List.of());

        String reply = formatter.format(draft, List.of());

        assertThat(reply).contains("[재료]");
        assertThat(reply).contains("[조리 순서]");
        assertThat(reply).doesNotContain("- 버터 1큰술");
    }
}
