package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.dto.LoginRequestDTO;
import com.mychefai.healthytable.repository.UserRepository;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.security.JwtTokenProvider;
import com.mychefai.healthytable.service.OAuthService;
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

    @Test
    void googleLoginWithoutAccessTokenDoesNotCallOAuth(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();

        ResponseEntity<?> response = controller.loginGoogle(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/google");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=google, reason=access_token_missing");
        verifyNoInteractions(oAuthService, userRepository, jwtTokenProvider);
    }

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

    @Test
    void naverLoginWithoutAccessTokenReturnsConsistentJsonError(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setCode("auth-code");
        request.setState("state");
        request.setRedirectUri("mychefai://redirect");
        when(oAuthService.exchangeNaverCode("auth-code", "state", "mychefai://redirect")).thenReturn(Map.of());

        ResponseEntity<?> response = controller.loginNaver(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/naver");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=naver, reason=access_token_missing")
                .doesNotContain("auth-code")
                .doesNotContain("state")
                .doesNotContain("mychefai://redirect");
    }

    @Test
    void naverLoginWithoutCodeDoesNotCallOAuth(CapturedOutput output) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setState("state");
        request.setRedirectUri("mychefai://redirect");

        ResponseEntity<?> response = controller.loginNaver(request);

        assertErrorResponse(response, 401, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", "/api/auth/naver");
        assertThat(output.getOut())
                .contains("OAuth login failed. provider=naver, reason=code_missing")
                .doesNotContain("state")
                .doesNotContain("mychefai://redirect");
        verifyNoInteractions(oAuthService, userRepository, jwtTokenProvider);
    }

    @Test
    void unauthenticatedCurrentUserReturnsConsistentJsonError() {
        when(authenticatedUserProvider.requireUserId())
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));

        ResponseEntity<?> response = controller.getMyInfo();

        assertErrorResponse(response, 401, "UNAUTHORIZED", "로그인이 필요합니다.", "/api/users/me");
        verifyNoInteractions(userRepository);
    }

    @Test
    void currentUserMissingFromDatabaseReturnsConsistentJsonError() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getMyInfo();

        assertErrorResponse(response, 404, "NOT_FOUND", "사용자를 찾을 수 없습니다.", "/api/users/me");
    }

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
