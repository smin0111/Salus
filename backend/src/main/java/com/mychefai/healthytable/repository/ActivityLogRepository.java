package com.mychefai.healthytable.repository;

import com.mychefai.healthytable.domain.ActivityLog;
import com.mychefai.healthytable.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {
    List<ActivityLog> findByUser(User user);

    Optional<ActivityLog> findByUserAndActivityDate(User user, LocalDate activityDate);

    long countByUser(User user);

    void deleteByUser(User user);

    long countByActivityDate(LocalDate activityDate);

    long countByActivityDateAndHasAiInteraction(LocalDate activityDate, Boolean hasAiInteraction);
}
