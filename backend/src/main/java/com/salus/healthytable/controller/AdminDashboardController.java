package com.salus.healthytable.controller;

import com.salus.healthytable.domain.UserGrade;
import com.salus.healthytable.dto.AdminDashboardDTO;
import com.salus.healthytable.repository.ActivityLogRepository;
import com.salus.healthytable.repository.PaymentRepository;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final UserRepository userRepository;
    private final ActivityLogRepository activityLogRepository;
    private final PaymentRepository paymentRepository;
    private final HealthEndpoint healthEndpoint;
    private final Clock clock;

    @GetMapping("/auth-check")
    public Map<String, Object> checkAdminAuth() {
        return Map.of("authenticated", true);
    }

    @GetMapping("/stats")
    public AdminDashboardDTO getStats() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.plusDays(1).atStartOfDay();

        LocalDate yesterday = today.minusDays(1);

        // ─── 사용자 통계 ───
        long totalUsers = userRepository.count();
        long plusUsers = userRepository.countByGrade(UserGrade.PLUS);
        long newUsers = userRepository.countByCreatedAtAfter(todayStart);

        // DAU (오늘 활동자)
        long dau = activityLogRepository.countByActivityDate(today);
        long dauYesterday = activityLogRepository.countByActivityDate(yesterday);
        double dauTrend = dauYesterday > 0
                ? Math.round(((double) (dau - dauYesterday) / dauYesterday) * 1000.0) / 10.0
                : 0;

        // ─── AI API 비용 ───
        long aiInteractions = activityLogRepository.countByActivityDateAndHasAiInteraction(today, true);
        double apiCost = Math.round(aiInteractions * 0.15 * 100.0) / 100.0;

        // ─── 결제 통계 ───
        long todayPaymentCount = paymentRepository.countByPaidAtBetweenAndStatus(todayStart, todayEnd, "paid");
        long todayPaymentAmount = paymentRepository.sumAmountByPaidAtBetweenAndStatus(todayStart, todayEnd, "paid");

        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        long monthPaymentCount = paymentRepository.countByPaidAtBetweenAndStatus(monthStart, todayEnd, "paid");
        long monthPaymentAmount = paymentRepository.sumAmountByPaidAtBetweenAndStatus(monthStart, todayEnd, "paid");

        // 최근 7일 일별 통계
        LocalDateTime sevenDaysAgo = today.minusDays(6).atStartOfDay();
        List<Object[]> rawStats = paymentRepository.findDailyPaymentStats(sevenDaysAgo);
        List<AdminDashboardDTO.DailyPaymentStat> dailyStats = new ArrayList<>();
        for (Object[] row : safeRows(rawStats)) {
            dailyStats.add(AdminDashboardDTO.DailyPaymentStat.builder()
                    .date(formatDailyStatDate(valueAt(row, 0)))
                    .count(toLong(valueAt(row, 1)))
                    .amount(toLong(valueAt(row, 2)))
                    .build());
        }

        // ─── 서버 상태 ───
        Map<String, String> serverStatus = new LinkedHashMap<>();
        serverStatus.put("application", readApplicationHealthStatus());
        serverStatus.put("auth", "healthy");
        serverStatus.put("db", "healthy");

        return AdminDashboardDTO.builder()
                .totalUsers(totalUsers)
                .plusUsers(plusUsers)
                .dau(dau)
                .dauTrend(dauTrend)
                .newUsers(newUsers)
                .apiCost(apiCost)
                .serverStatus(serverStatus)
                .todayPaymentCount(todayPaymentCount)
                .todayPaymentAmount(todayPaymentAmount)
                .monthPaymentCount(monthPaymentCount)
                .monthPaymentAmount(monthPaymentAmount)
                .dailyPaymentStats(dailyStats)
                .build();
    }

    private List<Object[]> safeRows(List<Object[]> rows) {
        return rows == null ? List.of() : rows;
    }

    private Object valueAt(Object[] row, int index) {
        if (row == null || row.length <= index) {
            return null;
        }
        return row[index];
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private String readApplicationHealthStatus() {
        try {
            HealthComponent health = healthEndpoint.health();
            if (health == null || health.getStatus() == null) {
                return "unknown";
            }
            return Status.UP.equals(health.getStatus()) ? "healthy" : "unhealthy";
        } catch (RuntimeException ex) {
            return "unknown";
        }
    }

    private String formatDailyStatDate(Object value) {
        if (value instanceof LocalDate date) {
            return date.format(DateTimeFormatter.ofPattern("MM-dd"));
        }
        if (value == null) {
            return "??-??";
        }

        String text = value.toString();
        return text.length() >= 10 ? text.substring(5, 10) : text;
    }
}
