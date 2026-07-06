package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.dto.LoginRequestDTO;
import com.mychefai.healthytable.repository.UserRepository;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.security.JwtTokenProvider;
import com.mychefai.healthytable.dto.UserResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private static final String LOGIN_FAILED_MESSAGE = "소셜 로그인 인증에 실패했습니다.";

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final com.mychefai.healthytable.service.OAuthService oAuthService;
    private final Clock clock;

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

    @PostMapping("/api/auth/naver")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> loginNaver(@RequestBody LoginRequestDTO request) {
        try {
            String code = requireText(request != null ? request.getCode() : null, "code_missing");
            Map<String, Object> tokenResponse = oAuthService.exchangeNaverCode(
                    code,
                    request != null ? request.getState() : null,
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

    private ResponseEntity<?> issueLoginResponse(String email, String name) {
        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User newUser = new User();
            newUser.setEmail(email);
            newUser.setName(name);
            newUser.setCreatedAt(LocalDateTime.now(clock));
            newUser.setPassword("");
            return userRepository.save(newUser);
        });

        String token = jwtTokenProvider.createToken(String.valueOf(user.getId()));

        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", UserResponseDTO.from(user)));
    }

    private ResponseEntity<Map<String, Object>> unauthorizedLoginResponse(String provider, String path, Exception exception) {
        return unauthorizedLoginResponse(provider, path, "exception_" + exception.getClass().getSimpleName());
    }

    private ResponseEntity<Map<String, Object>> unauthorizedLoginResponse(String provider, String path, String reason) {
        log.warn("OAuth login failed. provider={}, reason={}", provider, reason);
        return apiError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", LOGIN_FAILED_MESSAGE, path);
    }

    private String requireText(Object value, String reason) {
        String text = optionalText(value, "");
        if (text.isBlank()) {
            throw new OAuthLoginException(reason);
        }
        return text;
    }

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
