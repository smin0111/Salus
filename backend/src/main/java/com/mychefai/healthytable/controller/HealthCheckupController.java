package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.HealthCheckup;
import com.mychefai.healthytable.dto.HealthCheckupAnalysisDTO;
import com.mychefai.healthytable.dto.HealthCheckupDTO;
import com.mychefai.healthytable.repository.HealthCheckupRepository;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.service.HealthCheckupAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/health-checkups")
@RequiredArgsConstructor
public class HealthCheckupController {

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

    @GetMapping("/latest")
    public ResponseEntity<HealthCheckup> getLatestCheckup() {
        Long userId = authenticatedUserProvider.requireUserId();
        return healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/analysis")
    public ResponseEntity<HealthCheckupAnalysisDTO> getLatestAnalysis() {
        Long userId = authenticatedUserProvider.requireUserId();
        HealthCheckupAnalysisDTO analysis = healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(userId)
                .map(analysisService::analyze)
                .orElseGet(analysisService::emptyAnalysis);
        return ResponseEntity.ok(analysis);
    }

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
