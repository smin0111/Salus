package com.salus.healthytable.controller;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.dto.RecipeDTO;
import com.salus.healthytable.repository.RecipeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 레시피 API(/api/recipes)입니다.
 * 승인된 레시피 목록 조회를 제공합니다.
 * 재료 기반 AI 추천(/recommend)은 2026-11-06에 없앴습니다. 등록 알레르기 확인과 레시피 검증 없이 LLM 답을 그대로
 * 돌려주는 경로였고 앱에서도 쓰지 않았습니다. 레시피는 채팅(알레르기 차단·출처 확인)으로만 받습니다.
 */
@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private static final int DEFAULT_RECIPE_LIMIT = 10;
    private static final int MAX_RECIPE_LIMIT = 50;

    private final RecipeRepository recipeRepository;

    public RecipeController(RecipeRepository recipeRepository) {
        this.recipeRepository = recipeRepository;
    }

    /**
     * 승인(APPROVED)된 레시피를 최신순으로 조회합니다. 검수되지 않은 레시피는 노출하지 않습니다.
     */
    @GetMapping
    public List<RecipeDTO> getRecipes(@RequestParam(defaultValue = "" + DEFAULT_RECIPE_LIMIT) int limit) {
        int normalizedLimit = normalizeRecipeLimit(limit);
        return recipeRepository.findByApprovalStatus(RecipeApprovalStatus.APPROVED, PageRequest.of(
                0,
                normalizedLimit,
                Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(this::toRecipeDTO)
                .toList();
    }

    // 조회 개수가 1~50 범위인지 확인합니다.
    private int normalizeRecipeLimit(int limit) {
        if (limit < 1 || limit > MAX_RECIPE_LIMIT) {
            throw new IllegalArgumentException("레시피 조회 개수는 1부터 50 사이로 입력해 주세요.");
        }
        return limit;
    }

    // 엔티티를 응답 DTO로 변환합니다. servings에는 레시피의 기준 인분 수(baseServings)를 사용합니다.
    private RecipeDTO toRecipeDTO(Recipe recipe) {
        return new RecipeDTO(
                recipe.getId(),
                recipe.getTitle(),
                recipe.getDescription(),
                safeList(recipe.getIngredients()),
                safeList(recipe.getSteps()),
                recipe.getBaseServings(),
                recipe.getCalories(),
                recipe.getCaloriesPerServing(),
                recipe.getDifficulty(),
                recipe.getCookingTime(),
                recipe.getAverageRating(),
                recipe.getImageUrl(),
                recipe.getCreatedAt());
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
