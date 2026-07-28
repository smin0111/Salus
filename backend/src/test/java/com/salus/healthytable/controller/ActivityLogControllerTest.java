package com.salus.healthytable.controller;

import com.salus.healthytable.domain.ActivityLog;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.ActivityLogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ActivityLogControllerTest {

    private final ActivityLogService activityLogService = mock(ActivityLogService.class);
    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ActivityLogController controller = new ActivityLogController(
            activityLogService,
            authenticatedUserProvider,
            userRepository);

    @Test
    void getActivityLogsUsesCurrentUser() {
        User user = user(1L);
        ActivityLog log = new ActivityLog();

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(activityLogService.getActivityLogs(user)).thenReturn(List.of(log));

        ResponseEntity<?> response = controller.getActivityLogs();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(List.of(log));
        verify(activityLogService).getActivityLogs(user);
    }

    @Test
    void logActivityUsesCurrentUserAndAiFlag() {
        User user = user(1L);
        ActivityLog log = new ActivityLog();

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(activityLogService.logActivity(user, true)).thenReturn(log);

        ResponseEntity<?> response = controller.logActivity(Map.of("isAi", true));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(log);
        verify(activityLogService).logActivity(user, true);
    }

    @Test
    void logActivityDefaultsMissingAiFlagToFalse() {
        User user = user(1L);
        ActivityLog log = new ActivityLog();

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(activityLogService.logActivity(user, false)).thenReturn(log);

        ResponseEntity<?> response = controller.logActivity(Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(activityLogService).logActivity(user, false);
    }

    @Test
    void logActivityDefaultsNullBodyToFalse() {
        User user = user(1L);
        ActivityLog log = new ActivityLog();

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(activityLogService.logActivity(user, false)).thenReturn(log);

        ResponseEntity<?> response = controller.logActivity(null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(activityLogService).logActivity(user, false);
    }

    @Test
    void currentUserMissingFromDatabaseReturnsNotFound() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(404L);
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(controller::getActivityLogs)
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("사용자를 찾을 수 없습니다.");
                });

        verifyNoInteractions(activityLogService);
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
