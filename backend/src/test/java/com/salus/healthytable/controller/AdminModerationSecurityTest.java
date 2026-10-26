package com.salus.healthytable.controller;

import com.salus.healthytable.config.CoopHeaderFilter;
import com.salus.healthytable.config.SecurityConfig;
import com.salus.healthytable.domain.AdminRole;
import com.salus.healthytable.dto.ModerationDto;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.ApiSecurityErrorHandler;
import com.salus.healthytable.security.IpWhitelistFilter;
import com.salus.healthytable.security.JwtAuthenticationFilter;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.ModerationService;
import com.salus.healthytable.service.adminauth.AdminPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 신고 검토 API의 보안 규칙 테스트입니다(실제 SecurityConfig 적용, 서비스는 Mock).
 * 조회는 ADMIN·ADMIN_VIEWER, 조치는 ADMIN만이고, 일반 사용자(USER 역할)와 비로그인은 막힙니다.
 */
@WebMvcTest(AdminModerationController.class)
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
class AdminModerationSecurityTest {

    private static final String ACTION_BODY = "{\"action\":\"HIDE\",\"note\":\"확인\",\"reportCutoffId\":3}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ModerationService moderationService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserRepository userRepository;

    private static UsernamePasswordAuthenticationToken admin(AdminRole role) {
        return new UsernamePasswordAuthenticationToken(new AdminPrincipal(7L, "운영자", role, "session"), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    // 비로그인 401, 일반 사용자 403이며 서비스에 닿지 않습니다.
    @Test
    void anonymousAndOrdinaryUsersAreBlocked() throws Exception {
        mockMvc.perform(get("/api/admin/moderation/queue")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/moderation/queue").with(user("1").roles("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/moderation/targets/FEED_POST/1/actions").with(user("1").roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(moderationService);
    }

    // ADMIN_VIEWER는 대기열을 볼 수 있지만 조치는 403입니다.
    @Test
    void viewerCanReadButCannotAct() throws Exception {
        when(moderationService.queue()).thenReturn(List.of());
        mockMvc.perform(get("/api/admin/moderation/queue").with(authentication(admin(AdminRole.ADMIN_VIEWER))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/moderation/targets/FEED_POST/1/actions")
                        .with(authentication(admin(AdminRole.ADMIN_VIEWER)))
                        .contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY))
                .andExpect(status().isForbidden());
        verify(moderationService).queue();
        org.mockito.Mockito.verify(moderationService, org.mockito.Mockito.never()).act(any(), any(), any(), any());
    }

    // ADMIN은 조치할 수 있고, 조치한 관리자 ID가 인증 정보에서 넘어갑니다(요청 본문에서 받지 않음).
    @Test
    void adminCanActWithOwnId() throws Exception {
        when(moderationService.act(eq(7L), eq("FEED_POST"), eq(1L), any()))
                .thenReturn(new ModerationDto.ActionResult(5L, 1, "HIDDEN"));
        mockMvc.perform(post("/api/admin/moderation/targets/FEED_POST/1/actions")
                        .with(authentication(admin(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY))
                .andExpect(status().isOk());
        verify(moderationService).act(eq(7L), eq("FEED_POST"), eq(1L), any());
    }
}
