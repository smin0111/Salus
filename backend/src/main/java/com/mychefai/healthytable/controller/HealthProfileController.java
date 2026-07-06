package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.HealthProfile;
import com.mychefai.healthytable.dto.HealthProfileDto;
import com.mychefai.healthytable.repository.HealthProfileRepository;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;

@RestController
@RequestMapping("/api/users/me/health-profile")
@RequiredArgsConstructor
public class HealthProfileController {

    private static final int MAX_PROFILE_ITEMS = 30;
    private static final int MAX_PROFILE_ITEM_LENGTH = 80;

    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final HealthProfileRepository healthProfileRepository;

    @GetMapping
    public HealthProfileDto getMyHealthProfile() {
        Long userId = authenticatedUserProvider.requireUserId();
        return healthProfileRepository.findByUserId(userId)
                .map(this::toDto)
                .orElseGet(this::emptyDto);
    }

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

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
