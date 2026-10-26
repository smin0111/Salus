package com.salus.healthytable.repository;

import com.salus.healthytable.domain.FeedLike;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FeedLikeRepository extends JpaRepository<FeedLike, Long> {

    Optional<FeedLike> findByPostIdAndUserId(Long postId, Long userId);

    @Modifying
    @Query("DELETE FROM FeedLike l WHERE l.postId = :postId AND l.userId = :userId")
    int deleteByPostAndUser(@Param("postId") Long postId, @Param("userId") Long userId);

    long countByPostId(Long postId);

    /** 공유본별 좋아요 수(목록에서 한 번에 조회). */
    @Query("SELECT l.postId AS postId, COUNT(l) AS count FROM FeedLike l WHERE l.postId IN :postIds GROUP BY l.postId")
    List<PostCount> countByPostIds(@Param("postIds") Collection<Long> postIds);

    /** 보는 사람이 좋아요한 공유본 ID(목록에서 한 번에 조회). */
    @Query("SELECT l.postId FROM FeedLike l WHERE l.userId = :userId AND l.postId IN :postIds")
    List<Long> findLikedPostIds(@Param("userId") Long userId, @Param("postIds") Collection<Long> postIds);

    /**
     * 인기순: since 이후에 받은 좋아요 수로 정렬합니다(취소한 좋아요는 행이 없어 빠지고, 다시 누르면 새 시각으로 셉니다).
     * 동점이면 가장 최근 좋아요 시각, 그다음 공유본 ID 내림차순입니다. 개수 제한은 호출자가 Pageable로 줍니다.
     * 관리자가 숨긴 공유본은 집계에서 빠집니다(30개 자리를 차지하지 않음).
     */
    @Query("""
            SELECT l.postId AS postId, COUNT(l) AS count, MAX(l.createdAt) AS lastLikedAt
            FROM FeedLike l, FeedPost p WHERE p.id = l.postId AND p.hidden = false AND l.createdAt >= :since
            GROUP BY l.postId
            ORDER BY COUNT(l) DESC, MAX(l.createdAt) DESC, l.postId DESC
            """)
    List<PopularRow> findPopularSince(@Param("since") LocalDateTime since, Pageable pageable);

    interface PostCount {
        Long getPostId();

        Long getCount();
    }

    interface PopularRow {
        Long getPostId();

        Long getCount();

        LocalDateTime getLastLikedAt();
    }
}
