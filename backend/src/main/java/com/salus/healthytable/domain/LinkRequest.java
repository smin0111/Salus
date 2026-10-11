package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 소셜 계정 연결 요청입니다(link_requests 테이블).
 *
 * 대기 중인 소셜 계정(pending)을 대상 회원(target)에 연결하려면, 사용자가 대상 회원의 기존 로그인 수단으로
 * 다시 로그인해야 합니다. 10분 안에 한 번만 쓸 수 있고, 취소되면 다시 쓸 수 없습니다.
 */
@Entity
@Table(name = "link_requests")
@Getter
@Setter
@NoArgsConstructor
public class LinkRequest {

    public enum Purpose { LOGIN_LINK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "flow_nonce_hash", nullable = false, columnDefinition = "char(64)")
    private String flowNonceHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Purpose purpose;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "pending_provider", nullable = false, length = 20)
    private SocialProvider pendingProvider;

    @Column(name = "pending_provider_user_id", nullable = false)
    private String pendingProviderUserId;

    @Column(name = "pending_email")
    private String pendingEmail;

    @Column(name = "pending_email_verified", nullable = false)
    private boolean pendingEmailVerified;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
}
