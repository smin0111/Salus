package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recommendation;
import com.salus.healthytable.dto.RecommendationDTO;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.repository.RecommendationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RecommendationService} 테스트입니다.
 */
class RecommendationServiceTest {

    private final RecipeRepository recipeRepository = mock(RecipeRepository.class);
    private final RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
    private final RecommendationService service = new RecommendationService(
            recipeRepository,
            recommendationRepository);

    // 냉장고 기능을 없애 추천 근거가 없으므로, 이전 추천은 지우고 아무 레시피도 추천하지 않아야 합니다.
    @Test
    void generateRecommendationsClearsOldRecordsAndRecommendsNothingWithoutBasis() {
        assertThat(service.generateRecommendations(1L)).isEmpty();

        verify(recommendationRepository).deleteByUserId(1L);
        verify(recommendationRepository, never()).save(any(Recommendation.class));
        verifyNoInteractions(recipeRepository);
    }

    // 저장된 추천이 없으면 빈 목록을 돌려줍니다.
    @Test
    void getRecommendationsReturnsEmptyWhenNothingStored() {
        when(recommendationRepository.findByUserIdOrderByScoreDesc(1L)).thenReturn(List.of());

        assertThat(service.getRecommendations(1L)).isEmpty();
    }

    // 추천된 레시피가 삭제되어 없어도 "알 수 없는 레시피"로 안전하게 응답해야 합니다.
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

    // 사용자 ID가 null이면 DB 조회 전에 거부해야 합니다.
    @Test
    void generateRecommendationsRejectsNullUserIdBeforeRepositoryLookup() {
        assertThatThrownBy(() -> service.generateRecommendations(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용자 정보가 필요합니다.");

        verifyNoInteractions(recipeRepository, recommendationRepository);
    }
}
