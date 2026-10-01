package com.salus.healthytable.controller;

import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.LoginRequestDTO;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.OAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthController} 테스트입니다. 소셜 로그인 성공/실패 응답과 실패 원인 로그를 확인합니다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthControllerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final OAuthService oAuthService = mock(OAuthService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    private final AuthController controller = new AuthController(
            userRepository,
            jwtTokenProvider,
            authenticatedUserProvider,
            oAuthService,
            clock);

    // 구글 로그인으로 처음 들어온 사용자는 주입한 Clock 기준 가입 시각으로 생성되어야 합니다.
    @Test
    void googleLoginCreatesNewUserWithConfiguredClock() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("valid-token");

        when(oAuthService.verifyGoogleToken("valid-token")).thenReturn(Map.of(
                "email", "new@example.com",
                "name", "새 사용자"));
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(7L);
            return user;
        });
        when(jwtTokenProvider.createToken("7")).thenReturn("jwt-token");

        ResponseEntity<?> response = controller.loginGoogle(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(saved.getEmail()).isEqualTo("new@example.com");
        assertThat(saved.getName()).isEqualTo("새 사용자");
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 6, 0, 30));
        assertThat(saved.getPassword()).isEmpty();
        verify(jwtTokenProvider).createToken("7");
    }

    // 액세스 토큰이 없으면 OAuth 서비스를 호출하지 않고 401과 실패 원인 로그를 남겨야 합니다.
    @Test
    void googleLoginWithoutAccessTokenDoesNotCallOAuth(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();

        ResponseEntity<?> response = controller.loginGoogle(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/google");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=google, reason=access_token_missing");
        verifyNoInteractions(oAuthService, userRepository, jwtTokenProvider);
    }

    // 구글 토큰 검증 실패는 일관된 401 JSON 오류여야 하고, 로그에는 예외 클래스 이름만 남겨야 합니다.
    @Test
    void googleLoginFailureReturnsConsistentJsonError(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("invalid-token");
        when(oAuthService.verifyGoogleToken("invalid-token")).thenThrow(new RuntimeException("provider rejected token"));

        ResponseEntity<?> response = controller.loginGoogle(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/google");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=google, reason=exception_RuntimeException")
                .doesNotContain("invalid-token");
    }

    // 이메일이 없는 구글 사용자 정보로는 회원을 만들지 않아야 합니다.
    @Test
    void googleLoginWithoutEmailDoesNotCreateUser(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("valid-token");
        when(oAuthService.verifyGoogleToken("valid-token")).thenReturn(Map.of("name", "Google User"));

        ResponseEntity<?> response = controller.loginGoogle(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/google");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=google, reason=email_missing")
                .doesNotContain("valid-token");
        verifyNoInteractions(userRepository, jwtTokenProvider);
    }

    // 네이버 코드 교환 결과에 액세스 토큰이 없으면 401이어야 합니다.
    @Test
    void naverLoginWithoutAccessTokenReturnsConsistentJsonError(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setCode("auth-code");
        request.setState("state");
        request.setRedirectUri("salus://redirect");
        when(oAuthService.exchangeNaverCode("auth-code", "state", "salus://redirect")).thenReturn(Map.of());

        ResponseEntity<?> response = controller.loginNaver(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/naver");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=naver, reason=access_token_missing")
                .doesNotContain("auth-code")
                .doesNotContain("state")
                .doesNotContain("salus://redirect");
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
        verifyNoInteractions(oAuthService, userRepository, jwtTokenProvider);
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
        verifyNoInteractions(oAuthService, userRepository, jwtTokenProvider);
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
