package com.salus.healthytable.repository;

import com.salus.healthytable.domain.ConsentType;
import com.salus.healthytable.domain.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    boolean existsByUserIdAndConsentTypeAndVersion(Long userId, ConsentType consentType, String version);

    /**
     * 같은 회원의 동의 요청을 차례로 처리하려고 회원 행을 잠급니다(트랜잭션이 끝날 때까지).
     * 잠그지 않으면 동시 요청의 여러 행 삽입이 서로의 잠금을 기다리다 교착(deadlock)됩니다(ConsentMySqlIT에서 재현).
     */
    @Query(value = "SELECT id FROM users WHERE id = :userId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockUser(@Param("userId") Long userId);

    /**
     * 같은 (사용자, 종류, 버전)이 이미 있으면 아무것도 하지 않습니다. 동시에 두 번 동의해도 예외 없이 한 행만 남고
     * 트랜잭션이 실패 상태가 되지 않습니다(유니크 충돌 예외를 잡는 방식은 JPA 트랜잭션을 되살리지 못함).
     */
    @Modifying
    @Query(value = "INSERT INTO user_consents (user_id, consent_type, version, agreed_at) "
            + "VALUES (:userId, :type, :version, :agreedAt) "
            + "ON DUPLICATE KEY UPDATE id = id", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("type") String type,
            @Param("version") String version, @Param("agreedAt") LocalDateTime agreedAt);
}
