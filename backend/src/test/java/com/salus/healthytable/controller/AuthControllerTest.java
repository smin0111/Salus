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
import com.salus.healthytable.service.OAuthService;
import com.salus.healthytable.service.OAuthVerificationException;
import com.salus.healthytable.service.RefreshTokenService;
import com.salus.healthytable.service.SocialLoginService;
import com.salus.healthytable.service.VerifiedSocialIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final OAuthService oAuthService = mock(OAuthService.class);
    private final SocialLoginService socialLoginService = mock(SocialLoginService.class);
    private final AppleIdentityTokenVerifier appleIdentityTokenVerifier = mock(AppleIdentityTokenVerifier.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final AuthController controller = new AuthController(
            userRepository,
            jwtTokenProvider,
            authenticatedUserProvider,
            oAuthService,
            socialLoginService,
            appleIdentityTokenVerifier,
            refreshTokenService);

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
        when(socialLoginService.login(identity)).thenReturn(user);
        when(jwtTokenProvider.createToken("7")).thenReturn("jwt-token");
        when(refreshTokenService.issue(7L)).thenReturn("refresh-token");

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

    // Apple 로그인은 identity token, 원문 nonce, 이름을 검증기로 넘기고 JWT를 발급해야 합니다.
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
        when(socialLoginService.login(identity)).thenReturn(user);
        when(jwtTokenProvider.createToken("9")).thenReturn("jwt-token");
        when(refreshTokenService.issue(9L)).thenReturn("refresh-token");

        ResponseEntity<?> response = controller.loginApple(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
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

    // 유효한 refresh token이면 새 access token과 교체된 refresh token을 함께 응답해야 합니다.
    @Test
    @SuppressWarnings("unchecked")
    void refreshReturnsNewAccessAndRotatedRefreshToken() {
        RefreshTokenRequestDTO request = new RefreshTokenRequestDTO();
        request.setRefreshToken("old-refresh");
        when(refreshTokenService.rotate("old-refresh")).thenReturn(
                new RefreshTokenService.RotationResult(RefreshTokenService.Status.ROTATED, 7L, "new-refresh"));
        when(jwtTokenProvider.createToken("7")).thenReturn("new-access");

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
