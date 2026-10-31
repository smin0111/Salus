package com.salus.healthytable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 동의 기록입니다(user_consents 테이블). 동의한 문서 버전마다 한 행이며 고치지 않습니다.
 * 저장은 동시 요청에도 안전한 UserConsentRepository.insertIfAbsent로만 합니다.
 */
@Entity
@Table(name = "user_consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "consent_type", nullable = false, updatable = false, length = 20)
    private ConsentType consentType;

    @Column(name = "version", nullable = false, updatable = false, length = 20)
    private String version;

    @Column(name = "agreed_at", nullable = false, updatable = false)
    private LocalDateTime agreedAt;
}
