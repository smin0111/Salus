package com.salus.healthytable.repository;

import com.salus.healthytable.domain.ProviderWebAuthRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link ProviderWebAuthRequest} 엔티티의 DB 접근 인터페이스입니다.
 * 같은 요청으로 콜백·완료가 동시에 와도 한 번만 처리되도록 행을 잠그고 읽습니다.
 */
@Repository
public interface ProviderWebAuthRequestRepository extends JpaRepository<ProviderWebAuthRequest, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProviderWebAuthRequest r where r.stateHash = :stateHash")
    Optional<ProviderWebAuthRequest> findForUpdateByStateHash(@Param("stateHash") String stateHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProviderWebAuthRequest r where r.requestTokenHash = :requestTokenHash")
    Optional<ProviderWebAuthRequest> findForUpdateByRequestTokenHash(@Param("requestTokenHash") String requestTokenHash);
}
