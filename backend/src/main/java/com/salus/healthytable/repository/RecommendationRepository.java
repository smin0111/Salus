package com.salus.healthytable.repository;

import com.salus.healthytable.domain.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@link Recommendation} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    // 추천 점수가 높은 순으로 사용자의 추천 기록을 조회합니다.
    List<Recommendation> findByUserIdOrderByScoreDesc(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
