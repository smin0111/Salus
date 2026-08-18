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

    @Test
    void fourUnsupportedIngredientsCannotBypassEvidenceValidation() {
        GeneratedRecipeDraft draft = new GeneratedRecipeDraft(
                "김치찌개",
                "김치를 끓이는 찌개입니다.",
                2,
                25,
                null,
                1,
                List.of(
                        new GeneratedIngredient("김치", "200g"),
                        new GeneratedIngredient("돼지고기", "150g"),
                        new GeneratedIngredient("두부", "1모"),
                        new GeneratedIngredient("애호박", "0.5개")),
                List.of(
                        new GeneratedCookingStep(1, "김치를 볶습니다", "중불", 5, "김치가 부드러워진 상태", "타면 물을 조금 넣으세요", List.of("김치")),
                        new GeneratedCookingStep(2, "나머지 재료를 넣고 끓입니다", "중불", 15, "돼지고기가 중심까지 익은 상태", "국물이 졸면 물을 보충하세요", List.of("돼지고기", "두부", "애호박")),
                        new GeneratedCookingStep(3, "간을 확인합니다", "약불", 2, "국물 간이 맞는 상태", "짜면 물을 보충하세요", List.of("김치"))),
                List.of());
        Recipe recipe = mapper.toRecipe(draft);
        String reply = formatter.format(draft, List.of());

        RecipeValidator.ValidationResult result = validator.validateStructured(
                recipe,
                "검색어: 김치찌개\n김치찌개는 김치를 끓이는 음식입니다.",
                reply,
                draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("근거에 없는 핵심 재료"));
    }

    @Test
    void groundedRecipePassesStrictEvidenceValidation() {
        GeneratedRecipeDraft draft = new GeneratedRecipeDraft(
                "김치찌개",
                "김치와 돼지고기를 충분히 끓이는 찌개입니다.",
                2,
                25,
                null,
                1,
                List.of(
                        new GeneratedIngredient("김치", "200g"),
                        new GeneratedIngredient("돼지고기", "150g"),
                        new GeneratedIngredient("두부", "0.5모"),
                        new GeneratedIngredient("물", "500ml")),
                List.of(
                        new GeneratedCookingStep(1, "김치와 돼지고기를 볶습니다", "중불", 5, "돼지고기 겉면이 익은 상태", "타면 물을 조금 넣으세요", List.of("김치", "돼지고기")),
                        new GeneratedCookingStep(2, "물을 붓고 충분히 끓입니다", "중불", 15, "돼지고기가 중심까지 익고 김치가 부드러운 상태", "국물이 졸면 물을 보충하세요", List.of("물", "김치", "돼지고기")),
                        new GeneratedCookingStep(3, "두부를 넣고 더 끓입니다", "중약불", 3, "두부가 속까지 따뜻한 상태", "두부가 부서지지 않게 젓지 마세요", List.of("두부"))),
                List.of());
        Recipe recipe = mapper.toRecipe(draft);
        String reply = formatter.format(draft, List.of());

        RecipeValidator.ValidationResult result = validator.validateStructured(
                recipe,
                "검색어: 김치찌개\n김치 200g, 돼지고기 150g, 두부 반 모와 물 500ml를 사용한다. "
                        + "김치와 돼지고기를 먼저 볶은 뒤 물을 붓고 15분 끓이고 두부를 넣는다.",
                reply,
                draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void completeStructuredRecipeIsNotRejectedOnlyBecauseItHasTwoSteps() {
        GeneratedRecipeDraft draft = new GeneratedRecipeDraft(
                "제육볶음",
                "양념한 돼지고기를 충분히 익혀 볶는 요리입니다.",
                2,
                20,
                null,
                1,
                List.of(
                        new GeneratedIngredient("돼지고기", "400g"),
                        new GeneratedIngredient("양파", "0.5개"),
                        new GeneratedIngredient("고추장", "2큰술")),
                List.of(
                        new GeneratedCookingStep(1, "돼지고기에 고추장을 버무립니다", "해당 없음", 2, "양념이 고르게 묻은 상태", "고추장이 뭉치면 손으로 풀어주세요", List.of("돼지고기", "고추장")),
                        new GeneratedCookingStep(2, "돼지고기와 양파를 팬에서 볶습니다", "중강불", 12, "돼지고기가 중심까지 완전히 익은 상태", "겉이 타면 불을 중불로 낮추세요", List.of("돼지고기", "양파"))),
                List.of());
        Recipe recipe = mapper.toRecipe(draft);
        String reply = formatter.format(draft, List.of());

        RecipeValidator.ValidationResult result = validator.validateStructured(
                recipe,
                "검색어: 제육볶음\n돼지고기 400g, 양파 반 개, 고추장 2큰술을 사용한다. "
                        + "고기를 양념한 뒤 팬에서 12분 볶아 중심까지 완전히 익힌다.",
                reply,
                draft);

        assertThat(result.valid()).isTrue();
    }
}
