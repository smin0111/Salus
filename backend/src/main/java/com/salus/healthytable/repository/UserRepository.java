package com.salus.healthytable.repository;

import com.salus.healthytable.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@link User} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // 계정 연결 후보 찾기: 같은 이메일(검증된 이메일만 저장됨)의 회원을 모두 가져옵니다.
    // 여러 명이면 임의로 고르지 않고 연결을 보류해야 하므로 목록으로 받습니다.
    List<User> findAllByEmail(String email);

    // ID 목록으로 사용자 조회
    List<User> findByIdIn(List<Long> ids);

    // 특정 시각 이후 가입한 사용자 수 (관리자 대시보드의 오늘 신규 가입자 수)
    long countByCreatedAtAfter(java.time.LocalDateTime dateTime);

    // PLUS 구독자 수
    long countByGrade(com.salus.healthytable.domain.UserGrade grade);

    // 기간 내 가입한 사용자 수 (활동 사용자 수가 아니라 createdAt 기준 가입자 수입니다)
    long countByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
}
