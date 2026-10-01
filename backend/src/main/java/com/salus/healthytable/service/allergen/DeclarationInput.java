package com.salus.healthytable.service.allergen;

import java.util.Objects;

/** 호출자가 확인한 공식 함유 표시란만 입력한다. 작성자 메모는 전달하지 않는다. */
public record DeclarationInput(String rawText, Availability availability) {

    // 상태와 원문이 서로 모순되지 않는지 검사합니다(읽을 수 있다면 원문 필수, 표시 없음이면 원문 없음).
    public DeclarationInput {
        Objects.requireNonNull(availability);
        if (availability == Availability.READABLE && (rawText == null || rawText.isBlank())) {
            throw new IllegalArgumentException("읽을 수 있는 함유 표시 원문이 필요합니다.");
        }
        if (availability == Availability.NOT_FOUND && rawText != null) {
            throw new IllegalArgumentException("표시 없음에는 함유 표시 원문을 전달할 수 없습니다.");
        }
    }

    // 공식 함유 표시 원문을 읽을 수 있는 경우
    public static DeclarationInput readable(String rawText) {
        return new DeclarationInput(rawText, Availability.READABLE);
    }

    // 라벨에서 공식 함유 표시란을 찾지 못한 경우 (알레르겐 없음이라는 뜻이 아님)
    public static DeclarationInput notFound() {
        return new DeclarationInput(null, Availability.NOT_FOUND);
    }

    /** 일부 읽힌 원문이 있으면 보존하되, Evidence 생성에는 사용하지 않는다. */
    public static DeclarationInput unreadable(String rawText) {
        return new DeclarationInput(rawText, Availability.UNREADABLE);
    }

    // 입력 원문의 확보 상태: 읽을 수 있음 / 표시 없음 / 읽을 수 없음
    public enum Availability { READABLE, NOT_FOUND, UNREADABLE }
}
