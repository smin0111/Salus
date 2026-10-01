package com.salus.healthytable.service.allergen;

import java.util.List;
import java.util.Objects;

/** 라벨이 괄호 안에 직접 명시한 항목만 보존하며, 분류 체계로 자식을 추론하지 않는다. */
public record NormalizedAllergenRef(String allergen, List<String> explicitChildren) {

    // 예) allergen="갑각류", explicitChildren=["새우", "게"] — 라벨에 괄호로 적힌 자식만 담습니다.
    public NormalizedAllergenRef {
        Objects.requireNonNull(allergen);
        explicitChildren = List.copyOf(explicitChildren);
    }
}
