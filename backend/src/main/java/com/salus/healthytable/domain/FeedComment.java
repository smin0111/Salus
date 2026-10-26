package com.salus.healthytable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 피드 댓글입니다(feed_comments 테이블).
 *
 * <p>parentCommentId는 실제로 답한 댓글, rootCommentId는 화면에서 묶이는 최상위 댓글입니다(최상위 댓글은 둘 다 null).
 * 대댓글에 다시 답해도 root는 그 대댓글의 root라서 화면은 2단계까지만 쌓입니다.
 *
 * <p>삭제는 소프트 삭제입니다(status=DELETED, content=null). 답글 구조를 유지하고 본문은 지웁니다.
 * 관리자 숨김(hidden)은 이와 따로 관리합니다(숨긴 댓글도 작성자가 지우면 본문이 지워짐).
 * 회원 탈퇴 시에도 탈퇴 처리에서 같은 방식으로 지우고, 회원 행이 지워지면 userId만 null이 됩니다.
 * clientRequestId·requestHash는 처음 저장할 때 정하고 수정·삭제해도 바꾸지 않습니다.
 */
@Entity
@Table(name = "feed_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedComment {

    public static final String ACTIVE = "ACTIVE";
    public static final String DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    // 탈퇴하면 DB가 NULL로 바꿉니다(FK SET NULL). 그래서 updatable=false로 두지 않습니다.
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "parent_comment_id", updatable = false)
    private Long parentCommentId;

    @Column(name = "root_comment_id", updatable = false)
    private Long rootCommentId;

    @Column(length = 1000)
    private String content;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private boolean edited;

    @Column(name = "client_request_id", length = 36, updatable = false)
    private String clientRequestId;

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 관리자 숨김(V25). 작성자 삭제(status)와 따로 관리합니다: 숨김 해제가 지운 본문을 되살리지 않고,
    // 작성자가 숨긴 댓글을 고쳐도 숨김은 그대로입니다.
    @Column(nullable = false)
    private boolean hidden;

    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    public static FeedComment write(Long postId, Long userId, Long parentCommentId, Long rootCommentId, String content,
                                    String clientRequestId, String requestHash, LocalDateTime now) {
        FeedComment comment = new FeedComment();
        comment.postId = postId;
        comment.userId = userId;
        comment.parentCommentId = parentCommentId;
        comment.rootCommentId = rootCommentId;
        comment.content = content;
        comment.status = ACTIVE;
        comment.edited = false;
        comment.clientRequestId = clientRequestId;
        comment.requestHash = requestHash;
        comment.createdAt = now;
        comment.updatedAt = now;
        return comment;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    /** 다른 사람에게 보이는 상태: 작성자가 지우지 않았고 관리자가 숨기지 않음. */
    public boolean isPublic() {
        return isActive() && !hidden;
    }

    /** 관리자 숨김. 본문과 작성자 삭제 상태는 건드리지 않습니다. 이미 숨겼으면 그대로입니다. */
    public void hide(LocalDateTime now) {
        if (!hidden) {
            hidden = true;
            hiddenAt = now;
        }
    }

    /** 관리자 숨김 해제. 작성자가 지운 댓글은 지운 상태 그대로입니다(본문을 되살리지 않음). */
    public void unhide() {
        hidden = false;
        hiddenAt = null;
    }

    public void edit(String content, LocalDateTime now) {
        this.content = content;
        this.edited = true;
        this.updatedAt = now;
    }

    /** 본문을 지우고 삭제 상태로 둡니다. 이미 지운 댓글이면 아무것도 바꾸지 않습니다. */
    public void softDelete(LocalDateTime now) {
        if (!isActive()) {
            return;
        }
        this.content = null;
        this.status = DELETED;
        this.deletedAt = now;
        this.updatedAt = now;
    }
}
