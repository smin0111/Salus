package com.salus.healthytable.repository;

import com.salus.healthytable.domain.UserRecipe;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link UserRecipe} 엔티티의 DB 접근 인터페이스입니다. 모든 조회는 사용자 ID를 함께 걸어 남의 레시피를 읽지 않게 합니다.
 */
@Repository
public interface UserRecipeRepository extends JpaRepository<UserRecipe, Long> {
    Optional<UserRecipe> findByIdAndUserId(Long id, Long userId);

    Optional<UserRecipe> findByUserIdAndSourceCardId(Long userId, Long sourceCardId);

    // 최근 수정순 첫 페이지입니다(같은 시각이면 ID 큰 것부터).
    List<UserRecipe> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId, Pageable pageable);

    // 커서 다음 페이지: (updatedAt, id)가 커서보다 앞선(더 오래된) 것만 가져옵니다.
    @Query("""
            SELECT r FROM UserRecipe r
            WHERE r.userId = :userId
              AND (r.updatedAt < :updatedAt OR (r.updatedAt = :updatedAt AND r.id < :id))
            ORDER BY r.updatedAt DESC, r.id DESC
            """)
    List<UserRecipe> findPageAfter(@Param("userId") Long userId,
                                   @Param("updatedAt") LocalDateTime updatedAt,
                                   @Param("id") Long id,
                                   Pageable pageable);
}
