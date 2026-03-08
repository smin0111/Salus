package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.UserGrade;
import com.mychefai.healthytable.dto.AdminDashboardDTO;
import com.mychefai.healthytable.repository.ActivityLogRepository;
import com.mychefai.healthytable.repository.PaymentRepository;
import com.mychefai.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/stats")
    public AdminDashboardDTO getStats() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.plusDays(1).atStartOfDay();

        LocalDate yesterday = today.minusDays(1);
        LocalDateTime yesterdayStart = yesterday.atStartOfDay();

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
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        List<AdminDashboardDTO.DailyPaymentStat> dailyStats = new ArrayList<>();
        for (Object[] row : rawStats) {
            dailyStats.add(AdminDashboardDTO.DailyPaymentStat.builder()
                    .date(row[0] != null ? row[0].toString().substring(5) : "??-??") // yyyy-MM-dd → MM-dd
                    .count(row[1] != null ? ((Number) row[1]).longValue() : 0)
                    .amount(row[2] != null ? ((Number) row[2]).longValue() : 0)
                    .build());
        }

        // ─── 서버 상태 ───
        Map<String, String> serverStatus = new HashMap<>();
        serverStatus.put("auth", "healthy");
        serverStatus.put("chat", "healthy");
        serverStatus.put("recipe", "healthy");
        serverStatus.put("db", "healthy");
        serverStatus.put("payment", "healthy");

        // ─── 에러 로그 (현재는 빈 리스트, 에러 발생 시 여기에 추가) ───
        List<String> errorLogs = new ArrayList<>();

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
                .errorLogs(errorLogs)
                .build();
    }
}
