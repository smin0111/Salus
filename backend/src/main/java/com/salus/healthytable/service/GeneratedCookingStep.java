package com.salus.healthytable.service;

import java.util.List;
import java.util.Locale;

public record GeneratedCookingStep(
        Integer order,
        String instruction,
        String heatLevel,
        Integer minutes,
        String completionCue,
        String recoveryTip,
        List<String> ingredientNames
) {
    public GeneratedCookingStep(
            Integer order,
            String instruction,
            String heatLevel,
            Integer minutes,
            String completionCue,
            String recoveryTip) {
        this(order, instruction, heatLevel, minutes, completionCue, recoveryTip, List.of());
    }

    public String normalizedHeatLevel() {
        return normalizeHeatLevel(heatLevel);
    }

    public boolean hasUnknownHeatLevel() {
        return heatLevel != null && !heatLevel.isBlank() && normalizeHeatLevel(heatLevel).isBlank();
    }

    public static String normalizeHeatLevel(String rawHeatLevel) {
        if (rawHeatLevel == null || rawHeatLevel.isBlank()) {
            return "";
        }
        String normalized = rawHeatLevel.trim().replaceAll("\\s+", "-").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "강불", "센불", "high" -> "강불";
            case "중강불", "medium-high", "med-high" -> "중강불";
            case "중불", "medium", "med" -> "중불";
            case "중약불", "medium-low", "med-low" -> "중약불";
            case "약불", "low" -> "약불";
            case "무가열", "none", "no-heat", "noheat" -> "무가열";
            case "해당-없음", "해당없음", "n/a", "na", "not-applicable" -> "해당 없음";
            default -> "";
        };
    }
}
