package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 서버 콜백 방식의 제공자 웹 로그인 요청입니다(provider_web_auth_requests 테이블). 현재 카카오 재확인에 씁니다.
 * state는 제공자 주소에 실리는 값, requestToken은 앱만 가진 값이며 둘 다 해시로만 저장합니다.
 */
@Entity
@Table(name = "provider_web_auth_requests")
@Getter
@Setter
@NoArgsConstructor
public class ProviderWebAuthRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private SocialProvider provider;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 마이그레이션이 CHAR(64)라 열 정의를 맞춥니다(Recipe.sourceHash와 같은 방식).
    @Column(name = "state_hash", nullable = false, columnDefinition = "char(64)")
    private String stateHash;

    @Column(name = "request_token_hash", nullable = false, columnDefinition = "char(64)")
    private String requestTokenHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "callback_started_at")
    private LocalDateTime callbackStartedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "provider_user_id")
    private String providerUserId;

    @Column(name = "provider_email")
    private String providerEmail;

    @Column(name = "provider_email_verified", nullable = false)
    private boolean providerEmailVerified;

    @Column(name = "failure_reason", length = 40)
    private String failureReason;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;
}
