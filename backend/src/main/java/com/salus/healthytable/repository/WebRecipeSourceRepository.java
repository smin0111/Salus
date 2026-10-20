package com.salus.healthytable.repository;

import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.domain.WebRecipeSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WebRecipeSourceRepository extends JpaRepository<WebRecipeSource, Long> {

    // 기간이 남았고 검토에서 제외(REJECTED)되지 않은 근거를 품질 점수 순으로 찾습니다.
    List<WebRecipeSource> findByCacheKeyAndReviewStatusNotAndExpiresAtAfterOrderByQualityScoreDesc(
            String cacheKey, RecipeApprovalStatus excludedStatus, LocalDateTime now);

    Optional<WebRecipeSource> findByCacheKeyAndSourceUrl(String cacheKey, String sourceUrl);

    // 요리 이름(캐시 키 앞부분)으로 기간이 남은 근거가 있는지만 봅니다.
    boolean existsByCacheKeyStartingWithAndReviewStatusNotAndExpiresAtAfter(
            String cacheKeyPrefix, RecipeApprovalStatus excludedStatus, LocalDateTime now);
}
