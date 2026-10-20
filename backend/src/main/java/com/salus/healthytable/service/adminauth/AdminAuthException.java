package com.salus.healthytable.service.adminauth;

import org.springframework.http.HttpStatus;

/**
 * 관리자 로그인 실패입니다. code는 클라이언트가 화면을 고르는 데 쓰는 값이며,
 * 계정 존재 여부처럼 공격에 도움이 되는 정보는 담지 않습니다.
 */
public class AdminAuthException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AdminAuthException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AdminAuthException invalidCredentials() {
        return new AdminAuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "아이디, 비밀번호 또는 인증 코드가 올바르지 않습니다.");
    }

    public static AdminAuthException locked() {
        return new AdminAuthException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }

    public static AdminAuthException challengeInvalid() {
        return new AdminAuthException(HttpStatus.UNAUTHORIZED, "CHALLENGE_INVALID", "로그인 단계가 만료되었습니다. 처음부터 다시 로그인해 주세요.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
