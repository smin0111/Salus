package com.salus.healthytable.controller;

import com.salus.healthytable.dto.AdminDashboardDTO;
import com.salus.healthytable.service.DashboardStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 관리자 대시보드 API(/api/admin/dashboard)입니다.
 * SecurityConfig의 관리자 보안 설정에서 ADMIN 권한이 있는 경우만 접근할 수 있습니다.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardStatsService dashboardStatsService;

    /**
     * 관리자 웹이 로그인 직후 "관리자 권한이 있는지" 확인하는 용도입니다.
     * 이 메서드까지 도달했다는 것 자체가 ADMIN 권한 검사를 통과했다는 뜻입니다.
     */
    @GetMapping("/auth-check")
    public Map<String, Object> checkAdminAuth() {
        return Map.of("authenticated", true);
    }

    /**
     * 대시보드 통계(회원, DAU, AI 사용량, 결제, 서버 상태)를 한 번에 조회합니다.
     */
    @GetMapping("/stats")
    public AdminDashboardDTO getStats() {
        return dashboardStatsService.getStats();
    }
}
