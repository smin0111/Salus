package com.salus.healthytable.repository;

import com.salus.healthytable.domain.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    // 상세 화면이나 좋아요 토글 직후처럼 게시글 1개만 다룰 때 사용하는 단건 집계입니다.
    long countByPostId(Long postId);

    // 단건 권한/상태 확인에는 exists 쿼리가 충분하지만, 목록에서는 반복 호출하면 N+1이 됩니다.
    boolean existsByPostIdAndUserId(Long postId, Long userId);

    // 좋아요 취소는 실제 Entity를 삭제해야 하므로 Optional로 현재 좋아요 row를 조회합니다.
    Optional<PostLike> findByPostIdAndUserId(Long postId, Long userId);

    // 사용자 요청으로 좋아요를 취소할 때 사용하는 단건 삭제 메서드입니다.
    void deleteByPostIdAndUserId(Long postId, Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);

    void deleteByPostIdIn(java.util.List<Long> postIds);

    // 목록 조회에서는 게시글마다 countByPostId를 호출하지 않고 한 번에 집계합니다.
    // 반환값은 [postId, count] 형태라 Service에서 Map으로 바꿔 DTO 조립에 사용합니다.
    @Query("SELECT l.postId, COUNT(l) FROM PostLike l WHERE l.postId IN :postIds GROUP BY l.postId")
    List<Object[]> countLikesByPostIds(@Param("postIds") List<Long> postIds);

    // 현재 사용자가 좋아요한 게시글 ID만 한 번에 가져오면 isLiked 계산을 메모리에서 끝낼 수 있습니다.
    // 게시글 목록 크기만큼 existsByPostIdAndUserId를 반복하지 않는 것이 핵심입니다.
    @Query("SELECT l.postId FROM PostLike l WHERE l.postId IN :postIds AND l.userId = :userId")
    List<Long> findLikedPostIdsByPostIdsAndUserId(@Param("postIds") List<Long> postIds, @Param("userId") Long userId);
}
