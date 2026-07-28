package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminDashboardDTO {
    // 기존 필드
    private long dau;
    private long newUsers;
    private double apiCost;
    private Map<String, String> serverStatus;

    // 신규: 전체 회원 통계
    private long totalUsers; // 전체 회원 수
    private long plusUsers; // PLUS 구독자 수
    private double dauTrend; // 전일 대비 DAU 증감률 (%)

    // 신규: 결제 통계
    private long todayPaymentCount; // 오늘 결제 건수
    private long todayPaymentAmount; // 오늘 매출 합계 (원)
    private long monthPaymentCount; // 이번 달 결제 건수
    private long monthPaymentAmount; // 이번 달 매출 합계 (원)
    private List<DailyPaymentStat> dailyPaymentStats; // 최근 7일 일별 결제 통계

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyPaymentStat {
        private String date; // 날짜 (MM-dd)
        private long count; // 결제 건수
        private long amount; // 결제 금액 합계
    }
}
