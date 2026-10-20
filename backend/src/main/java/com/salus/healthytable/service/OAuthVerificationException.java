package com.salus.healthytable.service;

/**
 * 소셜 토큰 검증 실패를 나타냅니다. reason은 로그에만 남기는 실패 원인 코드이며,
 * 토큰이나 개인정보를 담지 않습니다.
 */
public class OAuthVerificationException extends RuntimeException {

    private final String reason;

    public OAuthVerificationException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
