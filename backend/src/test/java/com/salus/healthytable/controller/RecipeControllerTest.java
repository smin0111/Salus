package com.salus.healthytable.controller;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.ChatRateLimitService;
import com.salus.healthytable.service.GeminiService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletRequest;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecipeControllerTest {

    private final RecipeRepository recipeRepository = mock(RecipeRepository.class);
    private final GeminiService geminiService = mock(GeminiService.class);
    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final ChatRateLimitService chatRateLimitService = mock(ChatRateLimitService.class);
    private final RecipeController controller = new RecipeController(
            recipeRepository,
            geminiService,
            authenticatedUserProvider,
            chatRateLimitService);

    @Test
    void getRecipesReturnsLatestRecipeDtosWithLimit() {
        Recipe recipe = new Recipe();
        recipe.setId(1L);
        recipe.setTitle("토마토 파스타");
        recipe.setDescription("토마토소스로 만드는 파스타");
        recipe.setIngredients(List.of("통밀면", "토마토소스"));
        recipe.setSteps(List.of("면을 삶습니다.", "소스를 넣고 볶습니다."));
        recipe.setCalories(410);
        recipe.setDifficulty(2);
        recipe.setCookingTime(20);
        recipe.setAverageRating(4.5);
        recipe.setImageUrl("https://example.com/pasta.jpg");
        recipe.setCreatedAt(LocalDateTime.of(2026, 7, 4, 9, 0));
        when(recipeRepository.findAll(org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(recipe)));

        var response = controller.getRecipes(5);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).getTitle()).isEqualTo("토마토 파스타");
        assertThat(response.get(0).getIngredients()).containsExactly("통밀면", "토마토소스");
        assertThat(response.get(0).getSteps()).containsExactly("면을 삶습니다.", "소스를 넣고 볶습니다.");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(recipeRepository).findAll(pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void getRecipesRejectsInvalidLimitBeforeRepositoryCall() {
        assertThatThrownBy(() -> controller.getRecipes(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("레시피 조회 개수는 1부터 50 사이로 입력해 주세요.");

        verifyNoInteractions(recipeRepository);
    }

    @Test
    void recommendRecipeNormalizesRequestBeforeCallingAiService() {
        RecipeController.RecommendationRequest request = new RecipeController.RecommendationRequest();
        request.setIngredients(List.of(" 양파 ", "두부", "양파", " "));
        request.setHealthContext(" 저염식 ");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();

        when(authenticatedUserProvider.getCurrentUserId()).thenReturn(Optional.of(7L));
        when(geminiService.getRecipeRecommendation(List.of("양파", "두부"), "저염식"))
                .thenReturn(Mono.just("추천 결과"));

        String response = controller.recommendRecipe(request, servletRequest).block();

        assertThat(response).isEqualTo("추천 결과");
        verify(chatRateLimitService).checkAllowed(Optional.of(7L), servletRequest);
        verify(geminiService).getRecipeRecommendation(List.of("양파", "두부"), "저염식");
    }

    @Test
    void recommendRecipeDefaultsBlankHealthContext() {
        RecipeController.RecommendationRequest request = new RecipeController.RecommendationRequest();
        request.setIngredients(List.of("달걀"));
        request.setHealthContext(" ");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();

        when(authenticatedUserProvider.getCurrentUserId()).thenReturn(Optional.empty());
        when(geminiService.getRecipeRecommendation(List.of("달걀"), "None"))
                .thenReturn(Mono.just("추천 결과"));

        String response = controller.recommendRecipe(request, servletRequest).block();

        assertThat(response).isEqualTo("추천 결과");
        verify(chatRateLimitService).checkAllowed(Optional.empty(), servletRequest);
        verify(geminiService).getRecipeRecommendation(List.of("달걀"), "None");
    }

    @Test
    void recommendRecipeRejectsNullRequestBeforeCallingAiService() {
        assertThatThrownBy(() -> controller.recommendRecipe(null, new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("추천에 사용할 재료를 입력해 주세요.");

        verifyNoInteractions(geminiService, authenticatedUserProvider, chatRateLimitService);
    }

    @Test
    void recommendRecipeRejectsBlankIngredientsBeforeCallingAiService() {
        RecipeController.RecommendationRequest request = new RecipeController.RecommendationRequest();
        request.setIngredients(Arrays.asList(" ", null));

        assertThatThrownBy(() -> controller.recommendRecipe(request, new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("추천에 사용할 재료를 1개 이상 입력해 주세요.");

        verifyNoInteractions(geminiService, authenticatedUserProvider, chatRateLimitService);
    }

    @Test
    void recommendRecipeRejectsTooManyIngredientsBeforeCallingAiService() {
        RecipeController.RecommendationRequest request = new RecipeController.RecommendationRequest();
        request.setIngredients(IntStream.rangeClosed(1, 21)
                .mapToObj(index -> "재료" + index)
                .toList());

        assertThatThrownBy(() -> controller.recommendRecipe(request, new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재료는 최대 20개까지 입력할 수 있습니다.");

        verifyNoInteractions(geminiService, authenticatedUserProvider, chatRateLimitService);
    }

    @Test
    void recommendRecipeRejectsLongHealthContextBeforeCallingAiService() {
        RecipeController.RecommendationRequest request = new RecipeController.RecommendationRequest();
        request.setIngredients(List.of("양파"));
        request.setHealthContext("a".repeat(1001));

        assertThatThrownBy(() -> controller.recommendRecipe(request, new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("건강 참고 내용은 1000자 이하로 입력해 주세요.");

        verifyNoInteractions(geminiService, authenticatedUserProvider, chatRateLimitService);
    }
}
