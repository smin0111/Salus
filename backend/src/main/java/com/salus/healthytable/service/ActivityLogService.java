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

/**
 * 사용자의 일별 활동 기록을 남기는 서비스입니다.
 * 기록된 값은 관리자 대시보드의 DAU(일일 활동 사용자)와 AI 사용량 통계에 사용됩니다.
 */
@Service
@RequiredArgsConstructor
public class ActivityLogService {

    private final ActivityLogRepository activityLogRepository;
    private final Clock clock;

    // 사용자의 전체 활동 기록을 조회합니다.
    public List<ActivityLog> getActivityLogs(User user) {
        return activityLogRepository.findByUser(user);
    }

    /**
     * 오늘 활동을 기록합니다. 하루에 한 행만 유지합니다.
     * - 오늘 기록이 이미 있으면: AI 사용 요청일 때만 hasAiInteraction을 true로 올립니다(한 번 true면 다시 false로 내리지 않음).
     * - 오늘 기록이 없으면: 새 행을 만듭니다.
     */
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
