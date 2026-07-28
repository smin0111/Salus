package com.salus.healthytable.repository;

import com.salus.healthytable.domain.RecipeShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecipeShareRepository extends JpaRepository<RecipeShare, Long> {

    // 최신순으로 공유된 레시피 조회
    List<RecipeShare> findAllByOrderByCreatedAtDesc();

    // Public으로 공유된 레시피만 조회
    List<RecipeShare> findByVisibilityOrderByCreatedAtDesc(String visibility);

    // 특정 사용자가 공유한 레시피 조회
    List<RecipeShare> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
