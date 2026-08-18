package com.salus.healthytable.service;

import com.salus.healthytable.domain.HealthCheckup;
import com.salus.healthytable.dto.HealthCheckupAnalysisDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class HealthCheckupAnalysisService {

    public HealthCheckupAnalysisDTO analyze(HealthCheckup checkup) {
        if (checkup == null) {
            return emptyAnalysis();
        }

        List<String> risks = new ArrayList<>();
        List<String> policies = new ArrayList<>();
        List<String> foodGuides = new ArrayList<>();

        Double bmi = resolveBmi(checkup);
        if (bmi != null && bmi >= 25) {
            risks.add("체중 관리 필요");
            policies.add("총열량을 낮추고 단백질과 식이섬유가 충분한 메뉴를 우선 추천");
            foodGuides.add("현미, 닭가슴살, 두부, 생선, 채소류를 활용하고 튀김류와 고당 간식은 줄이기");
        }

        if ((checkup.getSystolicBp() != null && checkup.getSystolicBp() >= 130)
                || (checkup.getDiastolicBp() != null && checkup.getDiastolicBp() >= 80)) {
            risks.add("혈압 관리 필요");
            policies.add("저염식 위주로 추천하고 국물, 가공육, 짠 양념을 제한");
            foodGuides.add("나트륨이 낮은 조리법, 칼륨이 풍부한 채소와 과일을 우선 고려");
        }

        if (checkup.getFastingGlucose() != null && checkup.getFastingGlucose() >= 100) {
            risks.add("혈당 관리 필요");
            policies.add("정제 탄수화물과 당류를 줄이고 복합 탄수화물과 단백질 균형을 고려");
            foodGuides.add("흰쌀밥, 설탕 많은 소스, 음료류를 줄이고 잡곡, 달걀, 콩류를 활용");
        }

        if ((checkup.getLdl() != null && checkup.getLdl() >= 130)
                || (checkup.getTriglyceride() != null && checkup.getTriglyceride() >= 150)
                || (checkup.getTotalCholesterol() != null && checkup.getTotalCholesterol() >= 200)) {
            risks.add("지질 관리 필요");
            policies.add("포화지방과 튀김류를 줄이고 불포화지방, 생선, 채소 중심으로 추천");
            foodGuides.add("등푸른 생선, 견과류, 올리브오일은 적정량 활용하고 가공육은 피하기");
        }

        if ((checkup.getAst() != null && checkup.getAst() >= 40)
                || (checkup.getAlt() != null && checkup.getAlt() >= 40)) {
            risks.add("간 수치 관리 필요");
            policies.add("과음과 고지방 식사를 피하고 담백한 단백질과 채소 중심으로 추천");
            foodGuides.add("튀긴 음식, 야식, 과도한 당류를 줄이고 삶기, 굽기 조리법을 우선");
        }

        String summary = risks.isEmpty()
                ? "최근 검진 지표에서 특별한 식단 주의 항목이 크지 않습니다. 균형 잡힌 식사를 유지해보세요."
                : "최근 검진 기준으로 " + String.join(", ", risks) + " 항목을 고려한 식단 추천이 필요합니다.";

        return HealthCheckupAnalysisDTO.builder()
                .checkupId(checkup.getId())
                .checkupDate(checkup.getCheckupDate() != null ? checkup.getCheckupDate().toString() : null)
                .summary(summary)
                .risks(risks)
                .recommendationPolicies(policies)
                .foodGuides(foodGuides)
                .build();
    }

    public HealthCheckupAnalysisDTO emptyAnalysis() {
        return HealthCheckupAnalysisDTO.builder()
                .summary("등록된 건강검진 결과가 없습니다.")
                .build();
    }

    private Double resolveBmi(HealthCheckup checkup) {
        if (checkup.getBmi() != null) {
            return checkup.getBmi();
        }
        if (checkup.getHeight() == null || checkup.getWeight() == null || checkup.getHeight() <= 0) {
            return null;
        }
        double heightM = checkup.getHeight() / 100.0;
        return Math.round((checkup.getWeight() / (heightM * heightM)) * 10.0) / 10.0;
    }
}
