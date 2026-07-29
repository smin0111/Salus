package com.salus.healthytable.repository;

import com.salus.healthytable.domain.CommunityPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {

    // 최신순으로 전체 게시글 조회
    List<CommunityPost> findAllByOrderByCreatedAtDesc();

    // 특정 사용자가 작성한 게시글 조회
    List<CommunityPost> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);

    // 특정 기간 이후 게시글 조회
    List<CommunityPost> findByCreatedAtAfterOrderByCreatedAtDesc(java.time.LocalDateTime date);

    // 게시글 목록에서 여러 게시글을 한 번에 다시 조회해야 할 때 사용합니다.
    List<CommunityPost> findByIdIn(List<Long> ids);

    // 검색 (제목 또는 내용)
    List<CommunityPost> findByTitleContainingOrContentContainingOrderByCreatedAtDesc(String title, String content);
}
