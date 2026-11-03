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
import com.salus.healthytable.security.RecentIdentityConfirmation;
import com.salus.healthytable.service.AccountDeletionGuard;
import com.salus.healthytable.service.KakaoUnlinkClient;
import com.salus.healthytable.service.PaymentService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 계정 API(데이터 요약, 회원 탈퇴) 보안/응답 테스트입니다.
 */
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

    @MockBean
    private AccountDeletionGuard accountDeletionGuard;

    @MockBean
    private RecentIdentityConfirmation recentIdentityConfirmation;

    @MockBean
    private PaymentService paymentService;

    @MockBean
    private KakaoUnlinkClient kakaoUnlinkClient;

    @MockBean
    private com.salus.healthytable.service.MonthlyAnalysisGuard monthlyAnalysisGuard;

    // 로그인하지 않으면 데이터 요약을 볼 수 없어야 합니다.
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

    // 로그인하지 않으면 회원 탈퇴를 할 수 없어야 합니다.
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

    // 로그인 사용자는 자신의 데이터 요약을 볼 수 있어야 합니다.
    @Test
    void authenticatedUserCanReadOwnDataSummary() throws Exception {
        when(userAccountService.summarizeUserData(1L)).thenReturn(UserDataSummaryDTO.builder()
                .healthProfiles(1)
                .healthCheckups(2)
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
                .userRecipes(14)
                .build());

        mockMvc.perform(get("/api/users/me/data-summary").with(authentication(userAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthProfiles").value(1))
                .andExpect(jsonPath("$.healthCheckups").value(2))
                .andExpect(jsonPath("$.mealLogs").value(4))
                .andExpect(jsonPath("$.recommendations").value(5))
                .andExpect(jsonPath("$.activityLogs").value(6))
                .andExpect(jsonPath("$.communityPosts").value(7))
                .andExpect(jsonPath("$.comments").value(8))
                .andExpect(jsonPath("$.likes").value(9))
                .andExpect(jsonPath("$.recipeShares").value(10))
                .andExpect(jsonPath("$.payments").value(11))
                .andExpect(jsonPath("$.chatSessions").value(12))
                .andExpect(jsonPath("$.chatMessages").value(13))
                .andExpect(jsonPath("$.userRecipes").value(14));

        verify(userAccountService).summarizeUserData(1L);
    }

    // 본인 확인을 통과한 로그인 사용자는 자신의 계정을 삭제할 수 있고, 삭제 뒤 카카오 연결 끊기를 시도해야 합니다.
    @Test
    void authenticatedUserCanDeleteOwnAccount() throws Exception {
        when(recentIdentityConfirmation.isRecent(any())).thenReturn(true);
        when(accountDeletionGuard.check(eq(1L), eq(true), any())).thenReturn(AccountDeletionGuard.Result.OK);
        when(accountDeletionGuard.kakaoUserIds(1L)).thenReturn(List.of("k-1"));

        mockMvc.perform(delete("/api/users/me").with(authentication(userAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("계정과 개인 데이터가 삭제되었습니다."));

        verify(userAccountService).deleteAccount(1L);
        verify(kakaoUnlinkClient).unlink("k-1");
        verify(monthlyAnalysisGuard).forgetUser(1L);
    }

    // 최근 확인도 비밀번호·소셜 로그인 값도 없으면 지우지 않고 403 REAUTH_REQUIRED와 확인 수단을 알려야 합니다.
    // 401이면 앱이 로그인 만료로 보고 로그아웃시키므로 403이어야 합니다.
    @Test
    void deleteAccountWithoutReauthIsRejected() throws Exception {
        when(accountDeletionGuard.check(eq(1L), anyBoolean(), any())).thenReturn(AccountDeletionGuard.Result.REAUTH_REQUIRED);
        when(accountDeletionGuard.availableMethods(1L)).thenReturn(List.of("EMAIL", "KAKAO"));

        mockMvc.perform(delete("/api/users/me").with(authentication(userAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REAUTH_REQUIRED"))
                .andExpect(jsonPath("$.methods[0]").value("EMAIL"))
                .andExpect(jsonPath("$.methods[1]").value("KAKAO"));

        verify(userAccountService, never()).deleteAccount(anyLong());
        verifyNoInteractions(kakaoUnlinkClient);
    }

    // 본인 확인이 틀리면 지우지 않습니다.
    @Test
    void deleteAccountWithWrongProofIsRejected() throws Exception {
        when(accountDeletionGuard.check(eq(1L), anyBoolean(), any())).thenReturn(AccountDeletionGuard.Result.INVALID);

        mockMvc.perform(delete("/api/users/me").with(authentication(userAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REAUTH_FAILED"));

        verify(userAccountService, never()).deleteAccount(anyLong());
    }

    // 확인 중인 결제가 있으면 결제만 되고 PLUS를 받을 회원이 사라지지 않게 탈퇴를 막아야 합니다.
    @Test
    void deleteAccountIsBlockedWhilePaymentIsInProgress() throws Exception {
        when(accountDeletionGuard.check(eq(1L), anyBoolean(), any())).thenReturn(AccountDeletionGuard.Result.OK);
        when(paymentService.hasPaymentInProgress(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/users/me").with(authentication(userAuthentication())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_IN_PROGRESS"));

        verify(userAccountService, never()).deleteAccount(anyLong());
    }

    // 사전 확인 뒤 삭제 트랜잭션에서 미완료 주문이 보이면(사이에 생긴 주문) 같은 409로 알리고 카카오 연결도 끊지 않습니다.
    @Test
    void deleteAccountReportsPaymentFoundInsideDeletionTransaction() throws Exception {
        when(accountDeletionGuard.check(eq(1L), anyBoolean(), any())).thenReturn(AccountDeletionGuard.Result.OK);
        when(accountDeletionGuard.kakaoUserIds(1L)).thenReturn(List.of("k-1"));
        doThrow(new UserAccountService.PaymentInProgressException()).when(userAccountService).deleteAccount(1L);

        mockMvc.perform(delete("/api/users/me").with(authentication(userAuthentication())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_IN_PROGRESS"));

        verifyNoInteractions(kakaoUnlinkClient, monthlyAnalysisGuard);
    }

    // 삭제할 사용자가 없으면 404 JSON 오류여야 합니다.
    @Test
    void deleteAccountMissingUserReturnsJsonNotFound() throws Exception {
        when(accountDeletionGuard.check(eq(1L), anyBoolean(), any())).thenReturn(AccountDeletionGuard.Result.OK);
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

    // 테스트용 로그인 사용자 인증 정보를 만듭니다.
    private UsernamePasswordAuthenticationToken userAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
