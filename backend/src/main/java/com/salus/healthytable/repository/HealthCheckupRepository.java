package com.salus.healthytable.repository;

import com.salus.healthytable.domain.HealthCheckup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link HealthCheckup} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface HealthCheckupRepository extends JpaRepository<HealthCheckup, Long> {
    // 가장 최근 검진 1건을 조회합니다. 같은 날짜가 여러 건이면 나중에 저장된 것(id가 큰 것)을 고릅니다.
    Optional<HealthCheckup> findTopByUserIdOrderByCheckupDateDescIdDesc(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
