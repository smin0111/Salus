package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.config.CoopHeaderFilter;
import com.mychefai.healthytable.config.SecurityConfig;
import com.mychefai.healthytable.domain.UserGrade;
import com.mychefai.healthytable.repository.ActivityLogRepository;
import com.mychefai.healthytable.repository.PaymentRepository;
import com.mychefai.healthytable.repository.UserRepository;
import com.mychefai.healthytable.security.ApiSecurityErrorHandler;
import com.mychefai.healthytable.security.IpWhitelistFilter;
import com.mychefai.healthytable.security.JwtAuthenticationFilter;
import com.mychefai.healthytable.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDashboardController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        CoopHeaderFilter.class,
        IpWhitelistFilter.class,
        ApiSecurityErrorHandler.class
})
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:3000",
        "app.admin.ip-whitelist.enabled=false"
})
class AdminDashboardSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ActivityLogRepository activityLogRepository;

    @MockBean
    private PaymentRepository paymentRepository;

    @MockBean
    private HealthEndpoint healthEndpoint;

    @MockBean
    private Clock clock;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @org.junit.jupiter.api.BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-07-05T03:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void unauthenticatedUserCannotCheckAdminAuth() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/auth-check"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.path").value("/api/admin/dashboard/auth-check"));
    }

    @Test
    void ordinaryUserCannotCheckAdminAuth() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/auth-check").with(user("1").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."))
                .andExpect(jsonPath("$.path").value("/api/admin/dashboard/auth-check"));
    }

    @Test
    void adminCanCheckAdminAuthWithoutLoadingStats() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/auth-check").with(user("1").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));

        verifyNoInteractions(userRepository, activityLogRepository, paymentRepository);
    }

    @Test
    void unauthenticatedUserCannotReadAdminStats() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.path").value("/api/admin/dashboard/stats"));
    }

    @Test
    void ordinaryUserCannotReadAdminStats() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/stats").with(user("1").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."))
                .andExpect(jsonPath("$.path").value("/api/admin/dashboard/stats"));
    }

    @Test
    void adminCanReadAdminStats() throws Exception {
        when(userRepository.count()).thenReturn(10L);
        when(userRepository.countByGrade(UserGrade.PLUS)).thenReturn(2L);
        when(userRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(1L);
        when(activityLogRepository.countByActivityDate(any(LocalDate.class))).thenReturn(5L);
        when(activityLogRepository.countByActivityDateAndHasAiInteraction(any(LocalDate.class), eq(true)))
                .thenReturn(3L);
        when(paymentRepository.countByPaidAtBetweenAndStatus(
                any(LocalDateTime.class), any(LocalDateTime.class), eq("paid")))
                .thenReturn(1L, 7L);
        when(paymentRepository.sumAmountByPaidAtBetweenAndStatus(
                any(LocalDateTime.class), any(LocalDateTime.class), eq("paid")))
                .thenReturn(9900L, 69300L);
        when(paymentRepository.findDailyPaymentStats(any(LocalDateTime.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/dashboard/stats").with(user("1").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(10))
                .andExpect(jsonPath("$.plusUsers").value(2))
                .andExpect(jsonPath("$.todayPaymentAmount").value(9900))
                .andExpect(jsonPath("$.monthPaymentAmount").value(69300));
    }

    @Test
    void adminStatsDoesNotExposePersonalDataOrRawLogs() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/stats").with(user("1").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.users").doesNotExist())
                .andExpect(jsonPath("$.allergies").doesNotExist())
                .andExpect(jsonPath("$.healthProfiles").doesNotExist())
                .andExpect(jsonPath("$.payments").doesNotExist())
                .andExpect(jsonPath("$.errorLogs").doesNotExist());
    }

    @Test
    void adminStatsUsesActuatorHealthForApplicationStatus() throws Exception {
        when(healthEndpoint.health()).thenReturn(Health.down().build());

        mockMvc.perform(get("/api/admin/dashboard/stats").with(user("1").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverStatus.application").value("unhealthy"))
                .andExpect(jsonPath("$.serverStatus.auth").value("healthy"))
                .andExpect(jsonPath("$.serverStatus.db").value("healthy"))
                .andExpect(jsonPath("$.serverStatus.chat").doesNotExist())
                .andExpect(jsonPath("$.serverStatus.recipe").doesNotExist())
                .andExpect(jsonPath("$.serverStatus.payment").doesNotExist());
    }

    @Test
    void adminStatsUsesConfiguredClockForDailyBoundaries() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-07-05T15:30:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));

        mockMvc.perform(get("/api/admin/dashboard/stats").with(user("1").roles("ADMIN")))
                .andExpect(status().isOk());

        LocalDate expectedToday = LocalDate.of(2026, 7, 6);
        LocalDateTime expectedTodayStart = LocalDateTime.of(2026, 7, 6, 0, 0);
        LocalDateTime expectedTodayEnd = LocalDateTime.of(2026, 7, 7, 0, 0);

        verify(userRepository).countByCreatedAtAfter(expectedTodayStart);
        verify(activityLogRepository).countByActivityDate(expectedToday);
        verify(paymentRepository).countByPaidAtBetweenAndStatus(expectedTodayStart, expectedTodayEnd, "paid");
        verify(paymentRepository).sumAmountByPaidAtBetweenAndStatus(expectedTodayStart, expectedTodayEnd, "paid");
    }

    @Test
    void adminStatsFormatsDailyPaymentStatsSafely() throws Exception {
        when(paymentRepository.findDailyPaymentStats(any(LocalDateTime.class))).thenReturn(Arrays.asList(
                new Object[] { LocalDate.of(2026, 7, 3), 2L, 9900L },
                null,
                new Object[] { "bad", null, "ignored" }));

        mockMvc.perform(get("/api/admin/dashboard/stats").with(user("1").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyPaymentStats[0].date").value("07-03"))
                .andExpect(jsonPath("$.dailyPaymentStats[0].count").value(2))
                .andExpect(jsonPath("$.dailyPaymentStats[0].amount").value(9900))
                .andExpect(jsonPath("$.dailyPaymentStats[1].date").value("??-??"))
                .andExpect(jsonPath("$.dailyPaymentStats[2].date").value("bad"))
                .andExpect(jsonPath("$.dailyPaymentStats[2].count").value(0))
                .andExpect(jsonPath("$.dailyPaymentStats[2].amount").value(0));
    }
}
