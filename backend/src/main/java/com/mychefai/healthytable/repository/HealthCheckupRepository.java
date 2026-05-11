package com.mychefai.healthytable.repository;

import com.mychefai.healthytable.domain.HealthCheckup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface HealthCheckupRepository extends JpaRepository<HealthCheckup, Long> {
    Optional<HealthCheckup> findTopByUserIdOrderByCheckupDateDescIdDesc(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
