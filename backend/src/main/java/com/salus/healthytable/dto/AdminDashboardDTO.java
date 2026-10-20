package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 관리자 대시보드 화면에 내려 주는 통계 응답 DTO입니다.
 *
 * DTO(Data Transfer Object)는 계층 간/서버-클라이언트 간 데이터를 주고받기 위한 객체로,
 * 엔티티를 그대로 노출하지 않고 화면에 필요한 값만 담습니다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminDashboardDTO {
    // 기본 지표: 일일 활동 사용자 수(DAU), 신규 가입자 수, API 비용, 서버 상태
    private long dau;
    private long newUsers;
    private double apiCost;
    private Map<String, String> serverStatus;

    // 전체 회원 통계
    private long totalUsers; // 전체 회원 수
    private long plusUsers; // PLUS 구독자 수
    private double dauTrend; // 전일 대비 DAU 증감률 (%)

    // 결제 통계
    private long todayPaymentCount; // 오늘 결제 건수
    private long todayPaymentAmount; // 오늘 매출 합계 (원)
    private long monthPaymentCount; // 이번 달 결제 건수
    private long monthPaymentAmount; // 이번 달 매출 합계 (원)
    private List<DailyPaymentStat> dailyPaymentStats; // 최근 7일 일별 결제 통계

    // 일별 결제 통계 한 줄(차트의 막대 하나)을 표현하는 내부 클래스입니다.
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
