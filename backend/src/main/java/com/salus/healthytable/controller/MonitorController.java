package com.salus.healthytable.controller;

import com.salus.healthytable.dto.AdminDashboardDTO;
import com.salus.healthytable.service.DashboardStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 24시간 관제 화면 API(/api/monitor)입니다.
 * 디스플레이 토큰(ROLE_MONITOR)으로만 호출되며, 개인정보가 없는 집계 통계만 제공합니다.
 */
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final DashboardStatsService dashboardStatsService;

    @GetMapping("/stats")
    public AdminDashboardDTO getStats() {
        return dashboardStatsService.getStats();
    }
}
