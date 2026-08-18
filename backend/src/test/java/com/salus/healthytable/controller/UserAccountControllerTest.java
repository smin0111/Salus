package com.salus.healthytable.controller;

import com.salus.healthytable.config.CoopHeaderFilter;
import com.salus.healthytable.config.SecurityConfig;
import com.salus.healthytable.dto.UserDataSummaryDTO;
import com.salus.healthytable.exception.GlobalExceptionHandler;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.ApiSecurityErrorHandler;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.IpWhitelistFilter;
import com.salus.healthytable.security.JwtAuthenticationFilter;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserAccountController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        CoopHeaderFilter.class,
        IpWhitelistFilter.class,
        ApiSecurityErrorHandler.class,
        AuthenticatedUserProvider.class,
        GlobalExceptionHandler.class
})
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:3000",
        "app.admin.ip-whitelist.enabled=false"
})
class UserAccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserAccountService userAccountService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserRepository userRepository;

    @Test
    void unauthenticatedUserCannotReadDataSummary() throws Exception {
        mockMvc.perform(get("/api/users/me/data-summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.path").value("/api/users/me/data-summary"));

        verifyNoInteractions(userAccountService);
    }

    @Test
    void unauthenticatedUserCannotDeleteAccount() throws Exception {
        mockMvc.perform(delete("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.path").value("/api/users/me"));

        verifyNoInteractions(userAccountService);
    }

    @Test
    void authenticatedUserCanReadOwnDataSummary() throws Exception {
        when(userAccountService.summarizeUserData(1L)).thenReturn(UserDataSummaryDTO.builder()
                .healthProfiles(1)
                .healthCheckups(2)
                .fridgeItems(3)
                .mealLogs(4)
                .recommendations(5)
                .activityLogs(6)
                .communityPosts(7)
                .comments(8)
                .likes(9)
                .recipeShares(10)
                .payments(11)
                .chatSessions(12)
                .chatMessages(13)
                .build());

        mockMvc.perform(get("/api/users/me/data-summary").with(authentication(userAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthProfiles").value(1))
                .andExpect(jsonPath("$.healthCheckups").value(2))
                .andExpect(jsonPath("$.fridgeItems").value(3))
                .andExpect(jsonPath("$.mealLogs").value(4))
                .andExpect(jsonPath("$.recommendations").value(5))
                .andExpect(jsonPath("$.activityLogs").value(6))
                .andExpect(jsonPath("$.communityPosts").value(7))
                .andExpect(jsonPath("$.comments").value(8))
                .andExpect(jsonPath("$.likes").value(9))
                .andExpect(jsonPath("$.recipeShares").value(10))
                .andExpect(jsonPath("$.payments").value(11))
                .andExpect(jsonPath("$.chatSessions").value(12))
                .andExpect(jsonPath("$.chatMessages").value(13));

        verify(userAccountService).summarizeUserData(1L);
    }

    @Test
    void authenticatedUserCanDeleteOwnAccount() throws Exception {
        mockMvc.perform(delete("/api/users/me").with(authentication(userAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계정과 개인 데이터가 삭제되었습니다."));

        verify(userAccountService).deleteAccount(1L);
    }

    @Test
    void deleteAccountMissingUserReturnsJsonNotFound() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."))
                .when(userAccountService).deleteAccount(1L);

        mockMvc.perform(delete("/api/users/me").with(authentication(userAuthentication())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.path").value("/api/users/me"));
    }

    private UsernamePasswordAuthenticationToken userAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
