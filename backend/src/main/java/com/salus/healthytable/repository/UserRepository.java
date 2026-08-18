package com.salus.healthytable.repository;

import com.salus.healthytable.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    // ID 목록으로 사용자 조회
    List<User> findByIdIn(List<Long> ids);

    long countByCreatedAtAfter(java.time.LocalDateTime dateTime);

    // PLUS 구독자 수
    long countByGrade(com.salus.healthytable.domain.UserGrade grade);

    // 전일 DAU 계산용 (어제 기준 활동자 수)
    long countByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
}
