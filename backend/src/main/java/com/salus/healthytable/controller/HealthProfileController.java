package com.salus.healthytable.controller;

import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.dto.HealthProfileDto;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 내 건강 프로필 API(/api/users/me/health-profile)입니다.
 * 알레르기, 기저질환, 식단 제한, 복용 약, 건강 목표를 조회하고 저장합니다.
 */
@RestController
@RequestMapping("/api/users/me/health-profile")
@RequiredArgsConstructor
public class HealthProfileController {

    private static final int MAX_PROFILE_ITEMS = 30;
    private static final int MAX_PROFILE_ITEM_LENGTH = 80;

    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final HealthProfileRepository healthProfileRepository;

    /**
     * 내 건강 프로필을 조회합니다. 아직 저장한 적이 없으면 모든 항목이 빈 목록인 응답을 반환합니다.
     */
    @GetMapping
    public HealthProfileDto getMyHealthProfile() {
        Long userId = authenticatedUserProvider.requireUserId();
        return healthProfileRepository.findByUserId(userId)
                .map(this::toDto)
                .orElseGet(this::emptyDto);
    }

    /**
     * 내 건강 프로필을 저장합니다. 기존 프로필이 있으면 덮어쓰고, 없으면 새로 만듭니다(upsert).
     */
    @PutMapping
    public HealthProfileDto saveMyHealthProfile(@RequestBody HealthProfileDto request) {
        Long userId = authenticatedUserProvider.requireUserId();
        HealthProfile profile = healthProfileRepository.findByUserId(userId)
                .orElseGet(() -> {
                    HealthProfile created = new HealthProfile();
                    created.setUserId(userId);
                    return created;
                });

        profile.setAllergies(cleanList(request != null ? request.getAllergies() : null, "알레르기"));
        profile.setChronicConditions(cleanList(request != null ? request.getChronicConditions() : null, "기저질환"));
        profile.setDietaryRestrictions(cleanList(request != null ? request.getDietaryRestrictions() : null, "식단 제한"));
        profile.setMedications(cleanList(request != null ? request.getMedications() : null, "복용 약"));
        profile.setGoals(cleanList(request != null ? request.getGoals() : null, "건강 목표"));

        return toDto(healthProfileRepository.save(profile));
    }

    private HealthProfileDto toDto(HealthProfile profile) {
        return new HealthProfileDto(
                safeList(profile.getAllergies()),
                safeList(profile.getChronicConditions()),
                safeList(profile.getDietaryRestrictions()),
                safeList(profile.getMedications()),
                safeList(profile.getGoals()));
    }

    private HealthProfileDto emptyDto() {
        return new HealthProfileDto(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    // 빈 값 제거, 공백 정리, 중복 제거 후 항목당 80자 / 최대 30개 제한을 확인합니다.
    private List<String> cleanList(List<String> values, String label) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.replaceAll("\\s+", " ").trim();
            if (normalized.length() > MAX_PROFILE_ITEM_LENGTH) {
                throw new IllegalArgumentException(label + " 항목은 80자 이하로 입력해 주세요.");
            }
            cleaned.add(normalized);
            if (cleaned.size() > MAX_PROFILE_ITEMS) {
                throw new IllegalArgumentException(label + "는 30개 이하로 입력해 주세요.");
            }
        }
        return List.copyOf(cleaned);
    }

    // DB 값이 null이어도 클라이언트에는 항상 빈 배열([])을 내려 줍니다.
    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
