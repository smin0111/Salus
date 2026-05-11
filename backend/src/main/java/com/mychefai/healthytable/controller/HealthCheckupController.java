package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.HealthCheckup;
import com.mychefai.healthytable.dto.HealthCheckupAnalysisDTO;
import com.mychefai.healthytable.dto.HealthCheckupDTO;
import com.mychefai.healthytable.repository.HealthCheckupRepository;
import com.mychefai.healthytable.security.JwtTokenProvider;
import com.mychefai.healthytable.service.HealthCheckupAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/health-checkups")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class HealthCheckupController {

    private final HealthCheckupRepository healthCheckupRepository;
    private final HealthCheckupAnalysisService analysisService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping
    public ResponseEntity<HealthCheckup> saveCheckup(
            @RequestHeader("Authorization") String token,
            @RequestBody HealthCheckupDTO dto) {
        Long userId = getUserId(token);

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
    public ResponseEntity<HealthCheckup> getLatestCheckup(@RequestHeader("Authorization") String token) {
        Long userId = getUserId(token);
        return healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/analysis")
    public ResponseEntity<HealthCheckupAnalysisDTO> getLatestAnalysis(@RequestHeader("Authorization") String token) {
        Long userId = getUserId(token);
        HealthCheckupAnalysisDTO analysis = healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(userId)
                .map(analysisService::analyze)
                .orElseGet(analysisService::emptyAnalysis);
        return ResponseEntity.ok(analysis);
    }

    private Long getUserId(String token) {
        return Long.valueOf(jwtTokenProvider.getUserId(token.replace("Bearer ", "")));
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
}
