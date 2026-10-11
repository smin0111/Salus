package com.salus.healthytable.repository;

import com.salus.healthytable.domain.AuthNonce;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@link AuthNonce} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface AuthNonceRepository extends JpaRepository<AuthNonce, Long> {

    // 같은 nonce로 두 요청이 동시에 와도 한 번만 소비되도록 행을 잠그고 읽습니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from AuthNonce n where n.nonceHash = :nonceHash")
    Optional<AuthNonce> findForUpdateByNonceHash(@Param("nonceHash") String nonceHash);

    // 발급할 때 만료된 행을 함께 정리해 테이블이 계속 커지지 않게 합니다.
    @Modifying
    @Query("delete from AuthNonce n where n.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
