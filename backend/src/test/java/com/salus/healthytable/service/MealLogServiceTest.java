package com.salus.healthytable.service;

import com.salus.healthytable.domain.MealLog;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.MealLogDTO;
import com.salus.healthytable.repository.MealLogRepository;
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

/**
 * {@link MealLogService} 테스트입니다.
 */
class MealLogServiceTest {

    private final MealLogRepository mealLogRepository = mock(MealLogRepository.class);
    private final GeminiService geminiService = mock(GeminiService.class);
    private final MutableClock clock = new MutableClock(java.time.Instant.parse("2026-07-20T00:00:00Z"));
    private final MealLogService service = new MealLogService(mealLogRepository, geminiService, new MonthlyAnalysisGuard(clock));

    // 새 식단 기록을 현재 사용자로 만들고 입력값을 정리해 저장해야 합니다.
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

    // 기존 기록의 상세 정보 JSON에 새 끼니 정보를 덮어쓰지 않고 합쳐야 합니다.
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

    // 기록 날짜는 필수여야 합니다.
    @Test
    void saveOrUpdateMealLogRequiresRecordDate() {
        MealLogDTO dto = new MealLogDTO();
        dto.setBreakfast("오트밀");

        assertThatThrownBy(() -> service.saveOrUpdateMealLog(user(1L), dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("식단 기록 날짜를 입력해 주세요.");

        verifyNoInteractions(mealLogRepository);
    }

    // 빈 메뉴 이름은 거부해야 합니다.
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

    // 허용 범위를 벗어난 열량은 거부해야 합니다.
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

    // 잘못된 JSON 필드는 거부해야 합니다.
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

    // 잘못된 월(13월)은 DB 조회 전에 거부해야 합니다.
    @Test
    void getMonthlyAnalysisRejectsInvalidMonthBeforeRepositoryLookup() {
        assertThatThrownBy(() -> service.getMonthlyAnalysis(user(1L), 2026, 13))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("월은 1부터 12 사이로 입력해 주세요.");

        verifyNoInteractions(mealLogRepository, geminiService);
    }

    // 월간 분석은 현재 사용자와 해당 월의 1일~말일 범위로 조회해야 합니다.
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

    // 같은 달·같은 기록이면 저장한 총평을 다시 쓰고 LLM을 부르지 않습니다. 기록이 바뀌면 새로 만듭니다.
    @Test
    void monthlyAnalysisReusesResultUntilMealLogsChange() {
        User user = user(1L);
        List<MealLog> logs = List.of(mealLog(LocalDate.of(2026, 7, 3), "비빔밥"));
        when(mealLogRepository.findByUserAndRecordDateBetween(user, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(logs);
        when(geminiService.analyzeMonthlyMealPlan(logs)).thenReturn(Mono.just("채소를 잘 챙겼어요."));

        assertThat(service.getMonthlyAnalysis(user, 2026, 7)).isEqualTo("채소를 잘 챙겼어요.");
        assertThat(service.getMonthlyAnalysis(user, 2026, 7)).isEqualTo("채소를 잘 챙겼어요.");
        verify(geminiService, org.mockito.Mockito.times(1)).analyzeMonthlyMealPlan(logs);

        List<MealLog> changed = List.of(mealLog(LocalDate.of(2026, 7, 3), "김밥"));
        when(mealLogRepository.findByUserAndRecordDateBetween(user, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(changed);
        when(geminiService.analyzeMonthlyMealPlan(changed)).thenReturn(Mono.just("탄수화물이 많아요."));
        assertThat(service.getMonthlyAnalysis(user, 2026, 7)).isEqualTo("탄수화물이 많아요.");
    }

    // LLM이 응답하지 않았다는 안내는 저장하지 않아, 다음 요청에서 다시 만듭니다.
    @Test
    void monthlyAnalysisDoesNotCacheFailureReply() {
        User user = user(1L);
        List<MealLog> logs = List.of(mealLog(LocalDate.of(2026, 7, 3), "비빔밥"));
        when(mealLogRepository.findByUserAndRecordDateBetween(user, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(logs);
        when(geminiService.analyzeMonthlyMealPlan(logs))
                .thenReturn(Mono.just(LlmService.UNAVAILABLE_REPLY), Mono.just("채소를 잘 챙겼어요."));

        assertThat(service.getMonthlyAnalysis(user, 2026, 7)).isEqualTo(LlmService.UNAVAILABLE_REPLY);
        assertThat(service.getMonthlyAnalysis(user, 2026, 7)).isEqualTo("채소를 잘 챙겼어요.");
    }

    // 새로 만드는 횟수는 회원마다 10분에 3번까지이고, 10분이 지나면 다시 됩니다.
    @Test
    void monthlyAnalysisLimitsNewGenerationsPerUser() {
        User user = user(1L);
        for (int day = 1; day <= 3; day++) {
            List<MealLog> logs = List.of(mealLog(LocalDate.of(2026, 7, day), "메뉴" + day));
            when(mealLogRepository.findByUserAndRecordDateBetween(user, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                    .thenReturn(logs);
            when(geminiService.analyzeMonthlyMealPlan(logs)).thenReturn(Mono.just("총평" + day));
            service.getMonthlyAnalysis(user, 2026, 7);
        }
        List<MealLog> fourth = List.of(mealLog(LocalDate.of(2026, 7, 4), "메뉴4"));
        when(mealLogRepository.findByUserAndRecordDateBetween(user, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(fourth);
        when(geminiService.analyzeMonthlyMealPlan(fourth)).thenReturn(Mono.just("총평4"));

        assertThatThrownBy(() -> service.getMonthlyAnalysis(user, 2026, 7))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(429));

        clock.advance(java.time.Duration.ofMinutes(11));
        assertThat(service.getMonthlyAnalysis(user, 2026, 7)).isEqualTo("총평4");
    }

    // 만드는 도중 탈퇴(forgetUser)하면 그 결과를 저장하지 않습니다. 다음 요청은 새로 만들어야 합니다.
    @Test
    void monthlyAnalysisIsNotCachedWhenUserIsForgottenDuringGeneration() {
        MonthlyAnalysisGuard guard = new MonthlyAnalysisGuard(clock);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        guard.getOrGenerate(1L, "2026-07", "fp", () -> {
            calls.incrementAndGet();
            guard.forgetUser(1L);
            return "총평";
        });
        guard.getOrGenerate(1L, "2026-07", "fp", () -> {
            calls.incrementAndGet();
            return "총평";
        });

        assertThat(calls.get()).isEqualTo(2);
    }

    // 다른 스레드에서 탈퇴(forgetUser)가 끝난 뒤 생성이 끝나도 저장하지 않습니다.
    @Test
    void monthlyAnalysisIsNotCachedWhenForgottenFromAnotherThread() throws Exception {
        MonthlyAnalysisGuard guard = new MonthlyAnalysisGuard(clock);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch forgotten = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<String> generation = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                guard.getOrGenerate(1L, "2026-07", "fp", () -> {
                    started.countDown();
                    try {
                        forgotten.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "총평";
                }));
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        guard.forgetUser(1L);
        forgotten.countDown();
        assertThat(generation.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("총평");

        java.util.concurrent.atomic.AtomicBoolean regenerated = new java.util.concurrent.atomic.AtomicBoolean();
        guard.getOrGenerate(1L, "2026-07", "fp", () -> {
            regenerated.set(true);
            return "총평";
        });
        assertThat(regenerated).isTrue();
    }

    // 탈퇴 전에 식단을 읽어 둔 요청이 탈퇴(forgetUser)가 끝난 뒤에 생성을 시작해도 저장하지 않습니다(Codex 검토 3회차).
    @Test
    void monthlyAnalysisStartedAfterForgetIsNotCached() {
        MonthlyAnalysisGuard guard = new MonthlyAnalysisGuard(clock);
        guard.forgetUser(1L);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        guard.getOrGenerate(1L, "2026-07", "fp", () -> "총평" + calls.incrementAndGet());
        guard.getOrGenerate(1L, "2026-07", "fp", () -> "총평" + calls.incrementAndGet());

        assertThat(calls.get()).isEqualTo(2);
    }

    // 다른 회원이 만드는 중이라 거절(503)된 요청은 이 회원의 생성 횟수를 쓰지 않습니다.
    @Test
    void monthlyAnalysisBusyRejectionDoesNotConsumeUserQuota() {
        MonthlyAnalysisGuard guard = new MonthlyAnalysisGuard(clock);
        guard.getOrGenerate(1L, "2026-07", "fp-a", () -> {
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> guard.getOrGenerate(2L, "2026-07", "fp-b", () -> "B"))
                        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                                e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
            }
            return "A";
        });
        for (int i = 0; i < 3; i++) {
            assertThat(guard.getOrGenerate(2L, "2026-0" + (i + 1), "fp-b" + i, () -> "B")).isEqualTo("B");
        }
    }

    private MealLog mealLog(LocalDate date, String lunch) {
        MealLog log = new MealLog();
        log.setRecordDate(date);
        log.setLunch(lunch);
        return log;
    }

    // 시간을 앞으로 돌릴 수 있는 테스트용 시계
    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant now;

        MutableClock(java.time.Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneId.of("Asia/Seoul");
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
