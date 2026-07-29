package com.salus.healthytable.repository;

import com.salus.healthytable.domain.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

    // 댓글 목록처럼 실제 댓글 내용을 보여줄 때만 Entity 목록을 조회합니다.
    List<PostComment> findByPostIdOrderByCreatedAtAsc(Long postId);

    // 상세 화면처럼 게시글 1개만 다룰 때 사용하는 단건 댓글 수 집계입니다.
    long countByPostId(Long postId);

    // 마이페이지나 사용자 데이터 정리처럼 특정 사용자의 댓글을 다룰 때 사용합니다.
    List<PostComment> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);

    void deleteByPostIdIn(List<Long> postIds);

    // 게시글 목록에서 댓글 수를 붙일 때 countByPostId를 반복하면 N+1 문제가 생깁니다.
    // IN + GROUP BY 집계로 쿼리 수를 고정해 목록 조회 성능을 예측 가능하게 만듭니다.
    @Query("SELECT c.postId, COUNT(c) FROM PostComment c WHERE c.postId IN :postIds GROUP BY c.postId")
    List<Object[]> countCommentsByPostIds(@Param("postIds") List<Long> postIds);
}
