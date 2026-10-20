package com.salus.healthytable.service.adminauth;

import com.salus.healthytable.domain.AdminRole;

/**
 * 인증된 관리자 정보입니다. 관리자 보안 설정의 SecurityContext principal로 들어갑니다.
 */
public record AdminPrincipal(Long adminId, String username, AdminRole role, String sessionId) {
}
