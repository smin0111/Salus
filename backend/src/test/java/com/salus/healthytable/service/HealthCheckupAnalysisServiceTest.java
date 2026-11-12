package com.salus.healthytable.service;

import com.salus.healthytable.domain.HealthCheckup;
import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.dto.HealthCheckupAnalysisDTO;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.service.allergen.AllergenDictionary;
import com.salus.healthytable.service.allergen.AllergenMatcher;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 건강검진 분석의 식품 가이드가 등록 알레르기와 충돌할 때와, 수치가 없을 때의 동작을 확인합니다(2026-11-06 보안 점검).
 */
class HealthCheckupAnalysisServiceTest {

    private final HealthProfileRepository profiles = mock(HealthProfileRepository.class);
    private final HealthCheckupAnalysisService service = new HealthCheckupAnalysisService(profiles, matcher());

    private static AllergenMatcher matcher() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenMatcher(dictionary);
    }

    private static HealthCheckup glucoseHigh() {
        HealthCheckup checkup = new HealthCheckup();
        checkup.setCheckupDate(LocalDate.of(2026, 11, 1));
        checkup.setFastingGlucose(120);
        return checkup;
    }

    // 혈당 가이드는 "달걀, 콩류"를 권합니다. 달걀 알레르기가 등록돼 있으면 그 줄에 빼라는 안내가 붙어야 합니다.
    @Test
    void foodGuideConflictingWithRegisteredAllergyGetsExclusionNotice() {
        HealthProfile profile = new HealthProfile();
        profile.setAllergies(List.of("달걀"));
        when(profiles.findByUserId(1L)).thenReturn(Optional.of(profile));

        HealthCheckupAnalysisDTO analysis = service.analyzeForUser(1L, glucoseHigh());

        assertThat(analysis.getFoodGuides()).anySatisfy(guide ->
                assertThat(guide).contains("달걀").contains("등록한 알레르기").contains("빼 주세요"));
    }

    // 관련 없는 알레르기(새우)면 가이드를 바꾸지 않습니다(오탐 방지).
    @Test
    void unrelatedAllergyLeavesGuideUnchanged() {
        HealthProfile profile = new HealthProfile();
        profile.setAllergies(List.of("새우"));
        when(profiles.findByUserId(1L)).thenReturn(Optional.of(profile));

        HealthCheckupAnalysisDTO analysis = service.analyzeForUser(1L, glucoseHigh());

        assertThat(analysis.getFoodGuides()).noneSatisfy(guide -> assertThat(guide).contains("등록한 알레르기"));
    }

    // 알레르기 정보를 읽지 못하면 "알레르기 없음"으로 보지 않고 직접 확인하라는 안내를 붙입니다.
    @Test
    void allergyLoadFailureAddsManualCheckNotice() {
        when(profiles.findByUserId(1L)).thenThrow(new IllegalStateException("db down"));

        HealthCheckupAnalysisDTO analysis = service.analyzeForUser(1L, glucoseHigh());

        assertThat(analysis.getFoodGuides()).contains(HealthCheckupAnalysisService.ALLERGY_UNKNOWN_GUIDE);
    }

    // 수치를 하나도 입력하지 않은 기록을 "주의 항목 없음"으로 안심시키지 않습니다.
    @Test
    void checkupWithoutValuesIsNotReportedAsNoRisk() {
        HealthCheckup empty = new HealthCheckup();
        empty.setCheckupDate(LocalDate.of(2026, 11, 1));

        HealthCheckupAnalysisDTO analysis = service.analyze(empty);

        assertThat(analysis.getSummary()).isEqualTo(HealthCheckupAnalysisService.NO_VALUES_SUMMARY);
        assertThat(analysis.getSummary()).doesNotContain("크지 않습니다");
    }
}
