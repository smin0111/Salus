package com.salus.healthytable.controller;

import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.dto.LoginRequestDTO;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.AccountLinkException;
import com.salus.healthytable.service.KakaoWebAuthService;
import com.salus.healthytable.service.LoginMethodService;
import com.salus.healthytable.service.OAuthVerificationException;
import com.salus.healthytable.service.RefreshTokenService;
import com.salus.healthytable.service.SocialCredentialVerifier;
import com.salus.healthytable.service.VerifiedSocialIdentity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 계정 설정의 로그인 수단 API입니다.
 *
 * - GET  /api/users/me/login-methods           연결된 로그인 수단 목록
 * - POST /api/users/me/reauth/{provider}        재인증: 이 회원의 로그인 수단을 서버가 이번 요청에서 확인하면 identity_confirmed_at이 담긴 토큰을 새로 발급
 * - POST /api/users/me/kakao-web-auth           카카오 재확인 시작(서버 콜백 방식 웹 로그인)
 * - POST /api/users/me/login-methods/{provider} 로그인 수단 추가: 최근 10분 안에 실제로 로그인했어야 함
 *
 * 로그인 상태(refresh로 2주간 유지)만으로 추가를 허용하면, 세션을 훔친 사람이 자기 소셜 계정을 붙여 계속 들어올 수 있어
 * 최근 계정 확인 시각(identity_confirmed_at)을 확인합니다. 재인증은 일반 로그인과 달리 다른 회원으로 세션이 바뀌지 않게 막습니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class LoginMethodController {

    static final Duration RECENT_AUTH_WINDOW = Duration.ofMinutes(10);

    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final JwtTokenProvider jwtTokenProvider;
    private final SocialCredentialVerifier socialCredentialVerifier;
    private final LoginMethodService loginMethodService;
    private final RefreshTokenService refreshTokenService;
    private final KakaoWebAuthService kakaoWebAuthService;
    private final Clock clock;

    @GetMapping("/login-methods")
    public List<LoginMethodService.LoginMethod> list() {
        return loginMethodService.list(authenticatedUserProvider.requireUserId());
    }

    /**
     * 카카오 재확인 시작(서버 콜백 방식 웹 로그인). 앱은 authorizeUrl을 브라우저로 열고, appRedirectUri로 돌아오면
     * requestToken을 webAuthRequestToken으로 담아 reauth/kakao 또는 login-methods/kakao를 호출합니다.
     */
    @PostMapping("/kakao-web-auth")
    public ResponseEntity<?> startKakaoWebAuth(HttpServletRequest httpRequest) {
        Long userId = authenticatedUserProvider.requireUserId();
        try {
            KakaoWebAuthService.Started started = kakaoWebAuthService.start(userId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("authorizeUrl", started.authorizeUrl());
            body.put("requestToken", started.requestToken());
            body.put("appRedirectUri", started.appRedirectUri());
            return ResponseEntity.ok(body);
        } catch (IllegalStateException e) {
            log.error("Kakao web auth is not configured: {}", e.getMessage());
            return error(HttpStatus.SERVICE_UNAVAILABLE, "KAKAO_WEB_AUTH_UNAVAILABLE",
                    "지금은 카카오로 다시 확인할 수 없습니다. 다른 로그인 수단을 이용해 주세요.", httpRequest.getRequestURI());
        }
    }

    @PostMapping("/reauth/{provider}")
    public ResponseEntity<?> reauthenticate(@PathVariable String provider,
            @RequestBody(required = false) LoginRequestDTO request, HttpServletRequest httpRequest) {
        Long userId = authenticatedUserProvider.requireUserId();
        String path = httpRequest.getRequestURI();
        SocialProvider socialProvider = parseProvider(provider);
        if (socialProvider == null) {
            return error(HttpStatus.BAD_REQUEST, "UNKNOWN_PROVIDER", "지원하지 않는 로그인 수단입니다.", path);
        }

        VerifiedSocialIdentity identity;
        try {
            identity = socialCredentialVerifier.verify(socialProvider, request, userId);
        } catch (OAuthVerificationException e) {
            log.info("Reauth verification failed. provider={}, reason={}", provider, e.getReason());
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", path);
        } catch (RuntimeException e) {
            log.info("Reauth verification failed. provider={}, reason=exception_{}", provider, e.getClass().getSimpleName());
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", path);
        }

        if (identity.identityConfirmedAt() == null) {
            return error(HttpStatus.BAD_REQUEST, "REAUTH_PROVIDER_NOT_SUPPORTED",
                    "이 로그인 수단으로는 아직 재인증을 지원하지 않습니다. 다른 연결된 로그인 수단으로 다시 로그인해 주세요.", path);
        }
        if (!loginMethodService.isOwnLoginMethod(userId, identity)) {
            return error(HttpStatus.CONFLICT, "REAUTH_MISMATCH",
                    "현재 계정에 연결된 로그인 수단으로 다시 로그인해 주세요.", path);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", jwtTokenProvider.createToken(String.valueOf(userId), identity.identityConfirmedAt()));
        body.put("refreshToken", refreshTokenService.issue(userId, identity.identityConfirmedAt()));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/login-methods/{provider}")
    public ResponseEntity<?> add(@PathVariable String provider,
            @RequestBody(required = false) LoginRequestDTO request, HttpServletRequest httpRequest) {
        Long userId = authenticatedUserProvider.requireUserId();
        String path = httpRequest.getRequestURI();
        if (!recentlyConfirmed(httpRequest)) {
            return error(HttpStatus.UNAUTHORIZED, "REAUTH_REQUIRED",
                    "보안을 위해 다시 로그인한 뒤 로그인 수단을 추가해 주세요.", path);
        }
        SocialProvider socialProvider = parseProvider(provider);
        if (socialProvider == null) {
            return error(HttpStatus.BAD_REQUEST, "UNKNOWN_PROVIDER", "지원하지 않는 로그인 수단입니다.", path);
        }

        try {
            VerifiedSocialIdentity identity = socialCredentialVerifier.verify(socialProvider, request, userId);
            loginMethodService.add(userId, identity);
        } catch (OAuthVerificationException e) {
            log.info("Add login method verification failed. provider={}, reason={}", provider, e.getReason());
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", path);
        } catch (AccountLinkException e) {
            return error(e.getStatus(), e.getCode(), e.getMessage(), path);
        } catch (DataIntegrityViolationException e) {
            // 같은 소셜 계정을 동시에 추가한 경우입니다. 다른 회원으로 옮기지 않습니다.
            AccountLinkException conflict = AccountLinkException.alreadyLinked();
            return error(conflict.getStatus(), conflict.getCode(), conflict.getMessage(), path);
        } catch (RuntimeException e) {
            log.info("Add login method failed. provider={}, reason=exception_{}", provider, e.getClass().getSimpleName());
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "소셜 로그인 인증에 실패했습니다.", path);
        }
        return ResponseEntity.ok(loginMethodService.list(userId));
    }

    // 현재 요청 토큰의 identity_confirmed_at이 최근 10분 안이어야 합니다. 없으면(갱신만 거친 오래된 세션 포함) 재인증이 필요합니다.
    private boolean recentlyConfirmed(HttpServletRequest request) {
        String token = jwtTokenProvider.resolveToken(request);
        if (token == null) {
            return false;
        }
        Instant now = clock.instant();
        return jwtTokenProvider.getIdentityConfirmedAt(token)
                .map(identityConfirmedAt -> !identityConfirmedAt.isBefore(now.minus(RECENT_AUTH_WINDOW)) && !identityConfirmedAt.isAfter(now.plusSeconds(60)))
                .orElse(false);
    }

    private SocialProvider parseProvider(String value) {
        try {
            return SocialProvider.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        body.put("path", path);
        return ResponseEntity.status(status).body(body);
    }
}
