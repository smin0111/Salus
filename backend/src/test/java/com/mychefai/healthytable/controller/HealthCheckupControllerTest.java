package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.HealthCheckup;
import com.mychefai.healthytable.dto.HealthCheckupAnalysisDTO;
import com.mychefai.healthytable.dto.HealthCheckupDTO;
import com.mychefai.healthytable.repository.HealthCheckupRepository;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.service.HealthCheckupAnalysisService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HealthCheckupControllerTest {

    private final HealthCheckupRepository healthCheckupRepository = mock(HealthCheckupRepository.class);
    private final HealthCheckupAnalysisService analysisService = mock(HealthCheckupAnalysisService.class);
    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    private final HealthCheckupController controller = new HealthCheckupController(
            healthCheckupRepository,
            analysisService,
            authenticatedUserProvider,
            clock);

    @Test
    void saveCheckupUsesCurrentUserAndCalculatesBmi() {
        HealthCheckupDTO dto = validDto();
        dto.setHeight(170.0);
        dto.setWeight(68.0);
        dto.setBmi(null);

        when(authenticatedUserProvider.requireUserId()).thenReturn(7L);
        when(healthCheckupRepository.save(any(HealthCheckup.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<HealthCheckup> response = controller.saveCheckup(dto);

        ArgumentCaptor<HealthCheckup> captor = ArgumentCaptor.forClass(HealthCheckup.class);
        verify(healthCheckupRepository).save(captor.capture());
        HealthCheckup saved = captor.getValue();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(saved);
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getCheckupDate()).isEqualTo(dto.getCheckupDate());
        assertThat(saved.getBmi()).isEqualTo(23.5);
    }

    @Test
    void saveCheckupRejectsMissingDateBeforeSaving() {
        HealthCheckupDTO dto = validDto();
        dto.setCheckupDate(null);
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        assertThatThrownBy(() -> controller.saveCheckup(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("검진일을 입력해 주세요.");

        verifyNoInteractions(healthCheckupRepository, analysisService);
    }

    @Test
    void saveCheckupRejectsFutureDateBeforeSaving() {
        HealthCheckupDTO dto = validDto();
        dto.setCheckupDate(LocalDate.of(2026, 7, 7));
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        assertThatThrownBy(() -> controller.saveCheckup(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("검진일은 오늘 이후 날짜로 입력할 수 없습니다.");

        verifyNoInteractions(healthCheckupRepository, analysisService);
    }

    @Test
    void saveCheckupRejectsOutOfRangeValueBeforeSaving() {
        HealthCheckupDTO dto = validDto();
        dto.setHeight(999.0);
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        assertThatThrownBy(() -> controller.saveCheckup(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("키 값이 올바르지 않습니다.");

        verifyNoInteractions(healthCheckupRepository, analysisService);
    }

    @Test
    void getLatestCheckupReadsOnlyCurrentUsersLatestCheckup() {
        HealthCheckup checkup = new HealthCheckup();
        checkup.setUserId(1L);

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(1L))
                .thenReturn(Optional.of(checkup));

        ResponseEntity<HealthCheckup> response = controller.getLatestCheckup();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(checkup);
        verify(healthCheckupRepository).findTopByUserIdOrderByCheckupDateDescIdDesc(1L);
    }

    @Test
    void getLatestAnalysisAnalyzesCurrentUsersLatestCheckup() {
        HealthCheckup checkup = new HealthCheckup();
        HealthCheckupAnalysisDTO analysis = HealthCheckupAnalysisDTO.builder()
                .summary("분석 결과")
                .build();

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(1L))
                .thenReturn(Optional.of(checkup));
        when(analysisService.analyze(checkup)).thenReturn(analysis);

        ResponseEntity<HealthCheckupAnalysisDTO> response = controller.getLatestAnalysis();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(analysis);
        verify(analysisService).analyze(checkup);
    }

    @Test
    void getLatestAnalysisReturnsEmptyAnalysisWhenNoCheckupExists() {
        HealthCheckupAnalysisDTO empty = HealthCheckupAnalysisDTO.builder()
                .summary("등록된 건강검진 결과가 없습니다.")
                .build();

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(1L))
                .thenReturn(Optional.empty());
        when(analysisService.emptyAnalysis()).thenReturn(empty);

        ResponseEntity<HealthCheckupAnalysisDTO> response = controller.getLatestAnalysis();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(empty);
        verify(analysisService).emptyAnalysis();
    }

    private HealthCheckupDTO validDto() {
        HealthCheckupDTO dto = new HealthCheckupDTO();
        dto.setCheckupDate(LocalDate.of(2026, 7, 5));
        dto.setHeight(170.0);
        dto.setWeight(68.0);
        dto.setSystolicBp(120);
        dto.setDiastolicBp(80);
        dto.setFastingGlucose(90);
        return dto;
    }
}
