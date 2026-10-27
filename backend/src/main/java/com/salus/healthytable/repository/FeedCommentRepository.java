package com.salus.healthytable.repository;

import com.salus.healthytable.domain.FeedComment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FeedCommentRepository extends JpaRepository<FeedComment, Long> {

    // 댓글 재전송 판별: 같은 사용자의 같은 요청 ID로 이미 저장한 댓글
    Optional<FeedComment> findByUserIdAndClientRequestId(Long userId, String clientRequestId);

    /**
     * 최상위 댓글 한 페이지(오래된 순, 커서 = 앞 페이지 마지막 ID). 보는 사람에게 보여 줄 것만 조회 조건에서 고릅니다:
     * 공개 댓글(지우지 않았고 숨기지 않음), 보는 사람이 쓴 숨긴 댓글(본인 조회 예외), 또는 보는 사람에게 보이는 답글
     * (공개 답글이나 보는 사람이 쓴 숨긴 답글, findVisibleReplies와 같은 기준)이 있어 자리표시가 필요한 삭제·숨김 댓글.
     * 보는 사람이 차단한 사람의 댓글은 공개 댓글이어도 보여 줄 답글이 있을 때만 자리표시로 남고, 차단한 사람의 답글은
     * "보여 줄 답글"로 치지 않습니다. 그래서 페이지를 나눈 뒤 걸러 빈 페이지가 생기지 않습니다.
     */
    @Query("""
            SELECT c FROM FeedComment c
            WHERE c.postId = :postId AND c.rootCommentId IS NULL AND c.id > :after
              AND ((c.status = 'ACTIVE' AND c.hidden = false
                    AND NOT EXISTS (SELECT b.id FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = c.userId))
                   OR (c.status = 'ACTIVE' AND c.userId = :viewerId)
                   OR EXISTS (SELECT r.id FROM FeedComment r
                              WHERE r.rootCommentId = c.id AND r.status = 'ACTIVE'
                                AND (r.hidden = false OR r.userId = :viewerId)
                                AND NOT EXISTS (SELECT b2.id FROM UserBlock b2
                                                WHERE b2.blockerId = :viewerId AND b2.blockedId = r.userId)))
            ORDER BY c.id ASC
            """)
    List<FeedComment> findVisibleRoots(@Param("postId") Long postId, @Param("viewerId") Long viewerId,
                                       @Param("after") Long after, Pageable pageable);

    /**
     * 답글 한 페이지(오래된 순). 공개 답글과 보는 사람이 쓴 숨긴 답글만 보여 주고, 삭제된 답글과 보는 사람이 차단한 사람의
     * 답글은 보내지 않습니다(그 답글에 다시 단 답글은 남고, 답한 대상 이름이 "차단한 사용자"로 나감).
     */
    @Query("""
            SELECT c FROM FeedComment c
            WHERE c.rootCommentId = :rootId AND c.id > :after AND c.status = 'ACTIVE'
              AND (c.hidden = false OR c.userId = :viewerId)
              AND NOT EXISTS (SELECT b.id FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = c.userId)
            ORDER BY c.id ASC
            """)
    List<FeedComment> findVisibleReplies(@Param("rootId") Long rootId, @Param("viewerId") Long viewerId,
                                         @Param("after") Long after, Pageable pageable);

    /** 최상위 댓글별 공개 답글 수(삭제·숨김 제외). */
    @Query("""
            SELECT c.rootCommentId AS id, COUNT(c) AS count FROM FeedComment c
            WHERE c.rootCommentId IN :rootIds AND c.status = 'ACTIVE' AND c.hidden = false GROUP BY c.rootCommentId
            """)
    List<IdCount> countActiveReplies(@Param("rootIds") Collection<Long> rootIds);

    /** 공유본별 공개 댓글 수(최상위·답글 모두, 삭제·숨김·자리표시 제외). */
    @Query("""
            SELECT c.postId AS id, COUNT(c) AS count FROM FeedComment c
            WHERE c.postId IN :postIds AND c.status = 'ACTIVE' AND c.hidden = false GROUP BY c.postId
            """)
    List<IdCount> countActiveByPostIds(@Param("postIds") Collection<Long> postIds);

    List<FeedComment> findByIdIn(Collection<Long> ids);

    /**
     * 수정·삭제용 조회. 행을 잠가(SELECT ... FOR UPDATE) 같은 댓글의 수정·삭제·탈퇴 일괄 삭제를 차례로 처리합니다.
     * 그래서 삭제가 먼저 끝나면 뒤에 온 수정은 삭제 상태를 읽고, 이전 상태로 덮어써 본문을 되살리지 못합니다.
     * 트랜잭션 안에서만 부릅니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM FeedComment c WHERE c.id = :id")
    Optional<FeedComment> findByIdForUpdate(@Param("id") Long id);

    /**
     * 회원 탈퇴: 그 사람의 댓글을 삭제 상태로 바꾸고 본문을 지웁니다(부모·root 연결은 그대로).
     * 회원 행을 지우는 같은 트랜잭션에서 호출하고, 작성자 연결(user_id)은 회원 삭제 때 FK가 NULL로 바꿉니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE FeedComment c SET c.status = 'DELETED', c.content = NULL, c.deletedAt = :now, c.updatedAt = :now
            WHERE c.userId = :userId AND c.status <> 'DELETED'
            """)
    int deleteAllByAuthor(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    interface IdCount {
        Long getId();

        Long getCount();
    }
}
