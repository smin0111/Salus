package com.salus.healthytable.service.allergen;

import java.util.Objects;

/** 라벨 원재료란만 입력한다. 작성자 메모나 버전 충돌 자료의 선택은 호출자 책임이다. */
public record IngredientInput(String rawText, Availability availability) {
    public IngredientInput {
        Objects.requireNonNull(availability);
        if (availability == Availability.READABLE && (rawText == null || rawText.isBlank())) {
            throw new IngredientParseException(IngredientParseException.Kind.EMPTY_NODE, "읽을 원재료 원문이 없습니다.");
        }
        if (availability == Availability.NOT_FOUND && rawText != null) {
            throw new IllegalArgumentException("원재료 표시 없음에는 원문을 전달할 수 없습니다.");
        }
    }
    public static IngredientInput readable(String raw) { return new IngredientInput(raw, Availability.READABLE); }
    public static IngredientInput notFound() { return new IngredientInput(null, Availability.NOT_FOUND); }
    public static IngredientInput unreadable(String raw) { return new IngredientInput(raw, Availability.UNREADABLE); }
    public enum Availability { READABLE, NOT_FOUND, UNREADABLE }
}
