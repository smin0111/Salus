package com.salus.healthytable.controller;

import com.salus.healthytable.dto.AdminAuthDTO;
import com.salus.healthytable.service.adminauth.AdminAuthException;
import com.salus.healthytable.service.adminauth.AdminAuthService;
import com.salus.healthytable.service.adminauth.AdminLoginRateLimiter;
import com.salus.healthytable.service.adminauth.AdminPrincipal;
import com.salus.healthytable.service.adminauth.AdminSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 관리자 로그인 API(/api/admin/auth)입니다. 서비스 회원 로그인(/api/auth)과 완전히 별개입니다.
 *
 * 흐름: login(아이디/비밀번호) → [password(임시 비밀번호 변경)] → [totp/setup → totp/confirm(인증 앱 등록)] 또는 totp(코드 입력)
 * 마지막 단계가 끝나야 관리자 access 토큰을 발급합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService authService;
    private final AdminSessionService sessionService;
    private final AdminLoginRateLimiter rateLimiter;

    @PostMapping("/login")
    public AdminAuthDTO.ChallengeResponse login(@RequestBody AdminAuthDTO.LoginRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.recordAttempt(httpRequest.getRemoteAddr());
        AdminAuthService.Challenge challenge = authService.login(request.username(), request.password());
        return new AdminAuthDTO.ChallengeResponse(challenge.step(), challenge.challengeToken());
    }

    @PostMapping("/password")
    public AdminAuthDTO.ChallengeResponse changePassword(@RequestBody AdminAuthDTO.PasswordChangeRequest request) {
        AdminAuthService.Challenge challenge = authService.changePassword(request.challengeToken(), request.newPassword());
        return new AdminAuthDTO.ChallengeResponse(challenge.step(), challenge.challengeToken());
    }

    @PostMapping("/totp/setup")
    public AdminAuthDTO.TotpSetupResponse beginTotpSetup(@RequestBody AdminAuthDTO.ChallengeRequest request) {
        AdminAuthService.TotpSetup setup = authService.beginTotpSetup(request.challengeToken());
        return new AdminAuthDTO.TotpSetupResponse(setup.otpauthUri(), setup.secret());
    }

    @PostMapping("/totp/confirm")
    public AdminAuthDTO.LoginResponse confirmTotpSetup(@RequestBody AdminAuthDTO.TotpRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.recordAttempt(httpRequest.getRemoteAddr());
        return toResponse(authService.confirmTotpSetup(request.challengeToken(), request.code()));
    }

    @PostMapping("/totp")
    public AdminAuthDTO.LoginResponse verifyTotp(@RequestBody AdminAuthDTO.TotpRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.recordAttempt(httpRequest.getRemoteAddr());
        return toResponse(authService.verifyTotp(request.challengeToken(), request.code()));
    }

    @GetMapping("/me")
    public AdminAuthDTO.MeResponse me(@AuthenticationPrincipal AdminPrincipal principal) {
        return new AdminAuthDTO.MeResponse(principal.username(), principal.role().name());
    }

    // 관리자 로그아웃: 서버 세션을 끝내므로 같은 토큰은 즉시 쓸 수 없게 됩니다.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AdminPrincipal principal) {
        sessionService.revoke(principal.sessionId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AdminAuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuthFailure(AdminAuthException e, HttpServletRequest request) {
        return ResponseEntity.status(e.getStatus()).body(Map.of(
                "status", e.getStatus().value(),
                "error", e.getCode(),
                "message", e.getMessage(),
                "path", request.getRequestURI()));
    }

    // 관리자 서명 키·TOTP 암호화 키 설정이 잘못된 경우입니다. 설정 값은 응답이나 로그에 남기지 않습니다.
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleMisconfiguration(IllegalStateException e, HttpServletRequest request) {
        log.error("Admin authentication is misconfigured: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", "ADMIN_AUTH_UNAVAILABLE",
                "message", "관리자 인증을 사용할 수 없습니다. 서버 설정을 확인해 주세요.",
                "path", request.getRequestURI()));
    }

    private AdminAuthDTO.LoginResponse toResponse(AdminAuthService.Login login) {
        return new AdminAuthDTO.LoginResponse(login.accessToken(), login.expiresAt(), login.username(), login.role());
    }
}
