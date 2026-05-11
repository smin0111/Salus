package com.mychefai.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthCheckupAnalysisDTO {
    private Long checkupId;
    private String checkupDate;
    private String summary;

    @Builder.Default
    private List<String> risks = new ArrayList<>();

    @Builder.Default
    private List<String> recommendationPolicies = new ArrayList<>();

    @Builder.Default
    private List<String> foodGuides = new ArrayList<>();
}
