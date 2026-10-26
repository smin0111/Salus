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
 * 피드 좋아요입니다(feed_likes 테이블). 사용자·공유본마다 하나이고, 취소하면 행을 지웁니다.
 * 다시 누르면 새 시각으로 저장되므로 인기순(최근 7일간 받은 좋아요)에는 새 반응으로 집계됩니다.
 */
@Entity
@Table(name = "feed_likes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedLike {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static FeedLike of(Long postId, Long userId, LocalDateTime now) {
        FeedLike like = new FeedLike();
        like.postId = postId;
        like.userId = userId;
        like.createdAt = now;
        return like;
    }
}
