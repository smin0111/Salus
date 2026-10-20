package com.salus.healthytable.service.allergen;

import java.util.Objects;

/** 호출자가 식별한 교차접촉 표시란. 작성자 메모나 전체 라벨을 전달하지 않는다. */
public record CrossContactInput(String rawText, Availability availability) {
    // 상태와 원문이 서로 모순되지 않는지 검사합니다(읽을 수 있다면 원문 필수, 표시 없음이면 원문 없음).
    public CrossContactInput {
        Objects.requireNonNull(availability);
        if (availability == Availability.READABLE && (rawText == null || rawText.isBlank())) {
            throw new IllegalArgumentException("읽을 수 있는 교차접촉 표시 원문이 필요합니다.");
        }
        if (availability == Availability.NOT_FOUND && rawText != null) {
            throw new IllegalArgumentException("표시 없음에는 교차접촉 표시 원문을 전달할 수 없습니다.");
        }
    }

    // 교차접촉 표시 원문을 읽을 수 있는 경우
    public static CrossContactInput readable(String rawText) {
        return new CrossContactInput(rawText, Availability.READABLE);
    }

    // 라벨에서 교차접촉 표시란을 찾지 못한 경우 (위험 없음이라는 뜻이 아님)
    public static CrossContactInput notFound() {
        return new CrossContactInput(null, Availability.NOT_FOUND);
    }

    // 표시란은 있지만 흐림/잘림 등으로 읽을 수 없는 경우
    public static CrossContactInput unreadable(String rawText) {
        return new CrossContactInput(rawText, Availability.UNREADABLE);
    }

    // 입력 원문의 확보 상태: 읽을 수 있음 / 표시 없음 / 읽을 수 없음
    public enum Availability { READABLE, NOT_FOUND, UNREADABLE }
}
