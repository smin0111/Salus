package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.MealLog;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.dto.MealLogDTO;
import com.mychefai.healthytable.repository.UserRepository;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.service.MealLogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MealLogControllerTest {

    private final MealLogService mealLogService = mock(MealLogService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final MealLogController controller = new MealLogController(
            mealLogService,
            userRepository,
            authenticatedUserProvider);

    @Test
    void getMyMealLogsUsesCurrentUser() {
        User user = user(1L);
        MealLog mealLog = new MealLog();

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(mealLogService.getMealLogs(user)).thenReturn(List.of(mealLog));

        ResponseEntity<List<MealLog>> response = controller.getMyMealLogs();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsExactly(mealLog);
        verify(mealLogService).getMealLogs(user);
    }

    @Test
    void saveMealLogUsesCurrentUserAndRequestDto() {
        User user = user(1L);
        MealLogDTO dto = new MealLogDTO();
        dto.setRecordDate(LocalDate.of(2026, 7, 3));
        dto.setBreakfast("오트밀");
        MealLog saved = new MealLog();

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(mealLogService.saveOrUpdateMealLog(user, dto)).thenReturn(saved);

        ResponseEntity<MealLog> response = controller.saveMealLog(dto);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(saved);
        verify(mealLogService).saveOrUpdateMealLog(user, dto);
    }

    @Test
    void getMonthlyAnalysisUsesCurrentUser() {
        User user = user(1L);

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(mealLogService.getMonthlyAnalysis(user, 2026, 7)).thenReturn("분석 결과");

        ResponseEntity<String> response = controller.getMonthlyAnalysis(2026, 7);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("분석 결과");
        verify(mealLogService).getMonthlyAnalysis(user, 2026, 7);
    }

    @Test
    void currentUserMissingFromDatabaseReturnsNotFound() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(404L);
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(controller::getMyMealLogs)
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("사용자를 찾을 수 없습니다.");
                });

        verifyNoInteractions(mealLogService);
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
