package com.salus.healthytable.repository;

import com.salus.healthytable.domain.EmailVerification;
import com.salus.healthytable.domain.EmailVerificationPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    /**
     * 이 이메일·용도의 가장 최근 코드를 잠그고 읽습니다. 확인 시도 횟수를 동시 요청에도 정확히 세기 위해서입니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerification> findFirstByEmailAndPurposeOrderByCreatedAtDescIdDesc(String email, EmailVerificationPurpose purpose);

    Optional<EmailVerification> findFirstByEmailAndPurposeOrderByCreatedAtDesc(String email, EmailVerificationPurpose purpose);

    long countByEmailAndCreatedAtAfter(String email, LocalDateTime after);

    @Modifying
    @Query("DELETE FROM EmailVerification v WHERE v.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);

    @Modifying
    @Query("DELETE FROM EmailVerification v WHERE v.email = :email")
    int deleteByEmail(@Param("email") String email);
}
