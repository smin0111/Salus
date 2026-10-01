package com.salus.healthytable.service.allergen;

import java.util.List;
import java.util.Objects;

/** 괄호의 구조적 자식이다. 자식이 실제 하위원료인지 설명인지 단정하지 않는다. */
public record IngredientNode(String rawText, String name, String normalizedName, List<IngredientNode> children) {
    public IngredientNode {
        Objects.requireNonNull(rawText);
        Objects.requireNonNull(name);
        Objects.requireNonNull(normalizedName);
        children = List.copyOf(children);
    }

    /** 구조 경로의 normalizedName은 유지하고 Registry 조회용 의미 명칭을 별도로 제공한다. */
    public IngredientSemanticName semanticName() {
        return IngredientNameNormalizer.normalize(name);
    }
}
