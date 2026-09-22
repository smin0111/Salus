package com.salus.healthytable.service.allergen;

import java.util.List;
import java.util.Objects;

/** 명칭의 의미 조회와 원문 주석을 분리한다. 함유/안전 판정 결과가 아니다. */
public record IngredientSemanticName(String raw, String normalizedName, List<String> annotations) {
    public IngredientSemanticName {
        Objects.requireNonNull(raw);
        Objects.requireNonNull(normalizedName);
        annotations = List.copyOf(annotations);
    }
}
