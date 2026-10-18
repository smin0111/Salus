package com.salus.healthytable.controller;

import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.LoginRequestDTO;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.dto.UserResponseDTO;
import com.salus.healthytable.service.AccountLinkException;
import com.salus.healthytable.service.AuthNonceService;
import com.salus.healthytable.service.KakaoWebAuthService;
import com.salus.healthytable.service.EmailSignupLock;
import com.salus.healthytable.dto.RefreshTokenRequestDTO;
import com.salus.healthytable.service.SocialCredentialVerifier;
import com.salus.healthytable.service.RefreshTokenService;
import com.salus.healthytable.service.OAuthVerificationException;
import com.salus.healthytable.service.SocialLoginService;
import com.salus.healthytable.service.SocialLoginTxHelper;
import com.salus.healthytable.service.VerifiedSocialIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 소셜 로그인(Google, Kakao, Naver, Apple) API입니다.
 *
 * 공통 흐름:
 * 1) 클라이언트가 소셜 로그인 후 받은 토큰(또는 인가 코드)을 보냅니다.
 * 2) 서버가 해당 소셜 서비스에 토큰을 다시 확인해 사용자 정보를 받습니다(위조 방지).
 * 3) 제공자 고유 ID로 회원을 찾거나 새로 가입시킨 뒤, Salus access token(JWT)과 refresh token을 발급합니다.
 *    다른 제공자인데 같은 검증 이메일의 회원이 있으면 409 LINK_REQUIRED로 응답하고, 기존 계정으로 다시 로그인하면
 *    연결합니다(SocialLoginTxHelper 참고).
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
    private final SocialCredentialVerifier socialCredentialVerifier;
    private final SocialLoginService socialLoginService;
    private final RefreshTokenService refreshTokenService;
    private final AuthNonceService authNonceService;
    private final KakaoWebAuthService kakaoWebAuthService;
    // PLUS 이용권 만료 판단 기준 시각(서버 시간대)
    private final java.time.Clock clock;

    /**
     * Google 로그인: 클라이언트가 받은 액세스 토큰을 검증합니다.
     */
    @PostMapping("/api/auth/google")
    public ResponseEntity<?> loginGoogle(@RequestBody LoginRequestDTO request) {
        return login("google", "/api/auth/google", request,
                () -> socialCredentialVerifier.verify(SocialProvider.GOOGLE, request));
    }

    /**
     * Kakao 로그인: 앱은 네이티브 SDK가 돌려준 액세스 토큰을, 웹은 인가 코드(code·redirectUri)를 보냅니다.
     */
    @PostMapping("/api/auth/kakao")
    public ResponseEntity<?> loginKakao(@RequestBody LoginRequestDTO request) {
        return login("kakao", "/api/auth/kakao", request,
                () -> socialCredentialVerifier.verify(SocialProvider.KAKAO, request));
    }

    /**
     * Naver 로그인: 인가 코드(code)를 액세스 토큰으로 교환한 뒤 사용자 정보를 조회합니다.
     * state 값은 로그인 요청 위조(CSRF)를 막기 위해 필요합니다.
     */
    @PostMapping("/api/auth/naver")
    public ResponseEntity<?> loginNaver(@RequestBody LoginRequestDTO request) {
        return login("naver", "/api/auth/naver", request,
                () -> socialCredentialVerifier.verify(SocialProvider.NAVER, request));
    }

    /**
     * Apple 로그인: 앱이 받은 identity token을 Apple 공개키로 검증합니다.
     * nonce는 /api/auth/nonce로 서버가 발급한 원문이며, 앱은 그 SHA-256 값을 Apple에 넘깁니다.
     */
    @PostMapping("/api/auth/apple")
    public ResponseEntity<?> loginApple(@RequestBody LoginRequestDTO request) {
        return login("apple", "/api/auth/apple", request,
                () -> socialCredentialVerifier.verify(SocialProvider.APPLE, request));
    }

    /**
     * 카카오 웹 로그인 콜백(서버 콜백 방식, 계정 설정의 카카오 재확인용). 카카오 콘솔의 Redirect URI로 등록하는 주소입니다.
     * state를 서버가 대조하고 일회용 코드를 직접 교환한 뒤, 결과 상태만 담아 앱 주소로 돌려보냅니다(코드·토큰은 주소에 담지 않음).
     */
    @GetMapping("/api/auth/kakao/callback")
    public ResponseEntity<Void> kakaoWebAuthCallback(@RequestParam(required = false) String code,
            @RequestParam(required = false) String state, @RequestParam(required = false) String error) {
        String location = kakaoWebAuthService.handleCallback(code, state, error);
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", location).build();
    }

    /**
     * 웹 앱의 카카오 로그인 주소. 웹에는 카카오 네이티브 SDK가 없어, 카카오 로그인 창 주소를 받아 인가 코드를
     * redirectUri(웹 앱 주소)로 받은 뒤 /api/auth/kakao에 code·redirectUri로 보냅니다. REST 키는 서버 설정에만 둡니다.
     */
    @GetMapping("/api/auth/kakao/authorize-url")
    public ResponseEntity<?> kakaoLoginAuthorizeUrl(@RequestParam(required = false) String redirectUri,
            @RequestParam(required = false) String state) {
        String path = "/api/auth/kakao/authorize-url";
        try {
            return ResponseEntity.ok(Map.of("authorizeUrl", kakaoWebAuthService.loginAuthorizeUrl(redirectUri, state)));
        } catch (OAuthVerificationException e) {
            return apiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "카카오 로그인 요청 값이 비어 있습니다.", path);
        } catch (IllegalStateException e) {
            log.warn("Kakao web login is not configured.");
            return apiError(HttpStatus.SERVICE_UNAVAILABLE, "KAKAO_WEB_LOGIN_UNAVAILABLE", "카카오 웹 로그인을 사용할 수 없습니다.", path);
        }
    }

    /**
     * 일회용 nonce 발급(10분). 앱은 로그인 직전에 받아 그 SHA-256 값을 제공자에 넘기고, 원문은 로그인 요청에 담아 보냅니다.
     */
    @PostMapping("/api/auth/nonce")
    public Map<String, String> issueNonce() {
        return Map.of("nonce", authNonceService.issue());
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

        // 처음 계정 확인 시각을 이어받습니다. 갱신을 새 확인으로 바꾸지 않습니다.
        return ResponseEntity.ok(Map.of(
                "token", jwtTokenProvider.createToken(String.valueOf(result.userId()), result.identityConfirmedAt()),
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
        return ResponseEntity.ok(UserResponseDTO.from(user, java.time.LocalDateTime.now(clock)));
    }

    /**
     * 제공자 검증 → 회원 조회/생성 → Salus JWT 발급을 한 흐름으로 처리합니다.
     * 실패 원인과 상관없이 같은 401 응답을 주고, 원인 코드는 서버 로그에만 남깁니다.
     */
    private ResponseEntity<?> login(String provider, String path, LoginRequestDTO request,
            Supplier<VerifiedSocialIdentity> verifier) {
        SocialLoginTxHelper.Decision decision;
        VerifiedSocialIdentity identity;
        try {
            identity = verifier.get();
            decision = socialLoginService.login(identity,
                    request != null ? request.getLinkRequestId() : null,
                    request != null ? request.getFlowNonce() : null);
        } catch (OAuthVerificationException e) {
            return unauthorizedLoginResponse(provider, path, e.getReason());
        } catch (AccountLinkException e) {
            log.info("Account link failed. provider={}, reason={}", provider, e.getCode());
            return apiError(e.getStatus(), e.getCode(), e.getMessage(), path);
        } catch (EmailSignupLock.SignupBusyException e) {
            log.warn("Signup lock unavailable. provider={}", provider);
            return apiError(HttpStatus.SERVICE_UNAVAILABLE, "SIGNUP_BUSY", "잠시 후 다시 시도해 주세요.", path);
        } catch (Exception e) {
            return unauthorizedLoginResponse(provider, path, e);
        }

        if (decision instanceof SocialLoginTxHelper.LinkRequired linkRequired) {
            // 같은 검증 이메일의 기존 회원이 있습니다. 새 회원을 만들지 않았고, 기존 계정으로 로그인하면 연결됩니다.
            Map<String, Object> body = errorBody(HttpStatus.CONFLICT, "LINK_REQUIRED",
                    "이미 같은 이메일로 가입된 계정이 있습니다. 기존 계정으로 로그인하면 이 로그인 수단도 연결됩니다.", path);
            body.put("linkRequestId", linkRequired.linkToken());
            body.put("providers", linkRequired.providers());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        if (decision instanceof SocialLoginTxHelper.Ambiguous) {
            return apiError(HttpStatus.CONFLICT, "ACCOUNT_AMBIGUOUS",
                    "같은 이메일로 가입된 계정을 하나로 정할 수 없습니다. 기존에 사용하던 방법으로 로그인해 주세요.", path);
        }

        SocialLoginTxHelper.LoggedIn loggedIn = (SocialLoginTxHelper.LoggedIn) decision;
        User user = loggedIn.user();
        // JWT subject에는 내부 User ID만 넣고, 권한은 요청마다 DB에서 다시 읽습니다.
        // 이렇게 하면 토큰 payload가 오래되어도 최신 role 기준으로 접근 제어할 수 있습니다.
        Map<String, Object> body = new LinkedHashMap<>();
        // identity_confirmed_at은 서버가 이번 요청에서 계정을 확인했다고 볼 수 있을 때만 넣습니다(SocialCredentialVerifier 기준).
        // 계정 연결을 마치는 재인증이면 그 재인증의 인정 여부를 따릅니다.
        body.put("token", jwtTokenProvider.createToken(String.valueOf(user.getId()), identity.identityConfirmedAt()));
        body.put("refreshToken", refreshTokenService.issue(user.getId(), identity.identityConfirmedAt()));
        body.put("user", UserResponseDTO.from(user, java.time.LocalDateTime.now(clock)));
        if (loggedIn.linkedProvider() != null) {
            body.put("linkedProvider", loggedIn.linkedProvider());
        }
        return ResponseEntity.ok(body);
    }

    /**
     * 계정 연결 취소: 서버의 연결 요청을 무효화합니다. 결과와 관계없이 204를 응답합니다.
     */
    @PostMapping("/api/auth/link/cancel")
    public ResponseEntity<Void> cancelLink(@RequestBody(required = false) LoginRequestDTO request) {
        if (request != null) {
            socialLoginService.cancelLink(request.getLinkRequestId(), request.getFlowNonce());
        }
        return ResponseEntity.noContent().build();
    }

    // 예외 메시지에는 민감한 정보가 섞일 수 있어, 로그에는 예외 클래스 이름만 남깁니다.
    // 제공자 API 오류 응답이면 어느 API였는지 알 수 있게 호스트·경로만 덧붙입니다(쿼리에는 코드·시크릿이 있어 남기지 않음).
    private ResponseEntity<Map<String, Object>> unauthorizedLoginResponse(String provider, String path, Exception exception) {
        String reason = "exception_" + exception.getClass().getSimpleName();
        if (exception instanceof WebClientResponseException response && response.getRequest() != null) {
            reason += "@" + response.getRequest().getURI().getHost() + response.getRequest().getURI().getPath();
        }
        return unauthorizedLoginResponse(provider, path, reason);
    }

    private ResponseEntity<Map<String, Object>> unauthorizedLoginResponse(String provider, String path, String reason) {
        log.warn("OAuth login failed. provider={}, reason={}", provider, reason);
        return apiError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", LOGIN_FAILED_MESSAGE, path);
    }

    private ResponseEntity<Map<String, Object>> apiError(HttpStatus status, String error, String message, String path) {
        return ResponseEntity.status(status).body(errorBody(status, error, message, path));
    }

    private Map<String, Object> errorBody(HttpStatus status, String error, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("path", path);
        return body;
    }
}
