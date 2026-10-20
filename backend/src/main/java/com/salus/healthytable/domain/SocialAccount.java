package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 사용자에 연결된 소셜 로그인 계정입니다(social_accounts 테이블).
 *
 * 로그인 사용자는 (provider, providerUserId)로만 찾습니다. 이메일은 참고용이며,
 * 제공자가 다르면 이메일이 같아도 별개 사용자로 취급합니다.
 */
@Entity
@Table(name = "social_accounts")
@Getter
@Setter
@NoArgsConstructor
public class SocialAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private SocialProvider provider;

    // 제공자가 발급한 고유 ID(Google sub, Kakao id, Naver id)입니다.
    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
}
