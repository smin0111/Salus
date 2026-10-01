package com.salus.healthytable.controller;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.dto.RecipeDTO;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.GeminiService;
import com.salus.healthytable.service.ChatRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/**
 * 레시피 API(/api/recipes)입니다.
 * 승인된 레시피 목록 조회와, 입력한 재료 기반 AI 레시피 추천을 제공합니다.
 */
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

    // @RequiredArgsConstructor 대신 생성자를 직접 작성한 형태입니다. 동작(생성자 주입)은 동일합니다.
    public RecipeController(RecipeRepository recipeRepository,
            GeminiService geminiService,
            AuthenticatedUserProvider authenticatedUserProvider,
            ChatRateLimitService chatRateLimitService) {
        this.recipeRepository = recipeRepository;
        this.geminiService = geminiService;
        this.authenticatedUserProvider = authenticatedUserProvider;
        this.chatRateLimitService = chatRateLimitService;
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

    /**
     * 재료 목록과 건강 참고 정보를 받아 AI 추천 레시피를 문자열로 반환합니다.
     */
    @PostMapping("/recommend")
    public Mono<String> recommendRecipe(@Valid @RequestBody RecommendationRequest request, HttpServletRequest servletRequest) {
        // Validation을 거치면서 기본적인 null, 리스트 크기 및 데이터 길이 유효성이 모두 통과되었습니다.
        // 남은 작업은 AI 프롬프트에 들어갈 재료 목록의 중복 제거(distinct) 및 앞뒤 공백 정제(trim)입니다.
        List<String> ingredients = request.getIngredients().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(ingredient -> !ingredient.isBlank())
                .distinct()
                .toList();

        // AI 프롬프트에 들어갈 건강 참고 맥락 정보 정제
        String healthContext = request.getHealthContext() == null || request.getHealthContext().isBlank()
                ? "None"
                : request.getHealthContext().trim();

        // 추천 API도 AI 호출을 만들 수 있으므로 채팅과 같은 Rate Limit 정책을 공유합니다.
        // 로그인 사용자는 userId 기준, 게스트는 IP 기준으로 제한해 비용 폭증을 줄입니다.
        chatRateLimitService.checkAllowed(authenticatedUserProvider.getCurrentUserId(), servletRequest);
        return geminiService.getRecipeRecommendation(ingredients, healthContext);
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

    /**
     * 레시피 추천 요청 본문입니다. 리스트 원소 타입에도 검증 어노테이션을 붙여 재료 하나하나의 길이를 검사합니다.
     */
    @Data
    public static class RecommendationRequest {
        @NotNull(message = "추천에 사용할 재료를 1개 이상 입력해 주세요.")
        @Size(min = 1, message = "추천에 사용할 재료를 1개 이상 입력해 주세요.")
        @Size(max = 20, message = "재료는 최대 20개까지 입력할 수 있습니다.")
        private List<@NotBlank(message = "추천에 사용할 재료를 1개 이상 입력해 주세요.") @Size(max = 80, message = "재료 이름은 80자 이하로 입력해 주세요.") String> ingredients;

        @Size(max = 1000, message = "건강 참고 내용은 1000자 이하로 입력해 주세요.")
        private String healthContext;
    }
}
