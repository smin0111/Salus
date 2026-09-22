package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.repository.RecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ApprovedRecipeService} 테스트입니다. 실제 승인 카탈로그 JSON으로 렌더링과 카탈로그 검증 규칙을 확인합니다.
 */
class ApprovedRecipeServiceTest {

    private RecipeRepository recipeRepository;
    private ApprovedRecipeService service;

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        service = new ApprovedRecipeService(
                recipeRepository,
                mock(JdbcTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        service.run(null);
    }

    // 카탈로그는 검증을 통과한 레시피 정확히 10개를 로드해야 합니다.
    @Test
    void catalogLoadsExactlyTenValidatedRecipes() {
        assertThat(service.approvedCatalogSize()).isEqualTo(10);
    }

    // 인분/맵기를 여러 번 바꿔도 항상 원본에서 다시 계산해야 합니다(이전 반올림 값이 누적되지 않음).
    @Test
    void servingsAndSpiceAreAlwaysRebuiltFromImmutableBaseRecipe() {
        Recipe approved = approvedRecipe(101L, "jeyuk-bokkeum", 3);
        when(recipeRepository.findByIdAndApprovalStatus(101L, RecipeApprovalStatus.APPROVED))
                .thenReturn(Optional.of(approved));

        ApprovedRecipeService.RenderedRecipe extremeFour = service.renderApprovedRecipe(
                101L, 3, 4, ApprovedRecipeService.SpiceLevel.EXTREME);
        ApprovedRecipeService.RenderedRecipe normalTwo = service.renderApprovedRecipe(
                101L, 3, 2, ApprovedRecipeService.SpiceLevel.NORMAL);

        assertThat(extremeFour.recipe().getIngredients())
                .contains("돼지고기 앞다리살 600g (두께 3mm)")
                .contains("고춧가루 36g (중간 매운맛)")
                .contains("청양고추 40g (어슷썰기)");
        assertThat(extremeFour.recipe().getSteps()).anyMatch(value -> value.contains("청양고추"));
        assertThat(normalTwo.recipe().getIngredients())
                .contains("돼지고기 앞다리살 300g (두께 3mm)")
                .contains("고춧가루 8g (중간 매운맛)")
                .noneMatch(value -> value.startsWith("청양고추 "));
        assertThat(normalTwo.recipe().getSteps()).noneMatch(value -> value.contains("청양고추"));
        assertThat(normalTwo.recipe().getSteps()).noneMatch(value -> value.contains("재료 목록"));
    }

    // 맵지 않은 레시피에 검수되지 않은 "매우 매운맛" 변형을 요청하면 거부해야 합니다.
    @Test
    void nonSpicyRecipeRejectsUnreviewedExtremeVariant() {
        Recipe approved = approvedRecipe(102L, "beef-bulgogi", 3);
        when(recipeRepository.findByIdAndApprovalStatus(102L, RecipeApprovalStatus.APPROVED))
                .thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.renderApprovedRecipe(
                102L, 3, 2, ApprovedRecipeService.SpiceLevel.EXTREME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("검증된")
                .hasMessageContaining("변형이 없습니다");
    }

    // "4인분", "불닭 수준" 같은 자연스러운 한국어 후속 요청을 인식해야 합니다.
    @Test
    void adjustmentParserRecognizesNaturalKoreanFollowUps() {
        ApprovedRecipeService.AdjustmentRequest request = service
                .parseAdjustment("4인용으로, 불닭 정도의 맵기로 다시 말해줘")
                .orElseThrow();

        assertThat(request.servings()).isEqualTo(4);
        assertThat(request.spiceLevel()).isEqualTo(ApprovedRecipeService.SpiceLevel.EXTREME);
    }

    // 흔한 오타("찌게")도 LLM 생성 전에 승인 카탈로그 레시피와 매칭되어야 합니다.
    @Test
    void commonJjigaeTypoStillMatchesApprovedCatalogBeforeGeneration() {
        Recipe approved = approvedRecipe(105L, "doenjang-jjigae", 2);
        when(recipeRepository.findByCatalogKey("doenjang-jjigae"))
                .thenReturn(Optional.of(approved));

        Optional<Recipe> matched = service.findApprovedMatch("된장찌게 레시피 알려줘");

        assertThat(matched).containsSame(approved);
    }

    // 검수된 범위(1~6인분)를 벗어난 인분 요청은 거부해야 합니다.
    @Test
    void rejectsServingCountsOutsideReviewedRange() {
        Recipe approved = approvedRecipe(103L, "kimchi-jjigae", 2);
        when(recipeRepository.findByIdAndApprovalStatus(103L, RecipeApprovalStatus.APPROVED))
                .thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.renderApprovedRecipe(
                103L, 2, 10, ApprovedRecipeService.SpiceLevel.NORMAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1인분부터 6인분");
    }

    // 기본 김치찌개는 시간/열량 값이 일관되고 내부 조건 규칙 문구가 노출되지 않아야 합니다.
    @Test
    void defaultKimchiJjigaeHasConsistentTimeCaloriesAndNoLeakedConditionalRule() {
        Recipe approved = approvedRecipe(104L, "kimchi-jjigae", 2);
        when(recipeRepository.findByIdAndApprovalStatus(104L, RecipeApprovalStatus.APPROVED))
                .thenReturn(Optional.of(approved));

        ApprovedRecipeService.RenderedRecipe rendered = service.renderApprovedRecipe(
                104L, 2, 2, ApprovedRecipeService.SpiceLevel.NORMAL);

        assertThat(rendered.recipe().getCookingTime()).isEqualTo(40);
        assertThat(rendered.recipe().getBaseServings()).isEqualTo(2);
        assertThat(rendered.recipe().getCaloriesPerServing()).isEqualTo(360);
        assertThat(rendered.reply()).contains("열량: 1인분당 약 360kcal");
        assertThat(rendered.recipe().getIngredients()).noneMatch(value -> value.startsWith("청양고추 "));
        assertThat(rendered.recipe().getSteps())
                .noneMatch(value -> value.contains("청양고추"))
                .noneMatch(value -> value.contains("재료 목록"))
                .noneMatch(value -> value.contains("63°C"));
    }

    // 얇은 볶음 고기 레시피는 안전 대기 시간은 유지하되 스테이크식 "휴지" 표현을 쓰지 않아야 합니다.
    @Test
    void thinStirFriedMeatKeepsSafetyWaitWithoutSteakRestingLanguage() {
        Recipe approved = approvedRecipe(106L, "jeyuk-bokkeum", 3);
        when(recipeRepository.findByIdAndApprovalStatus(106L, RecipeApprovalStatus.APPROVED))
                .thenReturn(Optional.of(approved));

        ApprovedRecipeService.RenderedRecipe rendered = service.renderApprovedRecipe(
                106L, 3, 2, ApprovedRecipeService.SpiceLevel.NORMAL);

        assertThat(rendered.recipe().getSteps())
                .anyMatch(step -> step.contains("팬에 3분 그대로 둔다"))
                .noneMatch(step -> step.contains("휴지"));
    }

    // 카탈로그 검증기는 사용자 설명에 새어 나온 내부 작성 규칙을 거부해야 합니다.
    @Test
    void validatorRejectsLeakedAuthoringRule() throws Exception {
        ObjectNode root = catalogJson();
        ((ObjectNode) root.withArray("recipes").get(0).withArray("steps").get(0))
                .put("instruction", "청양고추가 재료 목록에 표시된 경우에만 썬다.");

        assertThatThrownBy(() -> service.validateCatalog(toCatalog(root)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("작성 규칙");
    }

    // 총 조리 시간이 단계별 시간 합계보다 짧으면 거부해야 합니다.
    @Test
    void validatorRejectsTotalTimeShorterThanSequentialSteps() throws Exception {
        ObjectNode root = catalogJson();
        ((ObjectNode) root.withArray("recipes").get(0)).put("cookingTime", 35);

        assertThatThrownBy(() -> service.validateCatalog(toCatalog(root)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("단계별 최대 시간 합계");
    }

    // 특정 맵기 프로필에서 빠진 재료를 그 프로필의 조리 설명이 언급하면 거부해야 합니다.
    @Test
    void validatorRejectsProfileThatDisplaysAnUnusedConditionalIngredient() throws Exception {
        ObjectNode root = catalogJson();
        ObjectNode recipe = (ObjectNode) root.withArray("recipes").get(0);
        ((ObjectNode) recipe.withArray("steps").get(0)).remove("instructionOverrides");
        ((ObjectNode) recipe.withArray("steps").get(4)).remove("instructionOverrides");

        assertThatThrownBy(() -> service.validateCatalog(toCatalog(root)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("활성 재료를 사용하지 않습니다")
                .hasMessageContaining("청양고추");
    }

    // 실제 카탈로그 JSON을 수정 가능한 트리로 읽습니다(검증 실패 상황을 만들기 위해).
    private ObjectNode catalogJson() throws Exception {
        return (ObjectNode) new ObjectMapper().readTree(
                getClass().getClassLoader().getResourceAsStream("recipes/approved-recipes.json"));
    }

    private ApprovedRecipeCatalogDefinition toCatalog(ObjectNode root) {
        return new ObjectMapper().findAndRegisterModules()
                .convertValue(root, ApprovedRecipeCatalogDefinition.class);
    }

    // 테스트용 APPROVED 상태 레시피 엔티티를 만듭니다.
    private Recipe approvedRecipe(Long id, String key, int version) {
        Recipe recipe = new Recipe();
        recipe.setId(id);
        recipe.setCatalogKey(key);
        recipe.setCatalogVersion(version);
        recipe.setBaseServings(2);
        recipe.setApprovalStatus(RecipeApprovalStatus.APPROVED);
        return recipe;
    }
}
