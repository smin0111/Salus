package com.salus.healthytable.repository;

import com.salus.healthytable.domain.LinkRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link LinkRequest} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface LinkRequestRepository extends JpaRepository<LinkRequest, Long> {

    // 같은 요청으로 연결·취소가 동시에 들어와도 하나만 처리되도록 행을 잠그고 읽습니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LinkRequest r where r.tokenHash = :tokenHash")
    Optional<LinkRequest> findForUpdateByTokenHash(@Param("tokenHash") String tokenHash);
}
