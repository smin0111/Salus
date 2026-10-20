package com.salus.healthytable.service.allergen;

import java.util.Objects;

/** 이미 upstream에서 정규화한 값이라는 경계 표시. 문장 parsing/조사 제거를 수행하지 않는다. */
public record NormalizedProfileAllergenTerm(String value, ProfileTermSource source) {
    public NormalizedProfileAllergenTerm {
        Objects.requireNonNull(value);
        Objects.requireNonNull(source);
        if (value.isBlank()) throw new IllegalArgumentException("Normalized profile term must not be blank");
    }
}
