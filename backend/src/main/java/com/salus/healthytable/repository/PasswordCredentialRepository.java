package com.salus.healthytable.repository;

import com.salus.healthytable.domain.PasswordCredential;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PasswordCredentialRepository extends JpaRepository<PasswordCredential, Long> {

    Optional<PasswordCredential> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * 로그인 실패 횟수를 동시 요청에도 정확히 세려고 행을 잠그고 읽습니다(SELECT ... FOR UPDATE).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM PasswordCredential c WHERE c.email = :email")
    Optional<PasswordCredential> findForUpdateByEmail(@Param("email") String email);

    /** 로그인한 회원의 비밀번호를 다시 확인할 때 같은 이유로 잠그고 읽습니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM PasswordCredential c WHERE c.userId = :userId")
    Optional<PasswordCredential> findForUpdateByUserId(@Param("userId") Long userId);
}
