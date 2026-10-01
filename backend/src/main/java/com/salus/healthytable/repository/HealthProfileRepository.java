package com.salus.healthytable.repository;

import com.salus.healthytable.domain.HealthProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link HealthProfile} 엔티티의 DB 접근 인터페이스입니다. 사용자당 프로필은 하나입니다.
 */
@Repository
public interface HealthProfileRepository extends JpaRepository<HealthProfile, Long> {
    Optional<HealthProfile> findByUserId(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
