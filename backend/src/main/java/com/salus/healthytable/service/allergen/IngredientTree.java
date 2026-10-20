package com.salus.healthytable.service.allergen;

import java.util.List;
import java.util.Objects;

/** 문법 결과와 관찰 상태만 담는다. 비어 있는 Tree는 안전 판정이 아니다. */
public record IngredientTree(State state, String rawText, String normalizedText, List<IngredientNode> roots) {
    public IngredientTree {
        Objects.requireNonNull(state);
        roots = List.copyOf(roots);
    }
    public enum State { PARSED, NOT_FOUND, UNREADABLE }
}
