package com.salus.healthytable.repository;

import com.salus.healthytable.domain.AdminSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * {@link AdminSession} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface AdminSessionRepository extends JpaRepository<AdminSession, String> {

    // 비밀번호 초기화·계정 비활성화 때 그 관리자의 세션을 모두 끝냅니다.
    @Modifying
    @Query("update AdminSession s set s.revokedAt = :now where s.adminId = :adminId and s.revokedAt is null")
    int revokeAllByAdminId(@Param("adminId") Long adminId, @Param("now") LocalDateTime now);
}
