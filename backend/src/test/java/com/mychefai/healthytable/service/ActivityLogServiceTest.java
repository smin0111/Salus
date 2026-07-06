package com.mychefai.healthytable.service;

import com.mychefai.healthytable.domain.ActivityLog;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.repository.ActivityLogRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityLogServiceTest {

    private final ActivityLogRepository activityLogRepository = mock(ActivityLogRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-05T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final ActivityLogService service = new ActivityLogService(activityLogRepository, clock);

    @Test
    void getActivityLogsReadsOnlyCurrentUsersLogs() {
        User user = user(1L);
        ActivityLog log = new ActivityLog();
        when(activityLogRepository.findByUser(user)).thenReturn(List.of(log));

        List<ActivityLog> response = service.getActivityLogs(user);

        assertThat(response).containsExactly(log);
        verify(activityLogRepository).findByUser(user);
    }

    @Test
    void logActivityCreatesTodayLogForCurrentUser() {
        User user = user(1L);
        LocalDate today = LocalDate.now(clock);

        when(activityLogRepository.findByUserAndActivityDate(user, today)).thenReturn(Optional.empty());
        when(activityLogRepository.save(any(ActivityLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ActivityLog saved = service.logActivity(user, false);

        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getActivityDate()).isEqualTo(today);
        assertThat(saved.getHasAiInteraction()).isFalse();
    }

    @Test
    void logActivityMarksExistingLogAsAiWhenAiInteractionOccurs() {
        User user = user(1L);
        LocalDate today = LocalDate.now(clock);
        ActivityLog existing = new ActivityLog();
        existing.setUser(user);
        existing.setActivityDate(today);
        existing.setHasAiInteraction(false);

        when(activityLogRepository.findByUserAndActivityDate(user, today)).thenReturn(Optional.of(existing));
        when(activityLogRepository.save(any(ActivityLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ActivityLog saved = service.logActivity(user, true);

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getHasAiInteraction()).isTrue();
        verify(activityLogRepository).save(existing);
    }

    @Test
    void logActivityDoesNotDowngradeExistingAiLog() {
        User user = user(1L);
        LocalDate today = LocalDate.now(clock);
        ActivityLog existing = new ActivityLog();
        existing.setUser(user);
        existing.setActivityDate(today);
        existing.setHasAiInteraction(true);

        when(activityLogRepository.findByUserAndActivityDate(user, today)).thenReturn(Optional.of(existing));
        when(activityLogRepository.save(any(ActivityLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ActivityLog saved = service.logActivity(user, false);

        assertThat(saved.getHasAiInteraction()).isTrue();
    }

    @Test
    void logActivityUsesConfiguredClockZoneForActivityDate() {
        User user = user(1L);
        Clock seoulClock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
        ActivityLogService seoulService = new ActivityLogService(activityLogRepository, seoulClock);

        when(activityLogRepository.findByUserAndActivityDate(user, LocalDate.of(2026, 7, 6)))
                .thenReturn(Optional.empty());
        when(activityLogRepository.save(any(ActivityLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ActivityLog saved = seoulService.logActivity(user, false);

        assertThat(saved.getActivityDate()).isEqualTo(LocalDate.of(2026, 7, 6));
        verify(activityLogRepository).findByUserAndActivityDate(user, LocalDate.of(2026, 7, 6));
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
