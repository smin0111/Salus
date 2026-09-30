package com.salus.healthytable.service;

import com.salus.healthytable.domain.UserGrade;
import com.salus.healthytable.dto.AdminDashboardDTO;
import com.salus.healthytable.repository.ActivityLogRepository;
import com.salus.healthytable.repository.PaymentRepository;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 관리자 대시보드와 24시간 관제 화면이 함께 쓰는 집계 통계를 만듭니다.
 */
@Service
@RequiredArgsConstructor
public class DashboardStatsService {

    private final UserRepository userRepository;
    private final ActivityLogRepository activityLogRepository;
    private final PaymentRepository paymentRepository;
    private final HealthEndpoint healthEndpoint;
    private final Clock clock;

    /**
     * 대시보드 통계(회원, DAU, AI 사용량, 결제, 서버 상태)를 한 번에 조회합니다.
     * 모두 집계값이며 개인 단위 데이터(이메일, 이름, 건강 정보)는 담지 않습니다.
     */
    @Transactional(readOnly = true)
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
        // 전일 대비 증감률(%)을 소수점 첫째 자리까지 계산합니다. 어제 활동자가 0명이면 나눌 수 없으므로 0으로 둡니다.
        double dauTrend = dauYesterday > 0
                ? Math.round(((double) (dau - dauYesterday) / dauYesterday) * 1000.0) / 10.0
                : 0;

        // ─── AI API 비용 ───
        long aiInteractions = activityLogRepository.countByActivityDateAndHasAiInteraction(today, true);
        // 실제 청구 금액이 아니라, AI 사용 건수 × 건당 0.15로 계산한 추정치입니다.
        double apiCost = Math.round(aiInteractions * 0.15 * 100.0) / 100.0;

        // ─── 결제 통계 ───
        long todayPaymentCount = paymentRepository.countByPaidAtBetweenAndStatus(todayStart, todayEnd, "paid");
        long todayPaymentAmount = paymentRepository.sumAmountByPaidAtBetweenAndStatus(todayStart, todayEnd, "paid");

        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        long monthPaymentCount = paymentRepository.countByPaidAtBetweenAndStatus(monthStart, todayEnd, "paid");
        long monthPaymentAmount = paymentRepository.sumAmountByPaidAtBetweenAndStatus(monthStart, todayEnd, "paid");

        // 최근 7일 일별 통계
        // 오늘을 포함해 7일이 되도록 6일 전 0시부터 조회합니다.
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
        // auth, db는 실제 점검 결과가 아니라 고정값입니다. application만 Actuator 헬스 체크 결과를 반영합니다.
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

    // 아래 메서드들은 네이티브 집계 결과(Object[])를 안전하게 꺼내기 위한 도우미입니다.
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

    // Spring Boot Actuator의 헬스 체크 결과를 대시보드용 문자열(healthy/unhealthy/unknown)로 바꿉니다.
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

    // DB 드라이버에 따라 날짜가 LocalDate 또는 문자열(yyyy-MM-dd)로 올 수 있어 두 경우 모두 MM-dd로 맞춥니다.
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
