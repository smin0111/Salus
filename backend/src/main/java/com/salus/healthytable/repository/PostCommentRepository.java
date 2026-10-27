package com.salus.healthytable.repository;

import com.salus.healthytable.domain.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@link PostComment} 엔티티의 DB 접근 인터페이스입니다.
 * 예전 커뮤니티 API는 제거했고, 표가 남아 있는 동안 회원 탈퇴 정리와 계정 화면의 건수 집계에만 씁니다.
 *
 * 삭제는 행을 불러와 하나씩 지우지 않고 DELETE 한 문장으로 합니다. 부모 댓글을 지우면 DB가 답글을 FK(CASCADE)로 먼저 지우므로,
 * 하나씩 지우면 이미 사라진 답글을 다시 지우려다 실패합니다(탈퇴 500).
 */
@Repository
public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

    long countByUserId(Long userId);

    @Modifying
    @Query("DELETE FROM PostComment c WHERE c.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    // 여러 게시글을 한꺼번에 삭제할 때 그 게시글들에 달린 댓글도 함께 삭제합니다.
    @Modifying
    @Query("DELETE FROM PostComment c WHERE c.postId IN :postIds")
    void deleteByPostIdIn(@Param("postIds") List<Long> postIds);
}
