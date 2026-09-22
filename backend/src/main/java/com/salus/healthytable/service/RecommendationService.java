package com.salus.healthytable.service;

import com.salus.healthytable.domain.*;
import com.salus.healthytable.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.salus.healthytable.dto.RecommendationDTO;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 냉장고 재료와 건강 프로필을 바탕으로 커뮤니티 추천 레시피를 계산하는 서비스입니다.
 *
 * 점수 계산은 단순 규칙입니다: 냉장고 재료와 겹치는 레시피 재료 1건당 10점.
 * 주의: 여기의 알레르기 제외는 문자열 포함 비교만 사용하며, 레시피 생성 경로의 AllergenMatcher(파생 재료 사전 포함)를 거치지 않습니다.
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final RecipeRepository recipeRepository;
    private final FridgeItemRepository fridgeItemRepository;
    private final HealthProfileRepository healthProfileRepository;
    private final RecommendationRepository recommendationRepository;

    /**
     * 사용자의 냉장고와 건강 정보를 기반으로 추천 생성
     */
    @Transactional
    public List<Recommendation> generateRecommendations(Long userId) {
        validateUserId(userId);

        // 1. 데이터 준비
        List<Recipe> allRecipes = recipeRepository.findAll();
        List<FridgeItem> fridgeItems = fridgeItemRepository.findByUserId(userId);
        HealthProfile healthProfile = healthProfileRepository.findByUserId(userId).orElse(null);

        List<String> fridgeItemNames = fridgeItems.stream()
                .map(FridgeItem::getName)
                .collect(Collectors.collectingAndThen(Collectors.toList(), this::normalizeValues));

        List<String> allergies = (healthProfile != null && healthProfile.getAllergies() != null)
                ? normalizeValues(healthProfile.getAllergies())
                : new ArrayList<>();

        // 추천을 다시 계산하므로 이전 추천 기록을 먼저 지웁니다.
        // 기존 추천 삭제
        recommendationRepository.deleteByUserId(userId);

        List<Recommendation> recommendations = new ArrayList<>();

        for (Recipe recipe : allRecipes) {
            double score = calculateScore(recipe, fridgeItemNames, allergies);

            // 점수가 0 이하(겹치는 재료 없음 또는 알레르기 재료 포함)인 레시피는 추천하지 않습니다.
            if (score > 0) {
                Recommendation recommendation = new Recommendation();
                recommendation.setUserId(userId);
                recommendation.setRecipeId(recipe.getId());
                recommendation.setScore(score);
                recommendation.setReason(generateReason(recipe, fridgeItemNames));
                recommendations.add(recommendationRepository.save(recommendation));
            }
        }

        return recommendations;
    }

    /**
     * 레시피 하나의 추천 점수를 계산합니다.
     * 알레르기 재료 이름이 포함된 재료가 하나라도 있으면 즉시 -100을 반환해 추천에서 빠지게 합니다.
     */
    private double calculateScore(Recipe recipe, List<String> userIngredients, List<String> allergies) {
        double score = 0;
        List<String> recipeIngredients = normalizeValues(recipe.getIngredients());

        if (recipeIngredients.isEmpty())
            return 0;

        // 알러지 필터링
        for (String allergy : allergies) {
            for (String ri : recipeIngredients) {
                if (containsIgnoreCase(ri, allergy)) {
                    return -100; // 알러지 유발 음식은 배제
                }
            }
        }

        // 재료 매칭 점수 (개당 10점)
        for (String recipeIng : recipeIngredients) {
            for (String userIng : userIngredients) {
                if (containsIgnoreCase(recipeIng, userIng) || containsIgnoreCase(userIng, recipeIng)) {
                    score += 10;
                }
            }
        }

        return score;
    }

    // 겹치는 냉장고 재료 중 최대 2개를 넣어 추천 이유 문장을 만듭니다.
    private String generateReason(Recipe recipe, List<String> userIngredients) {
        Set<String> matched = new LinkedHashSet<>();
        List<String> recipeIngredients = normalizeValues(recipe.getIngredients());

        for (String recipeIng : recipeIngredients) {
            for (String userIng : userIngredients) {
                if (containsIgnoreCase(recipeIng, userIng) || containsIgnoreCase(userIng, recipeIng)) {
                    matched.add(userIng);
                    break;
                }
            }
        }

        if (matched.isEmpty()) {
            return "건강 정보를 고려한 추천입니다.";
        }

        return "냉장고 속 " + String.join(", ", matched.stream().limit(2).collect(Collectors.toList())) + "을(를) 활용한 레시피예요!";
    }

    /**
     * 저장된 추천을 점수순으로 조회합니다. 아직 추천이 없으면 먼저 계산해서 저장한 뒤 조회합니다.
     */
    public List<RecommendationDTO> getRecommendations(Long userId) {
        validateUserId(userId);

        List<Recommendation> results = recommendationRepository.findByUserIdOrderByScoreDesc(userId);
        if (results.isEmpty()) {
            generateRecommendations(userId);
            results = recommendationRepository.findByUserIdOrderByScoreDesc(userId);
        }

        if (results.isEmpty())
            return new ArrayList<>();

        List<Long> recipeIds = results.stream().map(Recommendation::getRecipeId).collect(Collectors.toList());
        Map<Long, Recipe> recipeMap = recipeRepository.findByIdIn(recipeIds).stream()
                .collect(Collectors.toMap(Recipe::getId, r -> r));

        return results.stream().map(reco -> {
            Recipe recipe = recipeMap.get(reco.getRecipeId());
            return new RecommendationDTO(
                    reco.getId(),
                    reco.getRecipeId(),
                    recipe != null ? recipe.getTitle() : "알 수 없는 레시피",
                    recipe != null ? recipe.getDescription() : "",
                    recipe != null ? recipe.getImageUrl() : "",
                    reco.getScore(),
                    reco.getReason());
        }).collect(Collectors.toList());
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자 정보가 필요합니다.");
        }
    }

    // null/빈 값을 제거하고 공백을 정리한 뒤 중복 없이 순서를 유지한 목록으로 만듭니다.
    private List<String> normalizeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        return values.stream()
                .filter(Objects::nonNull)
                .map(value -> value.replaceAll("\\s+", " ").trim())
                .filter(value -> !value.isBlank())
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
    }

    // 대소문자를 무시하고 source 안에 keyword가 포함되는지 확인합니다.
    private boolean containsIgnoreCase(String source, String keyword) {
        if (source == null || keyword == null || source.isBlank() || keyword.isBlank()) {
            return false;
        }
        return source.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }
}
