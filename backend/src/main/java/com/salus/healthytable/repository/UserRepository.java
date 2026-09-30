package com.salus.healthytable.repository;

import com.salus.healthytable.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@link User} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // 소셜 계정이 하나도 연결되지 않은 기존 회원(V9 이전 가입자)을 이메일로 찾습니다.
    // 새 회원은 항상 소셜 계정을 가지므로, 이 조회는 기존 회원의 첫 로그인 연결에만 쓰입니다.
    @Query("select u from User u where u.email = :email "
            + "and not exists (select s.id from SocialAccount s where s.userId = u.id)")
    List<User> findUnlinkedByEmail(@Param("email") String email);

    // ID 목록으로 사용자 조회
    List<User> findByIdIn(List<Long> ids);

    // 특정 시각 이후 가입한 사용자 수 (관리자 대시보드의 오늘 신규 가입자 수)
    long countByCreatedAtAfter(java.time.LocalDateTime dateTime);

    // PLUS 구독자 수
    long countByGrade(com.salus.healthytable.domain.UserGrade grade);

    // 기간 내 가입한 사용자 수 (활동 사용자 수가 아니라 createdAt 기준 가입자 수입니다)
    long countByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
}
