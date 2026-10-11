package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 서버가 발급한 일회용 nonce입니다(auth_nonces 테이블). 제공자 토큰에 담긴 nonce가 이 값이면
 * 그 토큰은 방금 시작한 로그인에서 나온 것으로 인정합니다. 한 번 쓰면 끝이고 10분 뒤 만료됩니다.
 */
@Entity
@Table(name = "auth_nonces")
@Getter
@Setter
@NoArgsConstructor
public class AuthNonce {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 마이그레이션이 CHAR(64)라 기본 VARCHAR 매핑 대신 열 정의를 맞춥니다(Recipe.sourceHash와 같은 방식).
    @Column(name = "nonce_hash", nullable = false, columnDefinition = "char(64)")
    private String nonceHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;
}
