package com.salus.healthytable.repository;

import com.salus.healthytable.domain.GeneratedRecipe;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * LLM 레시피 생성 감사 기록({@link GeneratedRecipe})의 DB 접근 인터페이스입니다.
 */
public interface GeneratedRecipeRepository extends JpaRepository<GeneratedRecipe, Long> {
    List<GeneratedRecipe> findByTitle(String title);
}
