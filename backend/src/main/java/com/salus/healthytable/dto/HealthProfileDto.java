package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 건강 프로필 조회/수정에 사용하는 DTO입니다(알레르기, 만성질환, 식이 제한, 복용 약, 건강 목표).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HealthProfileDto {
    private List<String> allergies;
    private List<String> chronicConditions;
    private List<String> dietaryRestrictions;
    private List<String> medications;
    private List<String> goals;
}
