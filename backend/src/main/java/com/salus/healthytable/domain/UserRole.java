package com.salus.healthytable.domain;

/**
 * 회원 권한입니다. ADMIN만 관리자 API(/api/admin/**)에 접근할 수 있습니다.
 */
public enum UserRole {
    USER, ADMIN
}
