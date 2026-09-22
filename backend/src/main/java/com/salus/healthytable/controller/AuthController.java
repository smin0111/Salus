package com.salus.healthytable.controller;

import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.LoginRequestDTO;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.dto.UserResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 소셜 로그인(Google, Kakao, Naver) API입니다.
 *
 * 공통 흐름:
 * 1) 클라이언트가 소셜 로그인 후 받은 토큰(또는 인가 코드)을 보냅니다.
 * 2) 서버가 해당 소셜 서비스에 토큰을 다시 확인해 사용자 정보를 받습니다(위조 방지).
 * 3) 이메일로 회원을 찾거나 새로 가입시킨 뒤, Salus 전용 JWT를 발급합니다.
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
    private final com.salus.healthytable.service.OAuthService oAuthService;
    private final Clock clock;

    /**
     * Google 로그인: 클라이언트가 받은 액세스 토큰을 검증합니다.
     */
    @PostMapping("/api/auth/google")
    public ResponseEntity<?> loginGoogle(@RequestBody LoginRequestDTO request) {
        try {
            String accessToken = requireText(request != null ? request.getAccessToken() : null, "access_token_missing");

            // 1. 구글을 통한 액세스 토큰 검증
            Map<String, Object> googleUser = oAuthService.verifyGoogleToken(accessToken);

            // 2. 사용자 정보 추출
            String email = requireText(googleUser != null ? googleUser.get("email") : null, "email_missing");
            String name = optionalText(googleUser != null ? googleUser.get("name") : null, "Google User");

            return issueLoginResponse(email, name);
        } catch (OAuthLoginException e) {
            return unauthorizedLoginResponse("google", "/api/auth/google", e.getReason());
        } catch (Exception e) {
            return unauthorizedLoginResponse("google", "/api/auth/google", e);
        }
    }

    /**
     * Kakao 로그인: 액세스 토큰으로 사용자 정보를 조회합니다.
     * 이메일 제공에 동의하지 않은 사용자는 "kakao_{카카오 ID}"를 이메일 대신 식별자로 사용합니다.
     */
    @PostMapping("/api/auth/kakao")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> loginKakao(@RequestBody LoginRequestDTO request) {
        try {
            String accessToken = requireText(request != null ? request.getAccessToken() : null, "access_token_missing");

            // 1. 카카오를 통한 액세스 토큰 검증
            Map<String, Object> kakaoUser = oAuthService.verifyKakaoToken(accessToken);

            // 2. 사용자 정보 추출 (카카오 구조는 계층형으로 구성됨)
            Map<String, Object> kakaoAccount = (Map<String, Object>) kakaoUser.get("kakao_account");
            if (kakaoAccount == null) {
                throw new RuntimeException("kakao_account is null");
            }

            Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");

            String email = optionalText(kakaoAccount.get("email"), "");
            if (email.isBlank()) {
                email = "kakao_" + requireText(kakaoUser.get("id"), "provider_id_missing");
            }

            String name = optionalText(profile != null ? profile.get("nickname") : null, "Kakao User");

            return issueLoginResponse(email, name);
        } catch (OAuthLoginException e) {
            return unauthorizedLoginResponse("kakao", "/api/auth/kakao", e.getReason());
        } catch (Exception e) {
            return unauthorizedLoginResponse("kakao", "/api/auth/kakao", e);
        }
    }

    /**
     * Naver 로그인: 인가 코드(code)를 액세스 토큰으로 교환한 뒤 사용자 정보를 조회합니다.
     * state 값은 로그인 요청 위조(CSRF)를 막기 위해 필요합니다.
     */
    @PostMapping("/api/auth/naver")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> loginNaver(@RequestBody LoginRequestDTO request) {
        try {
            String code = requireText(request != null ? request.getCode() : null, "code_missing");
            String state = requireText(request != null ? request.getState() : null, "state_missing");
            Map<String, Object> tokenResponse = oAuthService.exchangeNaverCode(
                    code,
                    state,
                    request != null ? request.getRedirectUri() : null);

            String accessToken = optionalText(tokenResponse != null ? tokenResponse.get("access_token") : null, "");
            if (accessToken == null || accessToken.isBlank()) {
                return unauthorizedLoginResponse("naver", "/api/auth/naver", "access_token_missing");
            }

            Map<String, Object> profileResponse = oAuthService.verifyNaverToken(accessToken);
            Map<String, Object> profile = (Map<String, Object>) profileResponse.get("response");
            if (profile == null) {
                return unauthorizedLoginResponse("naver", "/api/auth/naver", "profile_missing");
            }

            String email = optionalText(profile.get("email"), "");
            if (email.isBlank()) {
                email = "naver_" + requireText(profile.get("id"), "provider_id_missing");
            }
            String name = optionalText(profile.get("name"), optionalText(profile.get("nickname"), "Naver User"));

            return issueLoginResponse(email, name);
        } catch (OAuthLoginException e) {
            return unauthorizedLoginResponse("naver", "/api/auth/naver", e.getReason());
        } catch (Exception e) {
            return unauthorizedLoginResponse("naver", "/api/auth/naver", e);
        }
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
     * 이메일로 회원을 찾고, 없으면 새로 가입시킨 뒤 JWT와 회원 정보를 응답합니다.
     */
    private ResponseEntity<?> issueLoginResponse(String email, String name) {
        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User newUser = new User();
            newUser.setEmail(email);
            newUser.setName(name);
            // 가입 시각도 Clock을 통해 기록하면 테스트에서 시간값을 고정할 수 있습니다.
            // 운영에서는 app.time-zone 정책과 같은 기준으로 사용자 생성일을 해석할 수 있습니다.
            newUser.setCreatedAt(LocalDateTime.now(clock));
            // 소셜 로그인 전용 회원이라 비밀번호는 사용하지 않습니다.
            newUser.setPassword("");
            return userRepository.save(newUser);
        });

        // JWT subject에는 내부 User ID만 넣고, 권한은 요청마다 DB에서 다시 읽습니다.
        // 이렇게 하면 토큰 payload가 오래되어도 최신 role 기준으로 접근 제어할 수 있습니다.
        String token = jwtTokenProvider.createToken(String.valueOf(user.getId()));

        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", UserResponseDTO.from(user)));
    }

    // 예외 메시지에는 민감한 정보가 섞일 수 있어, 로그에는 예외 클래스 이름만 남깁니다.
    private ResponseEntity<Map<String, Object>> unauthorizedLoginResponse(String provider, String path, Exception exception) {
        return unauthorizedLoginResponse(provider, path, "exception_" + exception.getClass().getSimpleName());
    }

    private ResponseEntity<Map<String, Object>> unauthorizedLoginResponse(String provider, String path, String reason) {
        log.warn("OAuth login failed. provider={}, reason={}", provider, reason);
        return apiError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", LOGIN_FAILED_MESSAGE, path);
    }

    // 값이 비어 있으면 로그인 실패 예외를 던집니다. reason은 로그에 남길 실패 원인 코드입니다.
    private String requireText(Object value, String reason) {
        String text = optionalText(value, "");
        if (text.isBlank()) {
            throw new OAuthLoginException(reason);
        }
        return text;
    }

    // 값이 비어 있으면 기본값(fallback)을 반환합니다.
    private String optionalText(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private ResponseEntity<Map<String, Object>> apiError(HttpStatus status, String error, String message, String path) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", error,
                "message", message,
                "path", path));
    }

    // 로그인 실패 원인 코드를 담아 catch 블록까지 전달하기 위한 내부 전용 예외입니다.
    private static class OAuthLoginException extends RuntimeException {
        private final String reason;

        private OAuthLoginException(String reason) {
            this.reason = reason;
        }

        private String getReason() {
            return reason;
        }
    }
}
