package com.mychefai.healthytable.repository;

import com.mychefai.healthytable.domain.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

    // 특정 게시글의 댓글 조회 (작성 시간 오름차순)
    List<PostComment> findByPostIdOrderByCreatedAtAsc(Long postId);

    // 특정 게시글의 댓글 개수
    long countByPostId(Long postId);

    // 특정 사용자가 작성한 댓글 조회
    List<PostComment> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);

    void deleteByPostIdIn(List<Long> postIds);

    @Query("SELECT c.postId, COUNT(c) FROM PostComment c WHERE c.postId IN :postIds GROUP BY c.postId")
    List<Object[]> countCommentsByPostIds(@Param("postIds") List<Long> postIds);
}
