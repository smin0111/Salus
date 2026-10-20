package com.salus.healthytable.repository;

import com.salus.healthytable.domain.MealLog;
import com.salus.healthytable.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * {@link MealLog} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface MealLogRepository extends JpaRepository<MealLog, Long> {
    List<MealLog> findByUser(User user);

    Optional<MealLog> findByUserAndRecordDate(User user, LocalDate recordDate);

    // 시작일과 종료일을 모두 포함하는 기간의 식단 기록을 조회합니다(캘린더 월간 조회 등).
    List<MealLog> findByUserAndRecordDateBetween(User user, LocalDate startDate, LocalDate endDate);

    long countByUser(User user);

    void deleteByUser(User user);
}
