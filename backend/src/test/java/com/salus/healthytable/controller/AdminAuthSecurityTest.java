package com.salus.healthytable.controller;

import com.salus.healthytable.config.CoopHeaderFilter;
import com.salus.healthytable.config.SecurityConfig;
import com.salus.healthytable.domain.AdminRole;
import com.salus.healthytable.dto.AdminDashboardDTO;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.ApiSecurityErrorHandler;
import com.salus.healthytable.security.IpWhitelistFilter;
import com.salus.healthytable.security.JwtAuthenticationFilter;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.DashboardStatsService;
import com.salus.healthytable.service.adminauth.AdminAuthException;
import com.salus.healthytable.service.adminauth.AdminAuthService;
import com.salus.healthytable.service.adminauth.AdminLoginRateLimiter;
import com.salus.healthytable.service.adminauth.AdminPrincipal;
import com.salus.healthytable.service.adminauth.AdminSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 인증의 보안 테스트입니다. 사용자 토큰과 관리자 토큰이 서로의 API에서 거부되는지(상호 거부),
 * 관리자 역할별 권한이 맞는지 확인합니다.
 */
@WebMvcTest({AdminAuthController.class, AdminDashboardController.class})
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
class AdminAuthSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminAuthService adminAuthService;

    @MockBean
    private AdminSessionService adminSessionService;

    @MockBean
    private AdminLoginRateLimiter rateLimiter;

    @MockBean
    private DashboardStatsService dashboardStatsService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserRepository userRepository;

    // 사용자 JWT는 관리자 API에서 사용자 JWT 검사기가 아예 호출되지 않고 거부되어야 합니다.
    @Test
    void userJwtIsRejectedOnAdminApi() throws Exception {
        when(adminSessionService.authenticate("user-jwt")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/dashboard/stats").header("Authorization", "Bearer user-jwt"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(jwtTokenProvider, dashboardStatsService);
    }

    // 관리자 토큰은 사용자 API에서 거부되어야 합니다(사용자 JWT 키로 검증되지 않음).
    @Test
    void adminTokenIsRejectedOnUserApi() throws Exception {
        when(jwtTokenProvider.validateToken("admin-token")).thenReturn(false);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminSessionService);
    }

    // ADMIN_VIEWER는 조회는 되지만 변경 요청은 403이어야 합니다.
    @Test
    void viewerCanReadButNotWrite() throws Exception {
        authenticateAs(AdminRole.ADMIN_VIEWER);
        when(dashboardStatsService.getStats()).thenReturn(AdminDashboardDTO.builder().totalUsers(3).build());

        mockMvc.perform(get("/api/admin/dashboard/stats").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(3));
        mockMvc.perform(post("/api/admin/dashboard/stats").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
    }

    // ADMIN은 변경 요청도 권한 검사를 통과해야 합니다. 이 경로에는 POST 핸들러가 없으므로
    // 인증·인가 실패(401/403)만 아니면 통과한 것입니다(메서드 불일치 응답 코드는 GlobalExceptionHandler가 정함).
    @Test
    void adminPassesWriteAuthorization() throws Exception {
        authenticateAs(AdminRole.ADMIN);

        mockMvc.perform(post("/api/admin/dashboard/stats").header("Authorization", "Bearer admin-token"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    // 만료·강제 종료된 관리자 토큰은 401이어야 합니다.
    @Test
    void invalidAdminTokenIsUnauthorized() throws Exception {
        when(adminSessionService.authenticate("expired")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer expired"))
                .andExpect(status().isUnauthorized());
    }

    // 로그인 단계 API는 인증 없이 호출할 수 있고, 오래된 토큰이 붙어 와도 검사하지 않아야 합니다.
    @Test
    void loginStepIsPublicAndReturnsChallenge() throws Exception {
        when(adminAuthService.login("alice", "pw"))
                .thenReturn(new AdminAuthService.Challenge("TOTP", "challenge-token"));

        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Authorization", "Bearer stale-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("TOTP"))
                .andExpect(jsonPath("$.challengeToken").value("challenge-token"));
        verify(rateLimiter).recordAttempt(anyString());
        verifyNoInteractions(adminSessionService);
    }

    // 로그인 실패는 코드와 상태로 구분되어 응답되어야 합니다(잠금은 429).
    @Test
    void lockedAccountReturnsTooManyRequests() throws Exception {
        when(adminAuthService.login("alice", "pw")).thenThrow(AdminAuthException.locked());

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pw\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("TOO_MANY_ATTEMPTS"));
    }

    // IP 기준 시도 제한을 넘으면 서비스 호출 없이 429여야 합니다.
    @Test
    void ipRateLimitBlocksBeforeCheckingPassword() throws Exception {
        doThrow(AdminAuthException.locked()).when(rateLimiter).recordAttempt(anyString());

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pw\"}"))
                .andExpect(status().isTooManyRequests());
        verifyNoInteractions(adminAuthService);
    }

    // 로그아웃은 현재 세션을 서버에서 끝내야 합니다.
    @Test
    void logoutRevokesCurrentSession() throws Exception {
        authenticateAs(AdminRole.ADMIN_VIEWER);

        mockMvc.perform(post("/api/admin/auth/logout").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNoContent());
        verify(adminSessionService).revoke("session-1");
    }

    private void authenticateAs(AdminRole role) {
        when(adminSessionService.authenticate("admin-token"))
                .thenReturn(Optional.of(new AdminPrincipal(1L, "alice", role, "session-1")));
    }
}
