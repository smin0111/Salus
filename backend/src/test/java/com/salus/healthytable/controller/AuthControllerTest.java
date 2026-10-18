package com.salus.healthytable.controller;

import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.LoginRequestDTO;
import com.salus.healthytable.dto.RefreshTokenRequestDTO;
import com.salus.healthytable.dto.UserResponseDTO;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.AppleIdentityTokenVerifier;
import com.salus.healthytable.service.AuthNonceService;
import com.salus.healthytable.service.GoogleIdTokenVerifier;
import com.salus.healthytable.service.KakaoWebAuthService;
import com.salus.healthytable.service.SocialCredentialVerifier;
import com.salus.healthytable.service.OAuthService;
import com.salus.healthytable.service.OAuthVerificationException;
import com.salus.healthytable.service.RefreshTokenService;
import com.salus.healthytable.service.AccountLinkException;
import com.salus.healthytable.service.EmailSignupLock;
import com.salus.healthytable.service.SocialLoginService;
import com.salus.healthytable.service.SocialLoginTxHelper;
import com.salus.healthytable.service.VerifiedSocialIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthController} 테스트입니다. 소셜 로그인 성공/실패 응답과 실패 원인 로그를 확인합니다.
 * 회원 조회/생성 판정은 SocialLoginTxHelperTest에서 확인합니다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthControllerTest {

    private final KakaoWebAuthService kakaoWebAuthService = mock(KakaoWebAuthService.class);
    private final GoogleIdTokenVerifier googleIdTokenVerifier = mock(GoogleIdTokenVerifier.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final OAuthService oAuthService = mock(OAuthService.class);
    private final SocialLoginService socialLoginService = mock(SocialLoginService.class);
    private final AppleIdentityTokenVerifier appleIdentityTokenVerifier = mock(AppleIdentityTokenVerifier.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final AuthNonceService authNonceService = mock(AuthNonceService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    // 실제 검증기(최근 인증 판단 포함)에 제공자 검증 부분만 가짜로 넣습니다.
    private final SocialCredentialVerifier socialCredentialVerifier =
            new SocialCredentialVerifier(oAuthService, appleIdentityTokenVerifier, googleIdTokenVerifier, kakaoWebAuthService, authNonceService, clock);
    private final AuthController controller = new AuthController(
            userRepository,
            jwtTokenProvider,
            authenticatedUserProvider,
            socialCredentialVerifier,
            socialLoginService,
            refreshTokenService,
            authNonceService,
            kakaoWebAuthService,
            clock);

    // 검증된 소셜 계정으로 정해진 회원의 ID로 JWT를 발급하고 회원 정보를 함께 응답해야 합니다.
    @Test
    @SuppressWarnings("unchecked")
    void googleLoginIssuesTokenForResolvedUser() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("valid-token");
        VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
                SocialProvider.GOOGLE, "google-sub", "new@example.com", true, "새 사용자");
        User user = new User();
        user.setId(7L);
        user.setEmail("new@example.com");
        user.setName("새 사용자");

        when(oAuthService.verifyGoogle("valid-token")).thenReturn(identity);
        when(socialLoginService.login(identity, null, null)).thenReturn(new SocialLoginTxHelper.LoggedIn(user, null));
        when(jwtTokenProvider.createToken(eq("7"), any())).thenReturn("jwt-token");
        when(refreshTokenService.issue(eq(7L), any())).thenReturn("refresh-token");

        ResponseEntity<?> response = controller.loginGoogle(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body)
                .containsEntry("token", "jwt-token")
                .containsEntry("refreshToken", "refresh-token");
        assertThat(((UserResponseDTO) body.get("user")).getId()).isEqualTo(7L);
    }

    // 액세스 토큰이 없으면 OAuth 서비스를 호출하지 않고 401과 실패 원인 로그를 남겨야 합니다.
    @Test
    void googleLoginWithoutAccessTokenDoesNotCallOAuth(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();

        ResponseEntity<?> response = controller.loginGoogle(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/google");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=google, reason=access_token_missing");
        verifyNoInteractions(oAuthService, socialLoginService, jwtTokenProvider, refreshTokenService);
    }

    // 구글 토큰 검증 실패는 일관된 401 JSON 오류여야 하고, 로그에는 예외 클래스 이름만 남겨야 합니다.
    @Test
    void googleLoginFailureReturnsConsistentJsonError(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("invalid-token");
        when(oAuthService.verifyGoogle("invalid-token")).thenThrow(new RuntimeException("provider rejected token"));

        ResponseEntity<?> response = controller.loginGoogle(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/google");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=google, reason=exception_RuntimeException")
                .doesNotContain("invalid-token");
        verifyNoInteractions(socialLoginService, jwtTokenProvider);
    }

    // 다른 앱에 발급된 토큰(aud 불일치)은 회원 조회 없이 401이어야 하고, 원인 코드만 로그에 남아야 합니다.
    @Test
    void verificationFailureDoesNotResolveUser(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("other-app-token");
        when(oAuthService.verifyGoogle("other-app-token"))
                .thenThrow(new OAuthVerificationException("audience_mismatch"));

        ResponseEntity<?> response = controller.loginGoogle(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/google");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=google, reason=audience_mismatch")
                .doesNotContain("other-app-token");
        verifyNoInteractions(socialLoginService, jwtTokenProvider);
    }

    // 네이버 코드 교환 결과에 액세스 토큰이 없으면 401이어야 하고, 요청 값은 로그에 남지 않아야 합니다.
    @Test
    void naverLoginWithoutAccessTokenReturnsConsistentJsonError(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setCode("auth-code");
        request.setState("state");
        request.setRedirectUri("salus://redirect");
        when(oAuthService.verifyNaver("auth-code", "state", "salus://redirect"))
                .thenThrow(new OAuthVerificationException("access_token_missing"));

        ResponseEntity<?> response = controller.loginNaver(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/naver");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=naver, reason=access_token_missing")
                .doesNotContain("auth-code")
                .doesNotContain("salus://redirect");
        verifyNoInteractions(socialLoginService, jwtTokenProvider);
    }

    // Apple 로그인은 identity token, 원문 nonce, 이름을 검증기로 넘기고, 서버 발급 nonce를 소비하면
    // 이번 요청의 계정 확인으로 인정해 identity_confirmed_at이 담긴 JWT를 발급해야 합니다.
    @Test
    void appleLoginPassesTokenNonceAndNameToVerifier() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setIdentityToken("identity-token");
        request.setNonce("raw-nonce");
        request.setFullName("홍길동");
        VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
                SocialProvider.APPLE, "apple-sub", null, false, "홍길동");
        User user = new User();
        user.setId(9L);

        when(appleIdentityTokenVerifier.verifyLogin("identity-token", "raw-nonce", "홍길동")).thenReturn(identity);
        when(authNonceService.consume("raw-nonce")).thenReturn(true);
        Instant now = Instant.parse("2026-10-05T03:00:00Z");
        when(socialLoginService.login(identity.withIdentityConfirmedAt(now), null, null))
                .thenReturn(new SocialLoginTxHelper.LoggedIn(user, null));
        when(jwtTokenProvider.createToken(eq("9"), any())).thenReturn("jwt-token");
        when(refreshTokenService.issue(eq(9L), any())).thenReturn("refresh-token");

        ResponseEntity<?> response = controller.loginApple(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(jwtTokenProvider).createToken("9", now);
    }

    // nonce가 없는 Apple 로그인은 재사용 방지를 확인할 수 없으므로 검증기 호출 없이 실패해야 합니다.
    @Test
    void appleLoginWithoutNonceDoesNotCallVerifier(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setIdentityToken("identity-token");

        ResponseEntity<?> response = controller.loginApple(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/apple");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=apple, reason=nonce_missing")
                .doesNotContain("identity-token");
        verifyNoInteractions(appleIdentityTokenVerifier, socialLoginService, jwtTokenProvider);
    }

    // 네이버 인가 코드가 없으면 OAuth 호출 없이 실패해야 합니다.
    @Test
    void naverLoginWithoutCodeDoesNotCallOAuth(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setState("state");
        request.setRedirectUri("salus://redirect");

        ResponseEntity<?> response = controller.loginNaver(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/naver");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=naver, reason=code_missing")
                .doesNotContain("state")
                .doesNotContain("salus://redirect");
        verifyNoInteractions(oAuthService, socialLoginService, jwtTokenProvider, refreshTokenService);
    }

    // 네이버 state 값이 없으면 OAuth 호출 없이 실패해야 합니다.
    @Test
    void naverLoginWithoutStateDoesNotCallOAuth(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setCode("auth-code");
        request.setRedirectUri("salus://redirect");

        ResponseEntity<?> response = controller.loginNaver(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/naver");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=naver, reason=state_missing")
                .doesNotContain("auth-code")
                .doesNotContain("salus://redirect");
        verifyNoInteractions(oAuthService, socialLoginService, jwtTokenProvider, refreshTokenService);
    }

    // 같은 검증 이메일의 기존 회원이 있으면 토큰을 발급하지 않고 409 LINK_REQUIRED와 연결 요청 ID를 응답해야 합니다.
    @Test
    @SuppressWarnings("unchecked")
    void linkRequiredReturnsConflictWithoutTokens() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("valid-token");
        request.setFlowNonce("flow-nonce");
        VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
                SocialProvider.KAKAO, "kakao-1", "se@gmail.com", true, "사용자");
        when(oAuthService.verifyKakao("valid-token")).thenReturn(identity);
        when(socialLoginService.login(identity, null, "flow-nonce")).thenReturn(
                new SocialLoginTxHelper.LinkRequired("link-token", java.util.List.of(SocialProvider.GOOGLE)));

        ResponseEntity<?> response = controller.loginKakao(request);

        assertErrorResponse(response, 409, "LINK_REQUIRED",
                "이미 같은 이메일로 가입된 계정이 있습니다. 기존 계정으로 로그인하면 이 로그인 수단도 연결됩니다.", "/api/auth/kakao");
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("linkRequestId", "link-token")
                .containsEntry("providers", java.util.List.of(SocialProvider.GOOGLE))
                .doesNotContainKey("token");
        verifyNoInteractions(jwtTokenProvider, refreshTokenService);
    }

    // 재인증으로 연결이 끝나면 토큰과 함께 새로 연결된 로그인 수단을 알려야 합니다.
    @Test
    @SuppressWarnings("unchecked")
    void completedLinkReturnsTokensAndLinkedProvider() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("google-token");
        request.setLinkRequestId("link-token");
        request.setFlowNonce("flow-nonce");
        VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
                SocialProvider.GOOGLE, "google-sub", "se@gmail.com", true, "사용자");
        User user = new User();
        user.setId(10L);
        when(oAuthService.verifyGoogle("google-token")).thenReturn(identity);
        when(socialLoginService.login(identity, "link-token", "flow-nonce"))
                .thenReturn(new SocialLoginTxHelper.LoggedIn(user, SocialProvider.KAKAO));
        when(jwtTokenProvider.createToken(eq("10"), any())).thenReturn("jwt");
        when(refreshTokenService.issue(eq(10L), any())).thenReturn("refresh");

        ResponseEntity<?> response = controller.loginGoogle(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<String, Object>) response.getBody())
                .containsEntry("token", "jwt")
                .containsEntry("linkedProvider", SocialProvider.KAKAO);
    }

    // 후보를 하나로 정할 수 없으면 토큰 없이 409 ACCOUNT_AMBIGUOUS여야 합니다.
    @Test
    void ambiguousAccountReturnsConflict() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("valid-token");
        VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
                SocialProvider.KAKAO, "kakao-1", "se@gmail.com", true, "사용자");
        when(oAuthService.verifyKakao("valid-token")).thenReturn(identity);
        when(socialLoginService.login(identity, null, null)).thenReturn(new SocialLoginTxHelper.Ambiguous());

        ResponseEntity<?> response = controller.loginKakao(request);

        assertErrorResponse(response, 409, "ACCOUNT_AMBIGUOUS",
                "같은 이메일로 가입된 계정을 하나로 정할 수 없습니다. 기존에 사용하던 방법으로 로그인해 주세요.", "/api/auth/kakao");
        verifyNoInteractions(jwtTokenProvider, refreshTokenService);
    }

    // 연결 실패와 잠금 실패는 각자의 코드로 응답하고, 일반 로그인 실패(401)로 뭉개지지 않아야 합니다.
    @Test
    void linkAndLockFailuresKeepTheirCodes() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("valid-token");
        VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
                SocialProvider.GOOGLE, "google-sub", "se@gmail.com", true, "사용자");
        when(oAuthService.verifyGoogle("valid-token")).thenReturn(identity);

        when(socialLoginService.login(identity, null, null)).thenThrow(AccountLinkException.targetMismatch());
        assertThat(controller.loginGoogle(request).getStatusCode().value()).isEqualTo(409);

        org.mockito.Mockito.reset(socialLoginService);
        when(socialLoginService.login(identity, null, null)).thenThrow(new EmailSignupLock.SignupBusyException());
        assertErrorResponse(controller.loginGoogle(request), 503, "SIGNUP_BUSY", "잠시 후 다시 시도해 주세요.", "/api/auth/google");
        verifyNoInteractions(refreshTokenService);
    }

    // 연결 취소는 서버의 연결 요청을 무효화하고 204를 응답해야 합니다.
    @Test
    void cancelLinkInvalidatesServerRequest() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setLinkRequestId("link-token");
        request.setFlowNonce("flow-nonce");

        assertThat(controller.cancelLink(request).getStatusCode().value()).isEqualTo(204);
        verify(socialLoginService).cancelLink("link-token", "flow-nonce");
    }

    // 네이버(일회용 인가 코드)는 이번 요청의 계정 확인으로 인정되어 identity_confirmed_at이 토큰과 refresh token에 들어가야 합니다.
    @Test
    void naverLoginRecordsIdentityConfirmedAt() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setCode("auth-code");
        request.setState("state");
        VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
                SocialProvider.NAVER, "naver-1", null, false, "네이버");
        User user = new User();
        user.setId(7L);
        Instant now = Instant.parse("2026-10-05T03:00:00Z");
        when(oAuthService.verifyNaver("auth-code", "state", null)).thenReturn(identity);
        when(socialLoginService.login(identity.withIdentityConfirmedAt(now), null, null))
                .thenReturn(new SocialLoginTxHelper.LoggedIn(user, null));
        when(jwtTokenProvider.createToken("7", now)).thenReturn("jwt");
        when(refreshTokenService.issue(7L, now)).thenReturn("refresh");

        assertThat(controller.loginNaver(request).getStatusCode().value()).isEqualTo(200);
        verify(jwtTokenProvider).createToken("7", now);
        verify(refreshTokenService).issue(7L, now);
    }

    // 구글 access token은 다시 제출할 수 있어 최근 인증으로 인정하지 않으므로 identity_confirmed_at이 없어야 합니다.
    @Test
    void googleLoginDoesNotRecordIdentityConfirmedAt() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("valid-token");
        VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
                SocialProvider.GOOGLE, "google-sub", "a@gmail.com", true, "구글");
        User user = new User();
        user.setId(7L);
        when(oAuthService.verifyGoogle("valid-token")).thenReturn(identity);
        when(socialLoginService.login(identity, null, null)).thenReturn(new SocialLoginTxHelper.LoggedIn(user, null));

        controller.loginGoogle(request);

        verify(jwtTokenProvider).createToken("7", null);
        verify(refreshTokenService).issue(7L, null);
    }

    // 서버가 발급하지 않았거나 이미 쓴 nonce의 Apple 토큰은 서명이 맞아도 로그인시키지 않아야 합니다(재사용 방지).
    @Test
    void appleLoginWithUnissuedNonceIsRejected(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setIdentityToken("identity-token");
        request.setNonce("client-made-nonce");
        when(appleIdentityTokenVerifier.verifyLogin("identity-token", "client-made-nonce", null)).thenReturn(
                new VerifiedSocialIdentity(SocialProvider.APPLE, "apple-sub", null, false, "Apple User"));
        when(authNonceService.consume("client-made-nonce")).thenReturn(false);

        ResponseEntity<?> response = controller.loginApple(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/apple");
        assertThat(output.getOut()).contains("reason=nonce_not_issued_or_used");
        verifyNoInteractions(socialLoginService, jwtTokenProvider);
    }

    // 카카오 콜백은 처리 결과(앱 주소)로 302 이동시켜야 합니다.
    @Test
    void kakaoCallbackRedirectsToApp() {
        when(kakaoWebAuthService.handleCallback("code", "state", null)).thenReturn("salus://kakao-web-auth?result=success");

        ResponseEntity<Void> response = controller.kakaoWebAuthCallback("code", "state", null);

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getFirst("Location")).isEqualTo("salus://kakao-web-auth?result=success");
    }

    // nonce 발급 API는 서버가 만든 일회용 값을 돌려줘야 합니다.
    @Test
    void issueNonceReturnsServerNonce() {
        when(authNonceService.issue()).thenReturn("server-nonce");

        assertThat(controller.issueNonce()).containsEntry("nonce", "server-nonce");
    }

    // 유효한 refresh token이면 새 access token과 교체된 refresh token을 함께 응답해야 합니다.
    @Test
    @SuppressWarnings("unchecked")
    void refreshReturnsNewAccessAndRotatedRefreshToken() {
        RefreshTokenRequestDTO request = new RefreshTokenRequestDTO();
        request.setRefreshToken("old-refresh");
        when(refreshTokenService.rotate("old-refresh")).thenReturn(
                new RefreshTokenService.RotationResult(RefreshTokenService.Status.ROTATED, 7L, "new-refresh"));
        when(jwtTokenProvider.createToken(eq("7"), any())).thenReturn("new-access");

        ResponseEntity<?> response = controller.refresh(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<String, Object>) response.getBody())
                .containsEntry("token", "new-access")
                .containsEntry("refreshToken", "new-refresh");
    }

    // 무효·재사용된 refresh token은 access token을 발급하지 않고 REFRESH_TOKEN_INVALID 401이어야 합니다.
    @Test
    void refreshWithRejectedTokenReturnsUnauthorized() {
        RefreshTokenRequestDTO request = new RefreshTokenRequestDTO();
        request.setRefreshToken("reused-refresh");
        when(refreshTokenService.rotate("reused-refresh")).thenReturn(
                new RefreshTokenService.RotationResult(RefreshTokenService.Status.REUSE_DETECTED, null, null));

        ResponseEntity<?> response = controller.refresh(request);

        assertErrorResponse(response, 401, "REFRESH_TOKEN_INVALID", "다시 로그인해 주세요.", "/api/auth/refresh");
        verifyNoInteractions(jwtTokenProvider);
    }

    // 로그아웃은 refresh token을 폐기하고 204를 응답해야 합니다.
    @Test
    void logoutRevokesRefreshToken() {
        RefreshTokenRequestDTO request = new RefreshTokenRequestDTO();
        request.setRefreshToken("refresh");

        ResponseEntity<Void> response = controller.logout(request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(refreshTokenService).revoke("refresh");
    }

    // 로그인하지 않은 상태의 내 정보 조회는 401 JSON 오류여야 합니다.
    @Test
    void unauthenticatedCurrentUserReturnsConsistentJsonError() {
        when(authenticatedUserProvider.requireUserId())
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));

        ResponseEntity<?> response = controller.getMyInfo();

        assertErrorResponse(response, 401, "UNAUTHORIZED", "로그인이 필요합니다.", "/api/users/me");
        verifyNoInteractions(userRepository);
    }

    // 토큰의 사용자가 DB에 없으면 404 JSON 오류여야 합니다.
    @Test
    void currentUserMissingFromDatabaseReturnsConsistentJsonError() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getMyInfo();

        assertErrorResponse(response, 404, "NOT_FOUND", "사용자를 찾을 수 없습니다.", "/api/users/me");
    }

    // 오류 응답의 상태 코드/error/message/path를 한 번에 검사하는 도우미입니다.
    @SuppressWarnings("unchecked")
    private void assertErrorResponse(ResponseEntity<?> response, int status, String error, String message, String path) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isInstanceOf(Map.class);

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body)
                .containsEntry("status", status)
                .containsEntry("error", error)
                .containsEntry("message", message)
                .containsEntry("path", path);
    }
}
