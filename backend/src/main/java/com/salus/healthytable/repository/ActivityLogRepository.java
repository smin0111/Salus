package com.salus.healthytable.repository;

import com.salus.healthytable.domain.ActivityLog;
import com.salus.healthytable.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * {@link ActivityLog} 엔티티의 DB 접근 인터페이스입니다.
 *
 * Spring Data JPA는 메서드 이름을 분석해 쿼리를 자동으로 만들어 줍니다.
 * 예) findByUserAndActivityDate → WHERE user_id = ? AND activity_date = ?
 * JpaRepository를 상속하면 save, findById, delete 같은 기본 CRUD 메서드도 함께 제공됩니다.
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {
    List<ActivityLog> findByUser(User user);

    Optional<ActivityLog> findByUserAndActivityDate(User user, LocalDate activityDate);

    long countByUser(User user);

    // 회원 탈퇴 시 해당 사용자의 활동 기록을 모두 삭제합니다.
    void deleteByUser(User user);

    // 관리자 대시보드: 특정 날짜의 활동 사용자 수 / 그중 AI 기능을 사용한 사용자 수
    long countByActivityDate(LocalDate activityDate);

    long countByActivityDateAndHasAiInteraction(LocalDate activityDate, Boolean hasAiInteraction);
}
