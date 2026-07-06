package com.mychefai.healthytable.service;

import com.mychefai.healthytable.domain.FridgeItem;
import com.mychefai.healthytable.domain.HealthProfile;
import com.mychefai.healthytable.domain.Recipe;
import com.mychefai.healthytable.domain.Recommendation;
import com.mychefai.healthytable.dto.RecommendationDTO;
import com.mychefai.healthytable.repository.FridgeItemRepository;
import com.mychefai.healthytable.repository.HealthProfileRepository;
import com.mychefai.healthytable.repository.RecipeRepository;
import com.mychefai.healthytable.repository.RecommendationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecommendationServiceTest {

    private final RecipeRepository recipeRepository = mock(RecipeRepository.class);
    private final FridgeItemRepository fridgeItemRepository = mock(FridgeItemRepository.class);
    private final HealthProfileRepository healthProfileRepository = mock(HealthProfileRepository.class);
    private final RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
    private final RecommendationService service = new RecommendationService(
            recipeRepository,
            fridgeItemRepository,
            healthProfileRepository,
            recommendationRepository);

    @Test
    void generateRecommendationsExcludesAllergyRecipesAndNormalizesLegacyValues() {
        Recipe watermelonRecipe = recipe(10L, "수박화채", Arrays.asList("수박", "얼음"));
        Recipe tofuRecipe = recipe(20L, "두부 샐러드", Arrays.asList(" 두부 ", "채소"));
        FridgeItem tofu = fridgeItem(" 두부 ");
        FridgeItem blank = fridgeItem(" ");
        HealthProfile profile = new HealthProfile();
        profile.setAllergies(Arrays.asList(" ", "수박", null));

        when(recipeRepository.findAll()).thenReturn(List.of(watermelonRecipe, tofuRecipe));
        when(fridgeItemRepository.findByUserId(1L)).thenReturn(Arrays.asList(tofu, blank));
        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(recommendationRepository.save(any(Recommendation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<Recommendation> recommendations = service.generateRecommendations(1L);

        ArgumentCaptor<Recommendation> captor = ArgumentCaptor.forClass(Recommendation.class);
        verify(recommendationRepository).deleteByUserId(1L);
        verify(recommendationRepository).save(captor.capture());

        Recommendation saved = captor.getValue();
        assertThat(recommendations).containsExactly(saved);
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getRecipeId()).isEqualTo(20L);
        assertThat(saved.getScore()).isEqualTo(10.0);
        assertThat(saved.getReason()).contains("두부");
    }

    @Test
    void getRecommendationsMapsMissingRecipeSafely() {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(5L);
        recommendation.setRecipeId(99L);
        recommendation.setScore(10.0);
        recommendation.setReason("추천 사유");

        when(recommendationRepository.findByUserIdOrderByScoreDesc(1L)).thenReturn(List.of(recommendation));
        when(recipeRepository.findByIdIn(List.of(99L))).thenReturn(List.of());

        List<RecommendationDTO> response = service.getRecommendations(1L);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).getTitle()).isEqualTo("알 수 없는 레시피");
        assertThat(response.get(0).getReason()).isEqualTo("추천 사유");
    }

    @Test
    void generateRecommendationsRejectsNullUserIdBeforeRepositoryLookup() {
        assertThatThrownBy(() -> service.generateRecommendations(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용자 정보가 필요합니다.");

        verifyNoInteractions(
                recipeRepository,
                fridgeItemRepository,
                healthProfileRepository,
                recommendationRepository);
    }

    private Recipe recipe(Long id, String title, List<String> ingredients) {
        Recipe recipe = new Recipe();
        recipe.setId(id);
        recipe.setTitle(title);
        recipe.setIngredients(ingredients);
        return recipe;
    }

    private FridgeItem fridgeItem(String name) {
        FridgeItem item = new FridgeItem();
        item.setName(name);
        return item;
    }
}
