package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 커뮤니티 게시글의 댓글 엔티티입니다(post_comments 테이블).
 * parentId가 있으면 다른 댓글에 단 답글(대댓글)입니다.
 */
@Entity
@Table(name = "post_comments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 부모 댓글 ID. 일반 댓글이면 null입니다.
    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // 저장 직전에 생성/수정 시각이 비어 있으면 현재 시각으로 채웁니다.
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    // 수정 시각이 비어 있을 때만 채웁니다. 실제 수정 시각 갱신은 서비스 코드에서 직접 설정합니다.
    @PreUpdate
    protected void onUpdate() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }
}
