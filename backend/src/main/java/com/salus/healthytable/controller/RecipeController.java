package com.salus.healthytable.controller;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.dto.RecipeDTO;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.GeminiService;
import com.salus.healthytable.service.ChatRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private static final int MAX_INGREDIENTS = 20;
    private static final int MAX_INGREDIENT_LENGTH = 80;
    private static final int MAX_HEALTH_CONTEXT_LENGTH = 1000;
    private static final int DEFAULT_RECIPE_LIMIT = 10;
    private static final int MAX_RECIPE_LIMIT = 50;

    private final RecipeRepository recipeRepository;
    private final GeminiService geminiService;
    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final ChatRateLimitService chatRateLimitService;

    public RecipeController(RecipeRepository recipeRepository,
            GeminiService geminiService,
            AuthenticatedUserProvider authenticatedUserProvider,
            ChatRateLimitService chatRateLimitService) {
        this.recipeRepository = recipeRepository;
        this.geminiService = geminiService;
        this.authenticatedUserProvider = authenticatedUserProvider;
        this.chatRateLimitService = chatRateLimitService;
    }

    @GetMapping
    public List<RecipeDTO> getRecipes(@RequestParam(defaultValue = "" + DEFAULT_RECIPE_LIMIT) int limit) {
        int normalizedLimit = normalizeRecipeLimit(limit);
        return recipeRepository.findAll(PageRequest.of(
                0,
                normalizedLimit,
                Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(this::toRecipeDTO)
                .toList();
    }

    @PostMapping("/recommend")
    public Mono<String> recommendRecipe(@RequestBody RecommendationRequest request, HttpServletRequest servletRequest) {
        List<String> ingredients = normalizeIngredients(request);
        String healthContext = normalizeHealthContext(request.getHealthContext());
        chatRateLimitService.checkAllowed(authenticatedUserProvider.getCurrentUserId(), servletRequest);
        return geminiService.getRecipeRecommendation(ingredients, healthContext);
    }

    private int normalizeRecipeLimit(int limit) {
        if (limit < 1 || limit > MAX_RECIPE_LIMIT) {
            throw new IllegalArgumentException("레시피 조회 개수는 1부터 50 사이로 입력해 주세요.");
        }
        return limit;
    }

    private RecipeDTO toRecipeDTO(Recipe recipe) {
        return new RecipeDTO(
                recipe.getId(),
                recipe.getTitle(),
                recipe.getDescription(),
                safeList(recipe.getIngredients()),
                safeList(recipe.getSteps()),
                recipe.getCalories(),
                recipe.getDifficulty(),
                recipe.getCookingTime(),
                recipe.getAverageRating(),
                recipe.getImageUrl(),
                recipe.getCreatedAt());
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private List<String> normalizeIngredients(RecommendationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("추천에 사용할 재료를 입력해 주세요.");
        }
        if (request.getIngredients() == null) {
            throw new IllegalArgumentException("추천에 사용할 재료를 1개 이상 입력해 주세요.");
        }

        List<String> ingredients = request.getIngredients().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(ingredient -> !ingredient.isBlank())
                .distinct()
                .toList();

        if (ingredients.isEmpty()) {
            throw new IllegalArgumentException("추천에 사용할 재료를 1개 이상 입력해 주세요.");
        }
        if (ingredients.size() > MAX_INGREDIENTS) {
            throw new IllegalArgumentException("재료는 최대 20개까지 입력할 수 있습니다.");
        }
        for (String ingredient : ingredients) {
            if (ingredient.length() > MAX_INGREDIENT_LENGTH) {
                throw new IllegalArgumentException("재료 이름은 80자 이하로 입력해 주세요.");
            }
        }

        return ingredients;
    }

    private String normalizeHealthContext(String healthContext) {
        if (healthContext == null || healthContext.isBlank()) {
            return "None";
        }

        String normalized = healthContext.trim();
        if (normalized.length() > MAX_HEALTH_CONTEXT_LENGTH) {
            throw new IllegalArgumentException("건강 참고 내용은 1000자 이하로 입력해 주세요.");
        }
        return normalized;
    }

    @Data
    public static class RecommendationRequest {
        private List<String> ingredients;
        private String healthContext;
    }
}
