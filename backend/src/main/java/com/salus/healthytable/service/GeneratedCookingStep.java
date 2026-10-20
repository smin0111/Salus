package com.salus.healthytable.service;

import java.util.List;
import java.util.Locale;

/**
 * LLM이 생성한 레시피의 조리 단계 하나입니다.
 *
 * record는 필드, 생성자, getter, equals/hashCode를 자동으로 만들어 주는 불변 데이터 클래스입니다.
 * 필드: 순서, 설명, 불 세기, 온도(℃), 소요 시간(분), 완료 판단 기준, 실패 시 복구 팁, 이 단계에서 쓰는 재료 이름들
 */
public record GeneratedCookingStep(
        Integer order,
        String instruction,
        String heatLevel,
        Integer temperatureC,
        Integer minutes,
        String completionCue,
        String recoveryTip,
        List<String> ingredientNames
) {
    // 온도(temperatureC) 없이 만드는 보조 생성자입니다(기존 코드/테스트 호환용).
    public GeneratedCookingStep(
            Integer order,
            String instruction,
            String heatLevel,
            Integer minutes,
            String completionCue,
            String recoveryTip,
            List<String> ingredientNames) {
        this(order, instruction, heatLevel, null, minutes, completionCue, recoveryTip, ingredientNames);
    }

    // 온도와 재료 이름 목록 없이 만드는 보조 생성자입니다.
    public GeneratedCookingStep(
            Integer order,
            String instruction,
            String heatLevel,
            Integer minutes,
            String completionCue,
            String recoveryTip) {
        this(order, instruction, heatLevel, null, minutes, completionCue, recoveryTip, List.of());
    }

    // 불 세기를 표준 한국어 표기(강불/중불/약불 등)로 바꾼 값을 반환합니다.
    public String normalizedHeatLevel() {
        return normalizeHeatLevel(heatLevel);
    }

    // 불 세기 값이 입력되어 있지만 알려진 표기로 바꿀 수 없으면 true입니다(검증기에서 오류로 사용).
    public boolean hasUnknownHeatLevel() {
        return heatLevel != null && !heatLevel.isBlank() && normalizeHeatLevel(heatLevel).isBlank();
    }

    /**
     * LLM이 영어나 다른 표기("medium-high", "센불")로 적어도 같은 의미로 비교할 수 있게 표준 표기로 통일합니다.
     * 알 수 없는 값은 빈 문자열을 반환합니다(임의로 추측하지 않음).
     */
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
