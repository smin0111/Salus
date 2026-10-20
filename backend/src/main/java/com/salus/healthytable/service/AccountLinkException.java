package com.salus.healthytable.service;

import org.springframework.http.HttpStatus;

/**
 * 소셜 계정 연결 실패입니다. code는 앱이 안내 문구를 고르는 데 쓰는 값입니다.
 */
public class AccountLinkException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AccountLinkException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    // 만료·사용됨·취소됨·위조·flowNonce 불일치를 구분하지 않고 같은 응답을 줍니다.
    public static AccountLinkException invalidRequest() {
        return new AccountLinkException(HttpStatus.CONFLICT, "LINK_INVALID",
                "계정 연결 요청이 만료되었거나 유효하지 않습니다. 처음부터 다시 로그인해 주세요.");
    }

    public static AccountLinkException reauthAccountNotFound() {
        return new AccountLinkException(HttpStatus.CONFLICT, "LINK_REAUTH_NOT_FOUND",
                "이 로그인 수단으로 가입된 회원이 없습니다. 연결할 기존 계정의 로그인 수단으로 로그인해 주세요.");
    }

    public static AccountLinkException targetMismatch() {
        return new AccountLinkException(HttpStatus.CONFLICT, "LINK_TARGET_MISMATCH",
                "다른 회원 계정으로 로그인했습니다. 같은 이메일로 가입된 기존 계정으로 로그인해 주세요.");
    }

    public static AccountLinkException alreadyLinked() {
        return new AccountLinkException(HttpStatus.CONFLICT, "LINK_ALREADY_LINKED",
                "이 소셜 계정은 이미 다른 회원에 연결되어 있거나 같은 종류의 로그인 수단이 이미 연결되어 있습니다.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
