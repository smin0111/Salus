package com.salus.healthytable.dto;

import java.time.LocalDateTime;

/**
 * 관리자 로그인 API의 요청·응답 형식입니다.
 */
public final class AdminAuthDTO {

    private AdminAuthDTO() {
    }

    public record LoginRequest(String username, String password) {
    }

    public record PasswordChangeRequest(String challengeToken, String newPassword) {
    }

    public record ChallengeRequest(String challengeToken) {
    }

    public record TotpRequest(String challengeToken, String code) {
    }

    // step: 다음 로그인 단계(PASSWORD_CHANGE, TOTP_SETUP, TOTP)
    public record ChallengeResponse(String step, String challengeToken) {
    }

    public record TotpSetupResponse(String otpauthUri, String secret) {
    }

    public record LoginResponse(String token, LocalDateTime expiresAt, String username, String role) {
    }

    public record MeResponse(String username, String role) {
    }
}
