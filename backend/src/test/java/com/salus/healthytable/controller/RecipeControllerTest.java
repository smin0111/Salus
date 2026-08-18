package com.salus.healthytable.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.dto.RecipeDTO;
import com.salus.healthytable.exception.GlobalExceptionHandler;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.ChatRateLimitService;
import com.salus.healthytable.service.GeminiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecipeControllerTest {

    private RecipeRepository recipeRepository;
    private GeminiService geminiService;
    private AuthenticatedUserProvider authenticatedUserProvider;
    private ChatRateLimitService chatRateLimitService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        geminiService = mock(GeminiService.class);
        authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
        chatRateLimitService = mock(ChatRateLimitService.class);

        RecipeController controller = new RecipeController(
                recipeRepository,
                geminiService,
                authenticatedUserProvider,
                chatRateLimitService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(
                        new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()
                )
                .build();

        objectMapper = new ObjectMapper();
    }

    @Test
    void getRecipesReturnsLatestRecipeDtosWithLimit() throws Exception {
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
        when(recipeRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(recipe)));

        mockMvc.perform(get("/api/recipes")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("토마토 파스타"))
                .andExpect(jsonPath("$[0].ingredients[0]").value("통밀면"))
                .andExpect(jsonPath("$[0].ingredients[1]").value("토마토소스"))
                .andExpect(jsonPath("$[0].steps[0]").value("면을 삶습니다."));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(recipeRepository).findAll(pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void getRecipesRejectsInvalidLimitBeforeRepositoryCall() throws Exception {
        mockMvc.perform(get("/api/recipes")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("레시피 조회 개수는 1부터 50 사이로 입력해 주세요."));

        verifyNoInteractions(recipeRepository);
    }

    @Test
    void recommendRecipeNormalizesRequestBeforeCallingAiService() throws Exception {
        RecipeController.RecommendationRequest request = new RecipeController.RecommendationRequest();
        request.setIngredients(List.of(" 양파 ", "두부", "양파"));
        request.setHealthContext(" 저염식 ");

        when(authenticatedUserProvider.getCurrentUserId()).thenReturn(Optional.of(7L));
        when(geminiService.getRecipeRecommendation(List.of("양파", "두부"), "저염식"))
                .thenReturn(Mono.just("추천 결과"));

        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(post("/api/recipes/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string("추천 결과"));

        verify(chatRateLimitService).checkAllowed(eq(Optional.of(7L)), any());
        verify(geminiService).getRecipeRecommendation(List.of("양파", "두부"), "저염식");
    }

    @Test
    void recommendRecipeDefaultsBlankHealthContext() throws Exception {
        RecipeController.RecommendationRequest request = new RecipeController.RecommendationRequest();
        request.setIngredients(List.of("달걀"));
        request.setHealthContext(" ");

        when(authenticatedUserProvider.getCurrentUserId()).thenReturn(Optional.empty());
        when(geminiService.getRecipeRecommendation(List.of("달걀"), "None"))
                .thenReturn(Mono.just("추천 결과"));

        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(post("/api/recipes/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string("추천 결과"));

        verify(chatRateLimitService).checkAllowed(eq(Optional.empty()), any());
        verify(geminiService).getRecipeRecommendation(List.of("달걀"), "None");
    }

    @Test
    void recommendRecipeRejectsNullRequestBeforeCallingAiService() throws Exception {
        mockMvc.perform(post("/api/recipes/recommend")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 본문 JSON 형식이 올바르지 않습니다."));

        verifyNoInteractions(geminiService, authenticatedUserProvider, chatRateLimitService);
    }

    @Test
    void recommendRecipeRejectsBlankIngredientsBeforeCallingAiService() throws Exception {
        RecipeController.RecommendationRequest request = new RecipeController.RecommendationRequest();
        request.setIngredients(Arrays.asList(" ", null));

        mockMvc.perform(post("/api/recipes/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("추천에 사용할 재료를 1개 이상 입력해 주세요."));

        verifyNoInteractions(geminiService, authenticatedUserProvider, chatRateLimitService);
    }

    @Test
    void recommendRecipeRejectsTooManyIngredientsBeforeCallingAiService() throws Exception {
        RecipeController.RecommendationRequest request = new RecipeController.RecommendationRequest();
        request.setIngredients(IntStream.rangeClosed(1, 21)
                .mapToObj(index -> "재료" + index)
                .toList());

        mockMvc.perform(post("/api/recipes/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("재료는 최대 20개까지 입력할 수 있습니다."));

        verifyNoInteractions(geminiService, authenticatedUserProvider, chatRateLimitService);
    }

    @Test
    void recommendRecipeRejectsLongHealthContextBeforeCallingAiService() throws Exception {
        RecipeController.RecommendationRequest request = new RecipeController.RecommendationRequest();
        request.setIngredients(List.of("양파"));
        request.setHealthContext("a".repeat(1001));

        mockMvc.perform(post("/api/recipes/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("건강 참고 내용은 1000자 이하로 입력해 주세요."));

        verifyNoInteractions(geminiService, authenticatedUserProvider, chatRateLimitService);
    }
}
