package com.salus.healthytable.controller;

import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.dto.LoginRequestDTO;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.AccountLinkException;
import com.salus.healthytable.service.AppleIdentityTokenVerifier;
import com.salus.healthytable.service.AuthNonceService;
import com.salus.healthytable.service.GoogleIdTokenVerifier;
import com.salus.healthytable.service.KakaoWebAuthService;
import com.salus.healthytable.service.LoginMethodService;
import com.salus.healthytable.service.OAuthService;
import com.salus.healthytable.service.RefreshTokenService;
import com.salus.healthytable.service.SocialCredentialVerifier;
import com.salus.healthytable.service.VerifiedSocialIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link LoginMethodController} 테스트입니다. 최근 인증 없이는 로그인 수단을 추가할 수 없고,
 * 재인증으로 다른 회원 세션이 되지 않는지 확인합니다.
 */
class LoginMethodControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");

    private final KakaoWebAuthService kakaoWebAuthService = mock(KakaoWebAuthService.class);
    private final GoogleIdTokenVerifier googleIdTokenVerifier = mock(GoogleIdTokenVerifier.class);
    private final AuthenticatedUserProvider userProvider = mock(AuthenticatedUserProvider.class);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final OAuthService oAuthService = mock(OAuthService.class);
    private final LoginMethodService loginMethodService = mock(LoginMethodService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    private final SocialCredentialVerifier verifier = new SocialCredentialVerifier(oAuthService, mock(AppleIdentityTokenVerifier.class), googleIdTokenVerifier, kakaoWebAuthService, mock(AuthNonceService.class), clock);
    private final LoginMethodController controller = new LoginMethodController(
            userProvider, jwtTokenProvider, verifier, loginMethodService, refreshTokenService, kakaoWebAuthService, clock);

    private final MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/users/me/login-methods/kakao");
    private final VerifiedSocialIdentity kakao = new VerifiedSocialIdentity(SocialProvider.KAKAO, "k1", null, false, "카카오");

    @BeforeEach
    void setUp() {
        when(userProvider.requireUserId()).thenReturn(10L);
        when(jwtTokenProvider.resolveToken(any())).thenReturn("access");
    }

    // 최근 10분 안에 실제로 로그인했으면 로그인 수단을 추가할 수 있어야 합니다.
    @Test
    void recentAuthAllowsAdding() {
        when(jwtTokenProvider.getIdentityConfirmedAt("access")).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(5))));
        when(oAuthService.verifyKakao("kakao-token")).thenReturn(kakao);

        ResponseEntity<?> response = controller.add("kakao", accessToken("kakao-token"), http);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(loginMethodService).add(10L, kakao);
    }

    // identity_confirmed_at이 오래됐거나 없으면(갱신만 거친 세션) 제공자를 호출하지도 않고 REAUTH_REQUIRED여야 합니다.
    @Test
    void staleOrMissingIdentityConfirmedAtRequiresReauth() {
        when(jwtTokenProvider.getIdentityConfirmedAt("access")).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(11))));
        assertCode(controller.add("kakao", accessToken("kakao-token"), http), 401, "REAUTH_REQUIRED");

        when(jwtTokenProvider.getIdentityConfirmedAt("access")).thenReturn(Optional.empty());
        assertCode(controller.add("kakao", accessToken("kakao-token"), http), 401, "REAUTH_REQUIRED");

        verifyNoInteractions(oAuthService);
        verify(loginMethodService, never()).add(anyLong(), any());
    }

    // 다른 회원에 연결된 계정이면 409로 거부해야 합니다.
    @Test
    void accountOfAnotherUserIsRejected() {
        when(jwtTokenProvider.getIdentityConfirmedAt("access")).thenReturn(Optional.of(NOW));
        when(oAuthService.verifyKakao("kakao-token")).thenReturn(kakao);
        doThrow(AccountLinkException.alreadyLinked()).when(loginMethodService).add(10L, kakao);

        assertCode(controller.add("kakao", accessToken("kakao-token"), http), 409, "LINK_ALREADY_LINKED");
    }

    // 네이버(최근 인증 인정) + 현재 회원의 로그인 수단이면 identity_confirmed_at이 담긴 새 토큰을 발급해야 합니다.
    @Test
    void reauthWithOwnFreshMethodIssuesTokensWithIdentityConfirmedAt() {
        VerifiedSocialIdentity naver = new VerifiedSocialIdentity(SocialProvider.NAVER, "n1", null, false, "네이버");
        when(oAuthService.verifyNaver("code", "state", null)).thenReturn(naver);
        when(loginMethodService.isOwnLoginMethod(10L, naver.withIdentityConfirmedAt(NOW))).thenReturn(true);
        when(jwtTokenProvider.createToken("10", NOW)).thenReturn("fresh-access");
        when(refreshTokenService.issue(10L, NOW)).thenReturn("fresh-refresh");

        ResponseEntity<?> response = controller.reauthenticate("naver", naverCode(), http);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("token", "fresh-access");
    }

    // 다른 회원(또는 미가입)의 로그인 수단으로는 재인증되지 않아야 합니다(세션이 다른 회원으로 바뀌지 않게).
    @Test
    void reauthWithAnotherUsersMethodIsRejected() {
        VerifiedSocialIdentity naver = new VerifiedSocialIdentity(SocialProvider.NAVER, "n1", null, false, "네이버");
        when(oAuthService.verifyNaver("code", "state", null)).thenReturn(naver);
        when(loginMethodService.isOwnLoginMethod(any(), any())).thenReturn(false);

        assertCode(controller.reauthenticate("naver", naverCode(), http), 409, "REAUTH_MISMATCH");
        verifyNoInteractions(refreshTokenService);
    }

    // 구글·카카오 access token은 최근 인증이 아니므로 재인증으로 쓸 수 없어야 합니다.
    @Test
    void reauthWithNonFreshProviderIsNotSupported() {
        when(oAuthService.verifyKakao("kakao-token")).thenReturn(kakao);

        assertCode(controller.reauthenticate("kakao", accessToken("kakao-token"), http), 400, "REAUTH_PROVIDER_NOT_SUPPORTED");
        verify(loginMethodService, never()).isOwnLoginMethod(any(), any());
        verifyNoInteractions(refreshTokenService);
    }

    // 알 수 없는 제공자 이름은 400이어야 합니다.
    @Test
    void unknownProviderIsBadRequest() {
        when(jwtTokenProvider.getIdentityConfirmedAt("access")).thenReturn(Optional.of(NOW));
        assertCode(controller.add("facebook", new LoginRequestDTO(), http), 400, "UNKNOWN_PROVIDER");
    }

    // 카카오 웹 재확인 설정이 없으면 503으로 안내해야 합니다.
    @Test
    void kakaoWebAuthStartReportsMissingConfiguration() {
        when(kakaoWebAuthService.start(10L)).thenThrow(new IllegalStateException("not configured"));
        assertCode(controller.startKakaoWebAuth(http), 503, "KAKAO_WEB_AUTH_UNAVAILABLE");
    }

    // 카카오 웹 재확인 결과(서버가 코드 교환으로 확인)로 같은 회원이면 재인증되어야 합니다.
    @Test
    void reauthWithKakaoWebResultIssuesTokens() {
        VerifiedSocialIdentity confirmed = kakao.withIdentityConfirmedAt(NOW);
        when(kakaoWebAuthService.complete(10L, "web-token")).thenReturn(confirmed);
        when(loginMethodService.isOwnLoginMethod(10L, confirmed)).thenReturn(true);
        when(jwtTokenProvider.createToken("10", NOW)).thenReturn("fresh");
        LoginRequestDTO request = new LoginRequestDTO();
        request.setWebAuthRequestToken("web-token");

        assertThat(controller.reauthenticate("kakao", request, http).getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(oAuthService);
    }

    private LoginRequestDTO accessToken(String token) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken(token);
        return request;
    }

    private LoginRequestDTO naverCode() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setCode("code");
        request.setState("state");
        return request;
    }

    @SuppressWarnings("unchecked")
    private void assertCode(ResponseEntity<?> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("error", code);
    }
}
