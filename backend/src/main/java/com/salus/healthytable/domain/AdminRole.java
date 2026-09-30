package com.salus.healthytable.domain;

/**
 * 관리자 역할입니다. 서비스 회원의 UserRole과는 별개입니다.
 * ADMIN_VIEWER: 조회만 가능, ADMIN: 조회와 변경(계정 관리 등) 모두 가능
 */
public enum AdminRole {
    ADMIN_VIEWER, ADMIN
}
