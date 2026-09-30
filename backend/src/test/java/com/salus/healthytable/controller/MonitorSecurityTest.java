package com.salus.healthytable.controller;

import com.salus.healthytable.config.CoopHeaderFilter;
import com.salus.healthytable.config.SecurityConfig;
import com.salus.healthytable.repository.ActivityLogRepository;
import com.salus.healthytable.repository.PaymentRepository;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.ApiSecurityErrorHandler;
import com.salus.healthytable.security.DisplayTokenFilter;
import com.salus.healthytable.security.IpWhitelistFilter;
import com.salus.healthytable.security.JwtAuthenticationFilter;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.DashboardStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관제 화면 API(/api/monitor)의 보안 테스트입니다.
 * 디스플레이 토큰만 통하고, 사용자·관리자 인증과 서로 섞이지 않는지 확인합니다.
 */
@WebMvcTest({MonitorController.class, AdminDashboardController.class})
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        CoopHeaderFilter.class,
        IpWhitelistFilter.class,
        ApiSecurityErrorHandler.class,
        DashboardStatsService.class
})
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:3000",
        "app.admin.ip-whitelist.enabled=false",
        // sha256("display-token-for-test")
        "app.monitor.display-token-hashes=" + MonitorSecurityTest.TOKEN_HASH
})
class MonitorSecurityTest {

    static final String TOKEN = "display-token-for-test";
    static final String TOKEN_HASH = "28af719799176cd471cf8a61e13ffb264417080dcc68246f0229020c9d36da76";

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

    @BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-01T03:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
    }

    // 등록된 디스플레이 토큰이면 집계 통계를 받을 수 있어야 합니다.
    @Test
    void displayTokenCanReadStats() throws Exception {
        mockMvc.perform(get("/api/monitor/stats").header(DisplayTokenFilter.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").exists());
    }

    // 토큰이 없거나 등록되지 않은 토큰이면 401이어야 합니다.
    @Test
    void missingOrUnknownDisplayTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/monitor/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/monitor/stats").header(DisplayTokenFilter.HEADER, "guessed-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(userRepository, paymentRepository);
    }

    // 사용자 JWT는 관제 경로에서 검사조차 되지 않고 401이어야 합니다(인증 수단 분리).
    @Test
    void userJwtIsNotAcceptedOnMonitorPath() throws Exception {
        mockMvc.perform(get("/api/monitor/stats").header("Authorization", "Bearer user-jwt"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(jwtTokenProvider);
    }

    // 관리자 권한을 가진 인증이라도 관제 경로는 MONITOR 권한만 허용해야 합니다.
    @Test
    void adminRoleIsNotAcceptedOnMonitorPath() throws Exception {
        mockMvc.perform(get("/api/monitor/stats").with(user("1").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    // 디스플레이 토큰은 조회(GET)만 허용되어야 합니다.
    @Test
    void displayTokenCannotWrite() throws Exception {
        mockMvc.perform(post("/api/monitor/stats").header(DisplayTokenFilter.HEADER, TOKEN))
                .andExpect(status().isForbidden());
    }

    // 디스플레이 토큰으로는 관리자 API와 사용자 API를 호출할 수 없어야 합니다.
    @Test
    void displayTokenIsNotAcceptedOutsideMonitorPath() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/stats").header(DisplayTokenFilter.HEADER, TOKEN))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me").header(DisplayTokenFilter.HEADER, TOKEN))
                .andExpect(status().isUnauthorized());
    }
}
