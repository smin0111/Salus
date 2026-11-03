package com.salus.healthytable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 가입·비밀번호 재설정용 이메일 인증 코드입니다(email_verifications 테이블).
 * 코드 원문은 저장하지 않고 해시만 남기며, 만료·시도 횟수·사용 시각으로 한 번만 쓰게 합니다.
 */
@Entity
@Table(name = "email_verifications")
@Getter
@Setter
@NoArgsConstructor
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private EmailVerificationPurpose purpose;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "code_hash", nullable = false, columnDefinition = "char(64)")
    private String codeHash;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
