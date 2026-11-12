package com.salus.healthytable.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.dto.RecipeDTO;
import com.salus.healthytable.exception.GlobalExceptionHandler;
import com.salus.healthytable.repository.RecipeRepository;
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

/**
 * {@link RecipeController} 테스트입니다. MockMvc로 요청 검증과 응답 형식을 확인합니다.
 */
class RecipeControllerTest {

    private RecipeRepository recipeRepository;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);

        RecipeController controller = new RecipeController(recipeRepository);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(
                        new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()
                )
                .build();

        objectMapper = new ObjectMapper();
    }

    // 레시피 목록은 최신순 DTO로, 요청한 개수만큼 반환해야 합니다.
    @Test
    void getRecipesReturnsLatestRecipeDtosWithLimit() throws Exception {
        Recipe recipe = new Recipe();
        recipe.setId(1L);
        recipe.setTitle("토마토 파스타");
        recipe.setDescription("토마토소스로 만드는 파스타");
        recipe.setIngredients(List.of("통밀면", "토마토소스"));
        recipe.setSteps(List.of("면을 삶습니다.", "소스를 넣고 볶습니다."));
        recipe.setBaseServings(2);
        recipe.setCalories(410);
        recipe.setCaloriesPerServing(410);
        recipe.setDifficulty(2);
        recipe.setCookingTime(20);
        recipe.setAverageRating(4.5);
        recipe.setImageUrl("https://example.com/pasta.jpg");
        recipe.setCreatedAt(LocalDateTime.of(2026, 7, 4, 9, 0));
        when(recipeRepository.findByApprovalStatus(eq(RecipeApprovalStatus.APPROVED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(recipe)));

        mockMvc.perform(get("/api/recipes")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("토마토 파스타"))
                .andExpect(jsonPath("$[0].ingredients[0]").value("통밀면"))
                .andExpect(jsonPath("$[0].ingredients[1]").value("토마토소스"))
                .andExpect(jsonPath("$[0].steps[0]").value("면을 삶습니다."))
                .andExpect(jsonPath("$[0].servings").value(2))
                .andExpect(jsonPath("$[0].calories").value(410))
                .andExpect(jsonPath("$[0].caloriesPerServing").value(410));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(recipeRepository).findByApprovalStatus(eq(RecipeApprovalStatus.APPROVED), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    // 잘못된 조회 개수는 DB 조회 전에 400으로 거부해야 합니다.
    @Test
    void getRecipesRejectsInvalidLimitBeforeRepositoryCall() throws Exception {
        mockMvc.perform(get("/api/recipes")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("레시피 조회 개수는 1부터 50 사이로 입력해 주세요."));

        verifyNoInteractions(recipeRepository);
    }
}
