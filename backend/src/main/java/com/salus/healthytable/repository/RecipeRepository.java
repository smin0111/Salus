package com.salus.healthytable.repository;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.RecipeApprovalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@link Recipe} 엔티티의 DB 접근 인터페이스입니다.
 *
 * 사용자에게 보여 주는 검색 쿼리는 APPROVED(검수 완료) 레시피만 대상으로 합니다.
 * 검수되지 않은 레시피가 신뢰할 수 있는 근거처럼 노출되지 않게 하기 위해서입니다.
 */
@Repository
public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    // ID 목록으로 레시피 조회
    List<Recipe> findByIdIn(List<Long> ids);

    // 사용자에게 제공할 레시피 검색은 승인된 카탈로그만 대상으로 한다.
    @Query("SELECT r FROM Recipe r WHERE r.approvalStatus = com.salus.healthytable.domain.RecipeApprovalStatus.APPROVED AND r.title LIKE CONCAT('%', :title, '%')")
    List<Recipe> findByTitleContaining(@Param("title") String title);

    // 검수 상태별 레시피를 페이지 단위로 조회합니다(Pageable로 페이지 번호/크기 지정).
    Page<Recipe> findByApprovalStatus(RecipeApprovalStatus approvalStatus, Pageable pageable);

    // 승인 카탈로그 키로 조회합니다. 카탈로그 JSON을 DB에 반영(동기화)할 때 사용합니다.
    Optional<Recipe> findByCatalogKey(String catalogKey);

    Optional<Recipe> findFirstByTitle(String title);

    // 특정 상태(주로 APPROVED)인 경우에만 레시피 단건을 반환합니다.
    Optional<Recipe> findByIdAndApprovalStatus(Long id, RecipeApprovalStatus approvalStatus);

    // nativeQuery = true: JPQL이 아닌 MySQL SQL을 그대로 실행합니다(JSON_UNQUOTE 같은 MySQL 함수 사용).
    // 정렬은 제목 일치 → 설명 일치 → 재료 일치 순으로 관련도가 높은 결과를 먼저 보여 줍니다.
    // 제목, 설명, 재료 JSON 문자열을 함께 검색한다.
    @Query(value = """
            SELECT *
            FROM recipes
            WHERE approval_status = 'APPROVED'
              AND (title LIKE CONCAT('%', :keyword, '%')
               OR description LIKE CONCAT('%', :keyword, '%')
               OR JSON_UNQUOTE(ingredients) LIKE CONCAT('%', :keyword, '%'))
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
