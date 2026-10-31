package com.salus.healthytable.controller;

import com.salus.healthytable.domain.EmailVerificationPurpose;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.EmailAuthRequestDTO;
import com.salus.healthytable.dto.UserResponseDTO;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.ChatRateLimitService;
import com.salus.healthytable.service.EmailAuthService;
import com.salus.healthytable.service.EmailSignupLock;
import com.salus.healthytable.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 살루스 자체 이메일·비밀번호 가입과 로그인 API입니다(/api/auth/** 는 로그인 없이 호출).
 *
 * <ul>
 *   <li>POST /api/auth/email/code: 가입·비밀번호 재설정 인증 코드 메일 요청. 가입 여부와 상관없이 202</li>
 *   <li>POST /api/auth/email/signup: 코드 확인 후 가입하고 바로 로그인(소셜 로그인과 같은 응답)</li>
 *   <li>POST /api/auth/email/login: 이메일·비밀번호 로그인</li>
 *   <li>POST /api/auth/email/password-reset: 코드 확인 후 비밀번호 변경, 모든 refresh token 폐기(이미 받은 access token은 30분 만료까지 유효). 204</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class EmailAuthController {

    private final EmailAuthService emailAuthService;
    private final ChatRateLimitService chatRateLimitService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    @PostMapping("/api/auth/email/code")
    public ResponseEntity<?> requestCode(@RequestBody(required = false) EmailAuthRequestDTO request,
            HttpServletRequest httpRequest) {
        String path = "/api/auth/email/code";
        return handle(path, () -> {
            EmailVerificationPurpose purpose = parsePurpose(request == null ? null : request.getPurpose());
            emailAuthService.requestCode(request == null ? null : request.getEmail(), purpose,
                    chatRateLimitService.resolveClientIp(httpRequest));
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("message", "메일을 보냈어요. 받은 편지함과 스팸함을 확인해 주세요."));
        });
    }

    @PostMapping("/api/auth/email/signup")
    public ResponseEntity<?> signup(@RequestBody(required = false) EmailAuthRequestDTO request) {
        String path = "/api/auth/email/signup";
        return handle(path, () -> {
            EmailAuthRequestDTO body = request == null ? new EmailAuthRequestDTO() : request;
            User user = emailAuthService.signup(body.getEmail(), body.getCode(), body.getPassword(), body.getName());
            log.info("Email signup completed. userId={}", user.getId());
            Instant confirmedAt = Instant.now(clock);
            return ResponseEntity.ok(loginBody(user, refreshTokenService.issue(user.getId(), confirmedAt), confirmedAt));
        });
    }

    @PostMapping("/api/auth/email/login")
    public ResponseEntity<?> login(@RequestBody(required = false) EmailAuthRequestDTO request,
            HttpServletRequest httpRequest) {
        String path = "/api/auth/email/login";
        return handle(path, () -> {
            EmailAuthRequestDTO body = request == null ? new EmailAuthRequestDTO() : request;
            Instant confirmedAt = Instant.now(clock);
            EmailAuthService.LoginSession session = emailAuthService.login(body.getEmail(), body.getPassword(),
                    chatRateLimitService.resolveClientIp(httpRequest), confirmedAt);
            return ResponseEntity.ok(loginBody(session.user(), session.refreshToken(), confirmedAt));
        });
    }

    @PostMapping("/api/auth/email/password-reset")
    public ResponseEntity<?> resetPassword(@RequestBody(required = false) EmailAuthRequestDTO request) {
        String path = "/api/auth/email/password-reset";
        return handle(path, () -> {
            EmailAuthRequestDTO body = request == null ? new EmailAuthRequestDTO() : request;
            emailAuthService.resetPassword(body.getEmail(), body.getCode(), body.getNewPassword());
            return ResponseEntity.noContent().build();
        });
    }

    // 소셜 로그인 성공 응답과 같은 모양입니다. 방금 비밀번호(또는 메일 코드)로 확인했으므로 identity_confirmed_at을 넣어
    // 계정 설정에서 소셜 로그인 수단을 바로 추가할 수 있게 합니다(10분 기준은 LoginMethodService).
    // 로그인은 refresh token을 비밀번호 확인과 같은 트랜잭션에서 받아 옵니다(EmailAuthTxHelper.login).
    private Map<String, Object> loginBody(User user, String refreshToken, Instant confirmedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", jwtTokenProvider.createToken(String.valueOf(user.getId()), confirmedAt));
        body.put("refreshToken", refreshToken);
        body.put("user", UserResponseDTO.from(user, LocalDateTime.now(clock)));
        return body;
    }

    private static EmailVerificationPurpose parsePurpose(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return EmailVerificationPurpose.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ResponseEntity<?> handle(String path, Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (EmailAuthService.EmailAuthException e) {
            return error(e.getStatus(), e.getCode(), e.getMessage(), path);
        } catch (EmailSignupLock.SignupBusyException e) {
            log.warn("Email signup lock unavailable.");
            return error(HttpStatus.SERVICE_UNAVAILABLE, "SIGNUP_BUSY", "잠시 후 다시 시도해 주세요.", path);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        body.put("path", path);
        return ResponseEntity.status(status).body(body);
    }
}
