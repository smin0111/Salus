package com.mychefai.healthytable.repository;

import com.mychefai.healthytable.domain.CommunityPost;
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

    // Batch 조회용
    List<CommunityPost> findByIdIn(List<Long> ids);

    // 검색 (제목 또는 내용)
    List<CommunityPost> findByTitleContainingOrContentContainingOrderByCreatedAtDesc(String title, String content);
}
