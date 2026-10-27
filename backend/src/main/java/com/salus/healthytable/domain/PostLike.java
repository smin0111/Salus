package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 게시글 좋아요 기록 엔티티입니다(post_likes 테이블).
 * 한 행이 "어떤 사용자가 어떤 글에 좋아요를 눌렀는지"를 의미합니다.
 * 예전 커뮤니티 API는 제거했습니다. 표가 남아 있는 동안 회원 탈퇴 정리와 계정 화면의 건수 집계를 위해서만 엔티티를 남겨 둡니다.
 */
@Entity
@Table(name = "post_likes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostLike {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
