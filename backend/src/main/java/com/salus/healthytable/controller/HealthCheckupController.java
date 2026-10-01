package com.salus.healthytable.controller;

import com.salus.healthytable.domain.HealthCheckup;
import com.salus.healthytable.dto.HealthCheckupAnalysisDTO;
import com.salus.healthytable.dto.HealthCheckupDTO;
import com.salus.healthytable.repository.HealthCheckupRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.HealthCheckupAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 건강검진 결과 API(/api/health-checkups)입니다.
 * 검진 결과 저장, 최신 결과 조회, 최신 결과 기반 분석을 제공합니다.
 */
@RestController
@RequestMapping("/api/health-checkups")
@RequiredArgsConstructor
public class HealthCheckupController {

    // 명백히 잘못된 입력(오타 등)을 막기 위한 허용 범위입니다. 의학적 정상 범위가 아닙니다.
    private static final double MIN_HEIGHT_CM = 50.0;
    private static final double MAX_HEIGHT_CM = 250.0;
    private static final double MIN_WEIGHT_KG = 10.0;
    private static final double MAX_WEIGHT_KG = 500.0;
    private static final double MIN_BMI = 5.0;
    private static final double MAX_BMI = 100.0;

    private final HealthCheckupRepository healthCheckupRepository;
    private final HealthCheckupAnalysisService analysisService;
    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final Clock clock;

    /**
     * 건강검진 결과를 저장합니다. BMI를 보내지 않으면 키와 몸무게로 계산합니다.
     */
    @PostMapping
    public ResponseEntity<HealthCheckup> saveCheckup(@RequestBody HealthCheckupDTO dto) {
        Long userId = authenticatedUserProvider.requireUserId();
        validateCheckup(dto);

        HealthCheckup checkup = new HealthCheckup();
        checkup.setUserId(userId);
        checkup.setCheckupDate(dto.getCheckupDate());
        checkup.setHeight(dto.getHeight());
        checkup.setWeight(dto.getWeight());
        checkup.setBmi(resolveBmi(dto));
        checkup.setSystolicBp(dto.getSystolicBp());
        checkup.setDiastolicBp(dto.getDiastolicBp());
        checkup.setFastingGlucose(dto.getFastingGlucose());
        checkup.setTotalCholesterol(dto.getTotalCholesterol());
        checkup.setHdl(dto.getHdl());
        checkup.setLdl(dto.getLdl());
        checkup.setTriglyceride(dto.getTriglyceride());
        checkup.setAst(dto.getAst());
        checkup.setAlt(dto.getAlt());

        return ResponseEntity.ok(healthCheckupRepository.save(checkup));
    }

    /**
     * 가장 최근 검진 결과를 조회합니다. 기록이 없으면 204 No Content로 응답합니다.
     */
    @GetMapping("/latest")
    public ResponseEntity<HealthCheckup> getLatestCheckup() {
        Long userId = authenticatedUserProvider.requireUserId();
        return healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 가장 최근 검진 결과를 분석합니다. 기록이 없으면 빈 분석 결과를 반환합니다.
     */
    @GetMapping("/analysis")
    public ResponseEntity<HealthCheckupAnalysisDTO> getLatestAnalysis() {
        Long userId = authenticatedUserProvider.requireUserId();
        HealthCheckupAnalysisDTO analysis = healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(userId)
                .map(analysisService::analyze)
                .orElseGet(analysisService::emptyAnalysis);
        return ResponseEntity.ok(analysis);
    }

    // BMI = 몸무게(kg) / 키(m)². 소수점 첫째 자리까지 반올림합니다.
    private Double resolveBmi(HealthCheckupDTO dto) {
        if (dto.getBmi() != null) {
            return dto.getBmi();
        }
        if (dto.getHeight() == null || dto.getWeight() == null || dto.getHeight() <= 0) {
            return null;
        }
        double heightM = dto.getHeight() / 100.0;
        return Math.round((dto.getWeight() / (heightM * heightM)) * 10.0) / 10.0;
    }

    // 검진일은 필수이며 미래 날짜일 수 없습니다. 각 수치는 입력된 경우에만 허용 범위를 확인합니다.
    private void validateCheckup(HealthCheckupDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("건강검진 정보를 입력해 주세요.");
        }
        if (dto.getCheckupDate() == null) {
            throw new IllegalArgumentException("검진일을 입력해 주세요.");
        }
        if (dto.getCheckupDate().isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("검진일은 오늘 이후 날짜로 입력할 수 없습니다.");
        }

        validateDoubleRange(dto.getHeight(), MIN_HEIGHT_CM, MAX_HEIGHT_CM, "키");
        validateDoubleRange(dto.getWeight(), MIN_WEIGHT_KG, MAX_WEIGHT_KG, "몸무게");
        validateDoubleRange(dto.getBmi(), MIN_BMI, MAX_BMI, "BMI");
        validateIntegerRange(dto.getSystolicBp(), 50, 300, "수축기 혈압");
        validateIntegerRange(dto.getDiastolicBp(), 30, 200, "이완기 혈압");
        validateIntegerRange(dto.getFastingGlucose(), 20, 1000, "공복혈당");
        validateIntegerRange(dto.getTotalCholesterol(), 0, 2000, "총콜레스테롤");
        validateIntegerRange(dto.getHdl(), 0, 2000, "HDL");
        validateIntegerRange(dto.getLdl(), 0, 2000, "LDL");
        validateIntegerRange(dto.getTriglyceride(), 0, 5000, "중성지방");
        validateIntegerRange(dto.getAst(), 0, 5000, "AST");
        validateIntegerRange(dto.getAlt(), 0, 5000, "ALT");
    }

    private void validateDoubleRange(Double value, double min, double max, String fieldName) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " 값이 올바르지 않습니다.");
        }
    }

    private void validateIntegerRange(Integer value, int min, int max, String fieldName) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " 값이 올바르지 않습니다.");
        }
    }
}
