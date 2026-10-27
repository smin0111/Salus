package com.salus.healthytable.repository;

import com.salus.healthytable.domain.CommunityPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@link CommunityPost} 엔티티의 DB 접근 인터페이스입니다.
 * 예전 커뮤니티 API는 제거했고, 표가 남아 있는 동안 회원 탈퇴 정리와 계정 화면의 건수 집계에만 씁니다.
 */
@Repository
public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {

    // 탈퇴 시 내 게시글에 달린 다른 사람의 댓글·좋아요를 먼저 지우려고 게시글 ID를 모읍니다.
    List<CommunityPost> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
