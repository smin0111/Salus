package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
