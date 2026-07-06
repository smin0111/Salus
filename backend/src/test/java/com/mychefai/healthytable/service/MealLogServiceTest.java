package com.mychefai.healthytable.service;

import com.mychefai.healthytable.domain.MealLog;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.dto.MealLogDTO;
import com.mychefai.healthytable.repository.MealLogRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MealLogServiceTest {

    private final MealLogRepository mealLogRepository = mock(MealLogRepository.class);
    private final GeminiService geminiService = mock(GeminiService.class);
    private final MealLogService service = new MealLogService(mealLogRepository, geminiService);

    @Test
    void saveOrUpdateMealLogCreatesCurrentUsersMealLogWithNormalizedValues() {
        User user = user(1L);
        MealLogDTO dto = new MealLogDTO();
        dto.setRecordDate(LocalDate.of(2026, 7, 3));
        dto.setBreakfast("  과일   요거트  ");
        dto.setBreakfastCalories(320);
        dto.setIsAiBreakfast(null);
        dto.setSnacks("[\"견과류\"]");
        dto.setMealDetails("{\"breakfast\":{\"fullText\":\"상세 레시피\"}}");
        dto.setDailyStats("{\"totalCalories\":320}");

        when(mealLogRepository.findByUserAndRecordDate(user, LocalDate.of(2026, 7, 3))).thenReturn(Optional.empty());
        when(mealLogRepository.save(any(MealLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MealLog saved = service.saveOrUpdateMealLog(user, dto);

        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getRecordDate()).isEqualTo(LocalDate.of(2026, 7, 3));
        assertThat(saved.getBreakfast()).isEqualTo("과일 요거트");
        assertThat(saved.getBreakfastCalories()).isEqualTo(320);
        assertThat(saved.getIsAiBreakfast()).isFalse();
        assertThat(saved.getSnacks()).isEqualTo("[\"견과류\"]");
        assertThat(saved.getMealDetails()).contains("\"breakfast\"");
        assertThat(saved.getDailyStats()).isEqualTo("{\"totalCalories\":320}");
    }

    @Test
    void saveOrUpdateMealLogMergesMealDetailsIntoExistingLog() {
        User user = user(1L);
        MealLog existing = new MealLog();
        existing.setUser(user);
        existing.setRecordDate(LocalDate.of(2026, 7, 3));
        existing.setMealDetails("{\"breakfast\":{\"fullText\":\"아침\"}}");

        MealLogDTO dto = new MealLogDTO();
        dto.setRecordDate(LocalDate.of(2026, 7, 3));
        dto.setDinner("채소 덮밥");
        dto.setMealDetails("{\"dinner\":{\"fullText\":\"저녁\"}}");

        when(mealLogRepository.findByUserAndRecordDate(user, LocalDate.of(2026, 7, 3))).thenReturn(Optional.of(existing));
        when(mealLogRepository.save(any(MealLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MealLog saved = service.saveOrUpdateMealLog(user, dto);

        assertThat(saved.getMealDetails())
                .contains("\"breakfast\"")
                .contains("\"dinner\"");
        assertThat(saved.getDinner()).isEqualTo("채소 덮밥");
    }

    @Test
    void saveOrUpdateMealLogRequiresRecordDate() {
        MealLogDTO dto = new MealLogDTO();
        dto.setBreakfast("오트밀");

        assertThatThrownBy(() -> service.saveOrUpdateMealLog(user(1L), dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("식단 기록 날짜를 입력해 주세요.");

        verifyNoInteractions(mealLogRepository);
    }

    @Test
    void saveOrUpdateMealLogRejectsBlankMealName() {
        MealLogDTO dto = new MealLogDTO();
        dto.setRecordDate(LocalDate.of(2026, 7, 3));
        dto.setLunch("   ");

        assertThatThrownBy(() -> service.saveOrUpdateMealLog(user(1L), dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("점심 식단 이름을 입력해 주세요.");

        verifyNoInteractions(mealLogRepository);
    }

    @Test
    void saveOrUpdateMealLogRejectsInvalidCalories() {
        MealLogDTO dto = new MealLogDTO();
        dto.setRecordDate(LocalDate.of(2026, 7, 3));
        dto.setDinner("샐러드");
        dto.setDinnerCalories(-1);

        assertThatThrownBy(() -> service.saveOrUpdateMealLog(user(1L), dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("저녁 칼로리는 0부터 5000kcal 사이로 입력해 주세요.");

        verifyNoInteractions(mealLogRepository);
    }

    @Test
    void saveOrUpdateMealLogRejectsInvalidJson() {
        MealLogDTO dto = new MealLogDTO();
        dto.setRecordDate(LocalDate.of(2026, 7, 3));
        dto.setBreakfast("오트밀");
        dto.setMealDetails("{bad json");

        assertThatThrownBy(() -> service.saveOrUpdateMealLog(user(1L), dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("식단 상세 JSON 형식이 올바르지 않습니다.");

        verifyNoInteractions(mealLogRepository);
    }

    @Test
    void getMonthlyAnalysisRejectsInvalidMonthBeforeRepositoryLookup() {
        assertThatThrownBy(() -> service.getMonthlyAnalysis(user(1L), 2026, 13))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("월은 1부터 12 사이로 입력해 주세요.");

        verifyNoInteractions(mealLogRepository, geminiService);
    }

    @Test
    void getMonthlyAnalysisUsesCurrentUserAndRequestedMonthRange() {
        User user = user(1L);
        when(mealLogRepository.findByUserAndRecordDateBetween(
                user,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31))).thenReturn(List.of());
        when(geminiService.analyzeMonthlyMealPlan(List.of())).thenReturn(Mono.just("분석 결과"));

        String response = service.getMonthlyAnalysis(user, 2026, 7);

        assertThat(response).isEqualTo("분석 결과");
        verify(mealLogRepository).findByUserAndRecordDateBetween(
                user,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31));
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
