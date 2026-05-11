package com.mychefai.healthytable.repository;

import com.mychefai.healthytable.domain.MealLog;
import com.mychefai.healthytable.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MealLogRepository extends JpaRepository<MealLog, Long> {
    List<MealLog> findByUser(User user);

    Optional<MealLog> findByUserAndRecordDate(User user, LocalDate recordDate);

    List<MealLog> findByUserAndRecordDateBetween(User user, LocalDate startDate, LocalDate endDate);

    long countByUser(User user);

    void deleteByUser(User user);
}
