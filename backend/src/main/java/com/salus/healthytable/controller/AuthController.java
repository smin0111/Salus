package com.salus.healthytable.controller;

import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.LoginRequestDTO;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.dto.UserResponseDTO;
import com.salus.healthytable.service.AppleIdentityTokenVerifier;
import com.salus.healthytable.dto.RefreshTokenRequestDTO;
import com.salus.healthytable.service.OAuthService;
import com.salus.healthytable.service.RefreshTokenService;
import com.salus.healthytable.service.OAuthVerificationException;
import com.salus.healthytable.service.SocialLoginService;
import com.salus.healthytable.service.VerifiedSocialIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 소셜 로그인(Google, Kakao, Naver, Apple) API입니다.
 *
 * 공통 흐름:
 * 1) 클라이언트가 소셜 로그인 후 받은 토큰(또는 인가 코드)을 보냅니다.
 * 2) 서버가 해당 소셜 서비스에 토큰을 다시 확인해 사용자 정보를 받습니다(위조 방지).
 * 3) 제공자 고유 ID로 회원을 찾거나 새로 가입시킨 뒤, Salus access token(JWT)과 refresh token을 발급합니다.
 *    제공자가 다르면 이메일이 같아도 별개 회원입니다(SocialLoginTxHelper 참고).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    // 실패 원인과 상관없이 사용자에게는 같은 메시지를 보여 주고, 자세한 원인은 서버 로그에만 남깁니다.
    private static final String LOGIN_FAILED_MESSAGE = "소셜 로그인 인증에 실패했습니다.";

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final OAuthService oAuthService;
    private final SocialLoginService socialLoginService;
    private final AppleIdentityTokenVerifier appleIdentityTokenVerifier;
    private final RefreshTokenService refreshTokenService;

    /**
     * Google 로그인: 클라이언트가 받은 액세스 토큰을 검증합니다.
     */
    @PostMapping("/api/auth/google")
    public ResponseEntity<?> loginGoogle(@RequestBody LoginRequestDTO request) {
        return login("google", "/api/auth/google", () -> oAuthService.verifyGoogle(
                requireText(request != null ? request.getAccessToken() : null, "access_token_missing")));
    }

    /**
     * Kakao 로그인: 네이티브 SDK가 돌려준 액세스 토큰을 검증합니다.
     */
    @PostMapping("/api/auth/kakao")
    public ResponseEntity<?> loginKakao(@RequestBody LoginRequestDTO request) {
        return login("kakao", "/api/auth/kakao", () -> oAuthService.verifyKakao(
                requireText(request != null ? request.getAccessToken() : null, "access_token_missing")));
    }

    /**
     * Naver 로그인: 인가 코드(code)를 액세스 토큰으로 교환한 뒤 사용자 정보를 조회합니다.
     * state 값은 로그인 요청 위조(CSRF)를 막기 위해 필요합니다.
     */
    @PostMapping("/api/auth/naver")
    public ResponseEntity<?> loginNaver(@RequestBody LoginRequestDTO request) {
        return login("naver", "/api/auth/naver", () -> oAuthService.verifyNaver(
                requireText(request != null ? request.getCode() : null, "code_missing"),
                requireText(request != null ? request.getState() : null, "state_missing"),
                request.getRedirectUri()));
    }

    /**
     * Apple 로그인: 앱이 받은 identity token을 Apple 공개키로 검증합니다.
     * nonce는 앱이 Apple에 SHA-256 값으로 넘긴 원문입니다.
     */
    @PostMapping("/api/auth/apple")
    public ResponseEntity<?> loginApple(@RequestBody LoginRequestDTO request) {
        return login("apple", "/api/auth/apple", () -> appleIdentityTokenVerifier.verifyLogin(
                requireText(request != null ? request.getIdentityToken() : null, "identity_token_missing"),
                requireText(request != null ? request.getNonce() : null, "nonce_missing"),
                request.getFullName()));
    }

    /**
     * access token 갱신: refresh token을 새 access token + 새 refresh token으로 교환합니다.
     * 쓴 refresh token은 즉시 폐기되므로 클라이언트는 응답의 refreshToken으로 바꿔 저장해야 합니다.
     */
    @PostMapping("/api/auth/refresh")
    public ResponseEntity<?> refresh(@RequestBody(required = false) RefreshTokenRequestDTO request) {
        RefreshTokenService.RotationResult result =
                refreshTokenService.rotate(request != null ? request.getRefreshToken() : null);
        if (result.status() != RefreshTokenService.Status.ROTATED) {
            log.info("Refresh token rejected. reason={}", result.status());
            return apiError(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID", "다시 로그인해 주세요.", "/api/auth/refresh");
        }

        return ResponseEntity.ok(Map.of(
                "token", jwtTokenProvider.createToken(String.valueOf(result.userId())),
                "refreshToken", result.refreshToken()));
    }

    /**
     * 로그아웃: refresh token을 서버에서 폐기합니다. 남은 access token은 짧은 유효시간 뒤 만료됩니다.
     * access token이 이미 만료됐어도 로그아웃할 수 있도록 refresh token만 받습니다.
     */
    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshTokenRequestDTO request) {
        refreshTokenService.revoke(request != null ? request.getRefreshToken() : null);
        return ResponseEntity.noContent().build();
    }

    /**
     * 현재 로그인된 사용자 정보 조회 (결제 후 등급 갱신에 사용)
     */
    @GetMapping("/api/users/me")
    public ResponseEntity<?> getMyInfo() {
        Long userId;
        try {
            userId = authenticatedUserProvider.requireUserId();
        } catch (ResponseStatusException e) {
            return apiError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.", "/api/users/me");
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null)
            return apiError(HttpStatus.NOT_FOUND, "NOT_FOUND", "사용자를 찾을 수 없습니다.", "/api/users/me");
        return ResponseEntity.ok(UserResponseDTO.from(user));
    }

    /**
     * 제공자 검증 → 회원 조회/생성 → Salus JWT 발급을 한 흐름으로 처리합니다.
     * 실패 원인과 상관없이 같은 401 응답을 주고, 원인 코드는 서버 로그에만 남깁니다.
     */
    private ResponseEntity<?> login(String provider, String path, Supplier<VerifiedSocialIdentity> verifier) {
        try {
            User user = socialLoginService.login(verifier.get());

            // JWT subject에는 내부 User ID만 넣고, 권한은 요청마다 DB에서 다시 읽습니다.
            // 이렇게 하면 토큰 payload가 오래되어도 최신 role 기준으로 접근 제어할 수 있습니다.
            String token = jwtTokenProvider.createToken(String.valueOf(user.getId()));
            String refreshToken = refreshTokenService.issue(user.getId());

            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "refreshToken", refreshToken,
                    "user", UserResponseDTO.from(user)));
        } catch (OAuthVerificationException e) {
            return unauthorizedLoginResponse(provider, path, e.getReason());
        } catch (Exception e) {
            return unauthorizedLoginResponse(provider, path, e);
        }
    }

    // 예외 메시지에는 민감한 정보가 섞일 수 있어, 로그에는 예외 클래스 이름만 남깁니다.
    private ResponseEntity<Map<String, Object>> unauthorizedLoginResponse(String provider, String path, Exception exception) {
        return unauthorizedLoginResponse(provider, path, "exception_" + exception.getClass().getSimpleName());
    }

    private ResponseEntity<Map<String, Object>> unauthorizedLoginResponse(String provider, String path, String reason) {
        log.warn("OAuth login failed. provider={}, reason={}", provider, reason);
        return apiError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", LOGIN_FAILED_MESSAGE, path);
    }

    // 요청 값이 비어 있으면 제공자를 호출하지 않고 실패시킵니다. reason은 로그에 남길 실패 원인 코드입니다.
    private String requireText(String value, String reason) {
        if (value == null || value.isBlank()) {
            throw new OAuthVerificationException(reason);
        }
        return value.trim();
    }

    private ResponseEntity<Map<String, Object>> apiError(HttpStatus status, String error, String message, String path) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", error,
                "message", message,
                "path", path));
    }
}
