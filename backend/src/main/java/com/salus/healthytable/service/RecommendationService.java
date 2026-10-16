package com.salus.healthytable.service;

import com.salus.healthytable.domain.*;
import com.salus.healthytable.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.salus.healthytable.dto.RecommendationDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 커뮤니티 "나를 위한 추천" 레시피를 조회하는 서비스입니다.
 *
 * 추천은 냉장고 재료와 겹치는 레시피로 계산했지만, 냉장고 기능을 없애면서(2026-10-15) 추천 근거가 없어졌습니다.
 * 근거 없이 아무 레시피나 추천하지 않도록 지금은 추천을 만들지 않고 빈 목록을 돌려줍니다. 새 기준이 정해지면 여기서 계산합니다.
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final RecipeRepository recipeRepository;
    private final RecommendationRepository recommendationRepository;

    /**
     * 추천을 다시 계산합니다. 이전 추천 기록은 지우고, 현재는 추천 근거가 없어 새로 만들지 않습니다.
     */
    @Transactional
    public List<Recommendation> generateRecommendations(Long userId) {
        validateUserId(userId);
        recommendationRepository.deleteByUserId(userId);
        return List.of();
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
}
