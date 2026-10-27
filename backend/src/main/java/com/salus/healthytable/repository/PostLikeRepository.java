package com.salus.healthytable.repository;

import com.salus.healthytable.domain.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@link PostLike} 엔티티의 DB 접근 인터페이스입니다.
 * 예전 커뮤니티 API는 제거했고, 표가 남아 있는 동안 회원 탈퇴 정리와 계정 화면의 건수 집계에만 씁니다.
 */
@Repository
public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);

    void deleteByPostIdIn(List<Long> postIds);
}
