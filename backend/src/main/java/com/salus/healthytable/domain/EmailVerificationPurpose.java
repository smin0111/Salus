package com.salus.healthytable.domain;

/**
 * 이메일 인증 코드의 용도입니다. 용도가 다르면 같은 이메일·코드라도 서로 쓸 수 없습니다.
 */
public enum EmailVerificationPurpose {
    SIGNUP,
    PASSWORD_RESET
}
