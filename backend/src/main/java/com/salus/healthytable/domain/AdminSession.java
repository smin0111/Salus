package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 관리자 로그인 세션입니다(admin_sessions 테이블).
 * 30분 동안 요청이 없거나 발급 후 8시간이 지나면 끝나고, 서버에서 강제로 종료할 수 있습니다.
 */
@Entity
@Table(name = "admin_sessions")
@Getter
@Setter
@NoArgsConstructor
public class AdminSession {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
}
