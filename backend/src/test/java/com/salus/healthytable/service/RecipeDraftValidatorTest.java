package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeDraftValidatorTest {

    private final RecipeDraftValidator validator = new RecipeDraftValidator();

    @Test
    void stepIngredientNotDeclaredFailsValidation() {
        GeneratedRecipeDraft draft = draft(
                "감자구이",
                List.of(new GeneratedIngredient("감자", "2개")),
                List.of(new GeneratedCookingStep(1, "감자와 버터를 팬에 넣습니다", "중불", 3, "감자가 노릇한 상태", "타면 불을 낮추세요", List.of("감자", "버터"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(request("감자구이", List.of(), List.of(), List.of()), draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.codes()).contains("STEP_INGREDIENT_NOT_DECLARED");
    }

    @Test
    void instructionTokensAreNotGuessedAsIngredients() {
        GeneratedRecipeDraft draft = draft(
                "감자볶음",
                List.of(new GeneratedIngredient("감자", "2개")),
                List.of(new GeneratedCookingStep(1, "감자를 잘게 썰어 5분 볶는다", "중불", 5, "감자가 익은 상태", "타면 불을 줄이세요", List.of("감자"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(request("감자볶음", List.of(), List.of(), List.of()), draft);

        assertThat(result.valid()).isTrue();
        assertThat(result.reasons()).doesNotContain("잘게", "썰어", "5분");
    }

    @Test
    void eggAndGyeranAreAliases() {
        GeneratedRecipeDraft draft = draft(
                "계란찜",
                List.of(new GeneratedIngredient("계란", "2개"), new GeneratedIngredient("물", "100ml")),
                List.of(new GeneratedCookingStep(1, "달걀과 물을 섞습니다", "약불", 5, "부드럽게 굳은 상태", "덜 익으면 1분 더 익히세요", List.of("달걀", "물"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(request("계란찜", List.of(), List.of(), List.of()), draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void noHeatBeveragePassesWithoutHeatLevel() {
        GeneratedRecipeDraft draft = draft(
                "청포도 에이드",
                List.of(new GeneratedIngredient("청포도 베이스", "45g"), new GeneratedIngredient("사이다", "160ml")),
                List.of(new GeneratedCookingStep(
                        1,
                        "청포도 베이스와 사이다를 잔에 붓습니다",
                        null,
                        null,
                        "차갑게 섞인 상태",
                        "탄산은 마지막에 천천히 부으세요",
                        List.of("청포도 베이스", "사이다"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(request("청포도 에이드", List.of(), List.of(), List.of()), draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void noHeatBeverageWithHeatLevelFailsValidation() {
        GeneratedRecipeDraft draft = draft(
                "청포도 에이드",
                List.of(new GeneratedIngredient("청포도 베이스", "45g"), new GeneratedIngredient("사이다", "160ml")),
                List.of(new GeneratedCookingStep(1, "청포도 베이스와 사이다를 잔에 붓습니다", "중불", 1, "섞인 상태", "넘치면 잠시 기다리세요")));

        RecipeDraftValidator.ValidationResult result = validator.validate(request("청포도 에이드", List.of(), List.of(), List.of()), draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("NO_HEAT_HAS_HEAT_LEVEL");
    }

    @Test
    void substitutionTargetMustRemainInIngredientsAndSteps() {
        GeneratedRecipeDraft draft = draft(
                "알룰로스 토마토 샐러드",
                List.of(new GeneratedIngredient("토마토", "1개"), new GeneratedIngredient("알룰로스", "1작은술")),
                List.of(new GeneratedCookingStep(1, "토마토에 알룰로스를 넣고 섞습니다", null, null, "고르게 섞인 상태", "너무 달면 토마토를 더 넣으세요", List.of("토마토", "알룰로스"))),
                List.of(new RecipeAdjustment(
                        "SUBSTITUTION",
                        "설탕",
                        "알룰로스",
                        "알룰로스는 단맛이 달라 처음에는 적게 넣습니다.",
                        "설탕 1작은술 대신 알룰로스 1작은술부터 넣고 맛을 확인합니다.")));

        RecipeDraftValidator.ValidationResult result = validator.validate(request(
                "토마토 샐러드",
                List.of(),
                List.of(new RecipeGenerationRequest.IngredientSubstitution("설탕", "알룰로스")),
                List.of()), draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void englishTablespoonIsNormalizedToKoreanUnit() {
        GeneratedIngredient ingredient = new GeneratedIngredient("고추장", 2.0, "tbsp", null);
        RecipeDraftMapper mapper = new RecipeDraftMapper();

        assertThat(ingredient.normalizedUnit()).isEqualTo("큰술");
        assertThat(mapper.toIngredientLines(List.of(ingredient))).containsExactly("고추장 2큰술");
    }

    @Test
    void unknownEnglishUnitFailsValidation() {
        GeneratedRecipeDraft draft = draft(
                "제육볶음",
                List.of(new GeneratedIngredient("돼지고기", "300g"), new GeneratedIngredient("고추장", 1.0, "bsp", null)),
                List.of(new GeneratedCookingStep(1, "돼지고기와 고추장을 볶습니다", "중불", 5, "양념이 배인 상태", "타면 불을 낮추세요", List.of("돼지고기", "고추장"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(request("제육볶음", List.of(), List.of(), List.of()), draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("INGREDIENT_UNIT_UNKNOWN");
    }

    @Test
    void mediumHighHeatLevelIsNormalized() {
        GeneratedCookingStep step = new GeneratedCookingStep(1, "팬을 달군 뒤 볶습니다", "medium-high", 3, "향이 올라온 상태", "타면 중불로 낮추세요", List.of("감자"));
        GeneratedRecipeDraft draft = draft(
                "감자볶음",
                List.of(new GeneratedIngredient("감자", "2개")),
                List.of(step));

        RecipeDraftValidator.ValidationResult result = validator.validate(request("감자볶음", List.of(), List.of(), List.of()), draft);

        assertThat(step.normalizedHeatLevel()).isEqualTo("중강불");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void noHeatBeverageWithMediumFailsAfterNormalization() {
        GeneratedRecipeDraft draft = draft(
                "바나나 스무디",
                List.of(new GeneratedIngredient("바나나", "1개"), new GeneratedIngredient("우유", "200ml")),
                List.of(new GeneratedCookingStep(1, "바나나와 우유를 갈아줍니다", "medium", 1, "부드러운 상태", "덩어리가 남으면 더 갈아주세요", List.of("바나나", "우유"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(request("바나나 스무디", List.of(), List.of(), List.of()), draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("NO_HEAT_HAS_HEAT_LEVEL");
    }

    @Test
    void substitutionWithoutQuantityAdjustmentFailsWithSpecificCode() {
        GeneratedRecipeDraft draft = draft(
                "알룰로스 제육볶음",
                List.of(new GeneratedIngredient("돼지고기", "300g"), new GeneratedIngredient("알룰로스", "1작은술")),
                List.of(new GeneratedCookingStep(1, "돼지고기에 알룰로스를 넣고 볶습니다", "중불", 5, "돼지고기가 중심까지 익고 양념이 고르게 배인 상태", "타면 물을 조금 넣으세요", List.of("돼지고기", "알룰로스"))),
                List.of(new RecipeAdjustment(
                        "SUBSTITUTION",
                        "설탕",
                        "알룰로스",
                        "알룰로스는 단맛이 다를 수 있어 조절합니다.",
                        null)));

        RecipeDraftValidator.ValidationResult result = validator.validate(request(
                "제육볶음",
                List.of(),
                List.of(new RecipeGenerationRequest.IngredientSubstitution("설탕", "알룰로스")),
                List.of()), draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("QUANTITY_ADJUSTMENT_MISSING");
    }

    @Test
    void substitutionLabelIsNotAcceptedAsQuantityAdjustment() {
        GeneratedRecipeDraft draft = draft(
                "알룰로스 제육볶음",
                List.of(new GeneratedIngredient("돼지고기", "300g"), new GeneratedIngredient("알룰로스", "1작은술")),
                List.of(new GeneratedCookingStep(1, "돼지고기에 알룰로스를 넣고 볶습니다", "중불", 5, "돼지고기가 중심까지 익고 양념이 배인 상태", "타면 물을 조금 넣으세요", List.of("돼지고기", "알룰로스"))),
                List.of(new RecipeAdjustment(
                        "SUBSTITUTION",
                        "설탕",
                        "알룰로스",
                        "알룰로스는 단맛이 다를 수 있어 조절합니다.",
                        "SUBSTITUTE")));

        RecipeDraftValidator.ValidationResult result = validator.validate(request(
                "제육볶음",
                List.of(),
                List.of(new RecipeGenerationRequest.IngredientSubstitution("설탕", "알룰로스")),
                List.of()), draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("QUANTITY_ADJUSTMENT_MISSING");
    }

    @Test
    void excludedIngredientUsedInInstructionFails() {
        GeneratedRecipeDraft draft = draft(
                "양파 없는 제육볶음",
                List.of(new GeneratedIngredient("돼지고기", "300g"), new GeneratedIngredient("대파", "1대")),
                List.of(new GeneratedCookingStep(1, "양파를 넣고 돼지고기와 볶습니다", "중불", 5, "고기가 익은 상태", "타면 불을 낮추세요", List.of("돼지고기", "대파"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(request("제육볶음", List.of("양파"), List.of(), List.of()), draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("EXCLUDED_INGREDIENT_REMAINED");
    }

    @Test
    void excludedIngredientNegationInInstructionDoesNotFail() {
        GeneratedRecipeDraft draft = draft(
                "양파 없는 제육볶음",
                List.of(new GeneratedIngredient("돼지고기", "300g"), new GeneratedIngredient("대파", "1대")),
                List.of(new GeneratedCookingStep(1, "양파 없이 대파와 돼지고기를 볶아 향을 보완합니다", "중불", 5, "돼지고기가 중심까지 익고 대파 향이 올라온 상태", "타면 불을 낮추세요", List.of("돼지고기", "대파"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(request("제육볶음", List.of("양파"), List.of(), List.of()), draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void excludedIngredientRemainingFailsValidation() {
        GeneratedRecipeDraft draft = draft(
                "설탕 없는 샐러드",
                List.of(new GeneratedIngredient("토마토", "1개"), new GeneratedIngredient("설탕", "1작은술")),
                List.of(new GeneratedCookingStep(1, "토마토에 설탕을 넣고 섞습니다", null, null, "고르게 섞인 상태", "너무 달면 토마토를 더 넣으세요")));

        RecipeDraftValidator.ValidationResult result = validator.validate(request("토마토 샐러드", List.of("설탕"), List.of(), List.of()), draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("EXCLUDED_INGREDIENT_REMAINED");
    }

    @Test
    void requestedDishAndGeneratedTitleMustMatch() {
        GeneratedRecipeDraft draft = draft(
                "된장찌개",
                List.of(new GeneratedIngredient("된장", "2큰술"), new GeneratedIngredient("두부", "1모")),
                List.of(new GeneratedCookingStep(
                        1,
                        "된장과 두부를 넣고 끓입니다",
                        "중불",
                        8,
                        "두부가 따뜻해지고 국물이 고르게 끓는 상태",
                        "국물이 짜면 물을 조금 보충하세요",
                        List.of("된장", "두부"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(
                request("김치찌개", List.of(), List.of(), List.of()),
                draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("TITLE_MISMATCH");
    }

    @Test
    void requestedDishCannotBeChangedIntoAnotherDishType() {
        GeneratedRecipeDraft draft = draft(
                "김치찌개 볶음밥",
                List.of(new GeneratedIngredient("김치", "200g"), new GeneratedIngredient("밥", "1공기")),
                List.of(new GeneratedCookingStep(
                        1,
                        "김치와 밥을 팬에서 볶습니다",
                        "중불",
                        5,
                        "밥알에 김치 양념이 고르게 배인 상태",
                        "밥이 달라붙으면 식용유를 조금 보충하세요",
                        List.of("김치", "밥"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(
                request("김치찌개", List.of(), List.of(), List.of()),
                draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("TITLE_MISMATCH");
    }

    @Test
    void servingsAndCookingTimeAreRequiredForExecutableRecipe() {
        GeneratedRecipeDraft draft = new GeneratedRecipeDraft(
                "감자구이",
                "감자를 굽는 요리입니다.",
                null,
                null,
                180,
                1,
                List.of(new GeneratedIngredient("감자", "2개")),
                List.of(new GeneratedCookingStep(
                        1,
                        "감자를 팬에 올려 굽습니다",
                        "중불",
                        8,
                        "감자 중심까지 부드럽게 익고 표면이 노릇한 상태",
                        "겉만 타면 불을 낮추고 뚜껑을 덮으세요",
                        List.of("감자"))),
                List.of());

        RecipeDraftValidator.ValidationResult result = validator.validate(
                request("감자구이", List.of(), List.of(), List.of()),
                draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("SERVINGS_REQUIRED", "COOKING_TIME_REQUIRED");
    }

    @Test
    void zeroOrNegativeIngredientAmountIsRejected() {
        GeneratedRecipeDraft draft = draft(
                "감자구이",
                List.of(new GeneratedIngredient("감자", 0.0, "개", null)),
                List.of(new GeneratedCookingStep(
                        1,
                        "감자를 팬에 올려 굽습니다",
                        "중불",
                        8,
                        "감자 중심까지 부드럽게 익고 표면이 노릇한 상태",
                        "겉만 타면 불을 낮추고 뚜껑을 덮으세요",
                        List.of("감자"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(
                request("감자구이", List.of(), List.of(), List.of()),
                draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("INGREDIENT_AMOUNT_INVALID");
    }

    @Test
    void aliasDuplicateIngredientsAreRejected() {
        GeneratedRecipeDraft draft = draft(
                "계란찜",
                List.of(new GeneratedIngredient("계란", "1개"), new GeneratedIngredient("달걀", "1개")),
                List.of(new GeneratedCookingStep(
                        1,
                        "계란을 풀어 약불에서 익힙니다",
                        "약불",
                        7,
                        "달걀물이 완전히 응고되고 중심까지 익은 상태",
                        "바닥이 눌어붙으면 불을 더 낮추세요",
                        List.of("계란"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(
                request("계란찜", List.of(), List.of(), List.of()),
                draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("INGREDIENT_DUPLICATED");
    }

    @Test
    void heatedStepRequiresHeatTimeAndCompletionCue() {
        GeneratedRecipeDraft draft = draft(
                "감자볶음",
                List.of(new GeneratedIngredient("감자", "2개")),
                List.of(new GeneratedCookingStep(
                        1,
                        "감자를 팬에서 볶습니다",
                        null,
                        null,
                        null,
                        "타기 시작하면 식용유를 조금 보충하세요",
                        List.of("감자"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(
                request("감자볶음", List.of(), List.of(), List.of()),
                draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains(
                "COOKING_HEAT_REQUIRED",
                "COOKING_MINUTES_REQUIRED",
                "COMPLETION_CUE_REQUIRED");
    }

    @Test
    void highRiskProteinRequiresSafeDonenessCue() {
        GeneratedRecipeDraft draft = draft(
                "닭볶음",
                List.of(new GeneratedIngredient("닭고기", "300g"), new GeneratedIngredient("간장", "1큰술")),
                List.of(new GeneratedCookingStep(
                        1,
                        "닭고기에 간장을 넣고 볶습니다",
                        "중불",
                        10,
                        "양념이 고르게 배인 상태",
                        "겉이 빨리 타면 물을 1큰술 넣으세요",
                        List.of("닭고기", "간장"))));

        RecipeDraftValidator.ValidationResult result = validator.validate(
                request("닭볶음", List.of(), List.of(), List.of()),
                draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.codes()).contains("PROTEIN_DONENESS_CUE_REQUIRED");
    }

    private RecipeGenerationRequest request(
            String title,
            List<String> excluded,
            List<RecipeGenerationRequest.IngredientSubstitution> substitutions,
            List<String> dietaryRestrictions) {
        return new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE,
                title + " 레시피 알려줘",
                title,
                List.of(),
                title + " 근거",
                "test",
                List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), dietaryRestrictions, List.of(), List.of()),
                "",
                List.of(),
                excluded,
                substitutions);
    }

    private GeneratedRecipeDraft draft(String title, List<GeneratedIngredient> ingredients, List<GeneratedCookingStep> steps) {
        return new GeneratedRecipeDraft(title, "설명", 1, 10, 100, 1, ingredients, steps, List.of());
    }

    private GeneratedRecipeDraft draft(
            String title,
            List<GeneratedIngredient> ingredients,
            List<GeneratedCookingStep> steps,
            List<RecipeAdjustment> adjustments) {
        return new GeneratedRecipeDraft(title, "설명", 1, 10, 100, 1, ingredients, steps, adjustments, List.of());
    }
}
