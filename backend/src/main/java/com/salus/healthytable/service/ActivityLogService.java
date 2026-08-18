package com.salus.healthytable.service;

import com.salus.healthytable.domain.ActivityLog;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.repository.ActivityLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ActivityLogService {

    private final ActivityLogRepository activityLogRepository;
    private final Clock clock;

    public List<ActivityLog> getActivityLogs(User user) {
        return activityLogRepository.findByUser(user);
    }

    @Transactional
    public ActivityLog logActivity(User user, boolean isAiInteraction) {
        LocalDate today = LocalDate.now(clock);
        Optional<ActivityLog> existing = activityLogRepository.findByUserAndActivityDate(user, today);

        ActivityLog log;
        if (existing.isPresent()) {
            log = existing.get();
            if (isAiInteraction) {
                log.setHasAiInteraction(true);
            }
        } else {
            log = new ActivityLog();
            log.setUser(user);
            log.setActivityDate(today);
            log.setHasAiInteraction(isAiInteraction);
        }

        return activityLogRepository.save(log);
    }
}
