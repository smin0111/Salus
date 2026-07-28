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

    // 특정 게시글의 좋아요 개수
    long countByPostId(Long postId);

    // 특정 사용자가 특정 게시글에 좋아요를 눌렀는지 확인
    boolean existsByPostIdAndUserId(Long postId, Long userId);

    // 특정 사용자의 특정 게시글 좋아요 조회
    Optional<PostLike> findByPostIdAndUserId(Long postId, Long userId);

    // 특정 게시글과 사용자의 좋아요 삭제
    void deleteByPostIdAndUserId(Long postId, Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);

    void deleteByPostIdIn(java.util.List<Long> postIds);

    @Query("SELECT l.postId, COUNT(l) FROM PostLike l WHERE l.postId IN :postIds GROUP BY l.postId")
    List<Object[]> countLikesByPostIds(@Param("postIds") List<Long> postIds);

    @Query("SELECT l.postId FROM PostLike l WHERE l.postId IN :postIds AND l.userId = :userId")
    List<Long> findLikedPostIdsByPostIdsAndUserId(@Param("postIds") List<Long> postIds, @Param("userId") Long userId);
}
