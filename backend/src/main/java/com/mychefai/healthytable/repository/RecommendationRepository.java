package com.mychefai.healthytable.repository;

import com.mychefai.healthytable.domain.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    List<Recommendation> findByUserIdOrderByScoreDesc(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
