package com.salus.healthytable.repository;

import com.salus.healthytable.domain.Recipe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    // ID 목록으로 레시피 조회
    List<Recipe> findByIdIn(List<Long> ids);

    // 제목으로 검색
    List<Recipe> findByTitleContaining(String title);

    // 제목, 설명, 재료 JSON 문자열을 함께 검색한다.
    @Query(value = """
            SELECT *
            FROM recipes
            WHERE title LIKE CONCAT('%', :keyword, '%')
               OR description LIKE CONCAT('%', :keyword, '%')
               OR JSON_UNQUOTE(ingredients) LIKE CONCAT('%', :keyword, '%')
            ORDER BY
                CASE
                    WHEN title LIKE CONCAT('%', :keyword, '%') THEN 0
                    WHEN description LIKE CONCAT('%', :keyword, '%') THEN 1
                    ELSE 2
                END,
                id
            LIMIT :limit
            """, nativeQuery = true)
    List<Recipe> searchByKeyword(@Param("keyword") String keyword, @Param("limit") int limit);
}
