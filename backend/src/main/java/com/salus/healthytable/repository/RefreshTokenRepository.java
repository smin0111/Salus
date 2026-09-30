package com.salus.healthytable.repository;

import com.salus.healthytable.domain.RefreshToken;
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
 * {@link RefreshToken} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // 같은 토큰으로 갱신 요청이 동시에 들어와도 한 요청만 교체하도록 행을 잠그고 읽습니다(SELECT ... FOR UPDATE).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findForUpdateByTokenHash(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 탈취가 의심되면 그 사용자의 살아 있는 토큰을 모두 폐기합니다.
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    // 새 토큰을 발급할 때 그 사용자의 만료된 행을 함께 정리해 테이블이 계속 커지지 않게 합니다.
    @Modifying
    @Query("delete from RefreshToken t where t.userId = :userId and t.expiresAt < :now")
    int deleteExpiredByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
