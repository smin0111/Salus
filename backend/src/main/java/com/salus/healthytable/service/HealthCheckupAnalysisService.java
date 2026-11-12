package com.salus.healthytable.service;

import com.salus.healthytable.domain.HealthCheckup;
import com.salus.healthytable.dto.HealthCheckupAnalysisDTO;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.service.allergen.AllergenMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 최근 건강검진 수치를 단순 기준값과 비교해 식단 추천 방향을 만드는 서비스입니다.
 *
 * LLM을 사용하지 않는 규칙 기반 분석이며, 결과는 식단 참고 정보일 뿐 의학적 진단이 아닙니다.
 */
@Slf4j
@Service
public class HealthCheckupAnalysisService {

    static final String NO_VALUES_SUMMARY = "입력된 검진 수치가 없어 식단 주의 항목을 판단할 수 없어요. 검진표의 수치를 입력해 주세요.";
    static final String ALLERGY_UNKNOWN_GUIDE =
            "알레르기 정보를 불러오지 못했어요. 위 식품 중 알레르기가 있는 재료는 직접 확인하고 빼 주세요.";

    private final HealthProfileRepository healthProfileRepository;
    private final AllergenMatcher allergenMatcher;

    public HealthCheckupAnalysisService(HealthProfileRepository healthProfileRepository, AllergenMatcher allergenMatcher) {
        this.healthProfileRepository = healthProfileRepository;
        this.allergenMatcher = allergenMatcher;
    }

    /**
     * 회원 화면용 분석입니다. 식품 가이드가 등록 알레르기 재료(예: 두부·달걀·생선·견과류)를 권하면 그 줄에 빼라는 안내를 붙입니다.
     * 알레르기 정보를 읽지 못하면 "알레르기 없음"으로 보지 않고 직접 확인하라는 안내를 붙입니다(모르는 것은 안전이 아님).
     */
    public HealthCheckupAnalysisDTO analyzeForUser(Long userId, HealthCheckup checkup) {
        HealthCheckupAnalysisDTO analysis = analyze(checkup);
        if (analysis.getFoodGuides() == null || analysis.getFoodGuides().isEmpty()) {
            return analysis;
        }
        List<String> allergies;
        try {
            allergies = healthProfileRepository.findByUserId(userId)
                    .map(profile -> profile.getAllergies() == null ? List.<String>of() : profile.getAllergies())
                    .orElse(List.of())
                    .stream()
                    .filter(value -> value != null && !value.isBlank())
                    .flatMap(value -> Stream.of(value.split("[,/·，\\n]")))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        } catch (RuntimeException error) {
            log.warn("[HealthCheckupAnalysis] category=ALLERGY_LOAD_FAILED, exceptionClass={}", error.getClass().getSimpleName());
            List<String> guides = new ArrayList<>(analysis.getFoodGuides());
            guides.add(ALLERGY_UNKNOWN_GUIDE);
            analysis.setFoodGuides(guides);
            return analysis;
        }
        if (allergies.isEmpty()) {
            return analysis;
        }
        List<String> guides = analysis.getFoodGuides().stream()
                .map(guide -> {
                    List<String> conflicts = allergenMatcher.findConflicts(allergies, List.of(guide));
                    return conflicts.isEmpty() ? guide
                            : guide + " (등록한 알레르기 " + String.join(", ", conflicts) + "에 해당하는 식품은 빼 주세요)";
                })
                .toList();
        analysis.setFoodGuides(guides);
        return analysis;
    }

    /**
     * 검진 결과를 분석합니다. 기준을 넘은 항목마다 위험 요인, 추천 정책, 식품 가이드를 한 줄씩 추가합니다.
     * 값이 입력되지 않은 항목(null)은 판단하지 않고 건너뜁니다.
     */
    public HealthCheckupAnalysisDTO analyze(HealthCheckup checkup) {
        if (checkup == null) {
            return emptyAnalysis();
        }

        List<String> risks = new ArrayList<>();
        List<String> policies = new ArrayList<>();
        List<String> foodGuides = new ArrayList<>();

        // 체질량지수(BMI) 25 이상
        Double bmi = resolveBmi(checkup);
        if (bmi != null && bmi >= 25) {
            risks.add("체중 관리 필요");
            policies.add("총열량을 낮추고 단백질과 식이섬유가 충분한 메뉴를 우선 추천");
            foodGuides.add("현미, 닭가슴살, 두부, 생선, 채소류를 활용하고 튀김류와 고당 간식은 줄이기");
        }

        // 수축기 130 이상 또는 이완기 80 이상
        if ((checkup.getSystolicBp() != null && checkup.getSystolicBp() >= 130)
                || (checkup.getDiastolicBp() != null && checkup.getDiastolicBp() >= 80)) {
            risks.add("혈압 관리 필요");
            policies.add("저염식 위주로 추천하고 국물, 가공육, 짠 양념을 제한");
            foodGuides.add("나트륨이 낮은 조리법, 칼륨이 풍부한 채소와 과일을 우선 고려");
        }

        // 공복 혈당 100 이상
        if (checkup.getFastingGlucose() != null && checkup.getFastingGlucose() >= 100) {
            risks.add("혈당 관리 필요");
            policies.add("정제 탄수화물과 당류를 줄이고 복합 탄수화물과 단백질 균형을 고려");
            foodGuides.add("흰쌀밥, 설탕 많은 소스, 음료류를 줄이고 잡곡, 달걀, 콩류를 활용");
        }

        // LDL 130 이상, 중성지방 150 이상, 총콜레스테롤 200 이상 중 하나
        if ((checkup.getLdl() != null && checkup.getLdl() >= 130)
                || (checkup.getTriglyceride() != null && checkup.getTriglyceride() >= 150)
                || (checkup.getTotalCholesterol() != null && checkup.getTotalCholesterol() >= 200)) {
            risks.add("지질 관리 필요");
            policies.add("포화지방과 튀김류를 줄이고 불포화지방, 생선, 채소 중심으로 추천");
            foodGuides.add("등푸른 생선, 견과류, 올리브오일은 적정량 활용하고 가공육은 피하기");
        }

        // AST 또는 ALT 40 이상
        if ((checkup.getAst() != null && checkup.getAst() >= 40)
                || (checkup.getAlt() != null && checkup.getAlt() >= 40)) {
            risks.add("간 수치 관리 필요");
            policies.add("과음과 고지방 식사를 피하고 담백한 단백질과 채소 중심으로 추천");
            foodGuides.add("튀긴 음식, 야식, 과도한 당류를 줄이고 삶기, 굽기 조리법을 우선");
        }

        // 수치를 하나도 입력하지 않은 기록을 "주의 항목 없음"으로 안심시키지 않습니다(모르는 것은 정상이 아님).
        String summary = !hasAnyValue(checkup)
                ? NO_VALUES_SUMMARY
                : risks.isEmpty()
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

    // 검진 기록이 없을 때 반환하는 빈 분석 결과입니다.
    public HealthCheckupAnalysisDTO emptyAnalysis() {
        return HealthCheckupAnalysisDTO.builder()
                .summary("등록된 건강검진 결과가 없습니다.")
                .build();
    }

    private boolean hasAnyValue(HealthCheckup checkup) {
        return Stream.of(checkup.getBmi(), checkup.getHeight(), checkup.getWeight(), checkup.getSystolicBp(),
                        checkup.getDiastolicBp(), checkup.getFastingGlucose(), checkup.getLdl(), checkup.getTriglyceride(),
                        checkup.getTotalCholesterol(), checkup.getHdl(), checkup.getAst(), checkup.getAlt())
                .anyMatch(java.util.Objects::nonNull);
    }

    // 저장된 BMI가 없으면 키와 몸무게로 계산합니다.
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
