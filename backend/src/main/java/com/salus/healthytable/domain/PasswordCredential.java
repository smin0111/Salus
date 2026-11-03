package com.salus.healthytable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 이메일·비밀번호 로그인 정보입니다(password_credentials 테이블, 회원당 한 행).
 * 비밀번호는 BCrypt 해시만 저장하고, 연속 실패 횟수와 잠금 시각으로 무차별 대입을 늦춥니다.
 */
@Entity
@Table(name = "password_credentials")
@Getter
@Setter
@NoArgsConstructor
public class PasswordCredential {

    @Id
    @Column(name = "user_id")
    private Long userId;

    // 소문자로 정규화한 로그인 이메일(UNIQUE)
    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "password_changed_at", nullable = false)
    private LocalDateTime passwordChangedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
