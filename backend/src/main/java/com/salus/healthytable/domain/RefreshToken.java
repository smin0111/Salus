package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 로그인 유지용 refresh token입니다(refresh_tokens 테이블).
 *
 * 원문은 클라이언트만 갖고 서버는 SHA-256 해시만 저장하므로, DB가 유출돼도 토큰으로 쓸 수 없습니다.
 * usedAt: 새 토큰으로 교체된 시각(한 번 쓰면 끝), revokedAt: 로그아웃·탈취 감지로 폐기된 시각.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
}
