package com.salus.healthytable.repository;

import com.salus.healthytable.domain.AdminAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link AdminAccount} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {

    Optional<AdminAccount> findByUsername(String username);

    // 실패 횟수·잠금은 동시에 여러 요청이 와도 정확히 세야 하므로 행을 잠그고 읽습니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AdminAccount a where a.username = :username")
    Optional<AdminAccount> findForUpdateByUsername(@Param("username") String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AdminAccount a where a.id = :id")
    Optional<AdminAccount> findForUpdateById(@Param("id") Long id);
}
