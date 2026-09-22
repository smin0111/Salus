package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 건강검진 결과를 분석한 응답 DTO입니다.
 * 요약, 위험 요인, 추천 정책, 식품 가이드를 담습니다(의학적 진단이 아닌 참고용 정보입니다).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthCheckupAnalysisDTO {
    private Long checkupId;
    private String checkupDate;
    private String summary;

    // @Builder.Default: 빌더로 객체를 만들 때 값을 지정하지 않아도 null 대신 빈 리스트로 초기화됩니다.
    @Builder.Default
    private List<String> risks = new ArrayList<>();

    @Builder.Default
    private List<String> recommendationPolicies = new ArrayList<>();

    @Builder.Default
    private List<String> foodGuides = new ArrayList<>();
}
