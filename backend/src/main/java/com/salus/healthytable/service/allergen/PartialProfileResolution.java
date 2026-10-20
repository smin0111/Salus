package com.salus.healthytable.service.allergen;

import java.util.List;

/** 하나 이상의 미해결 값과 이미 해결된 값을 함께 보존한다. */
public record PartialProfileResolution(
        List<ResolvedProfileAllergen> resolved,
        List<UnresolvedProfileAllergen> unresolved) implements ProfileResolution {
    public PartialProfileResolution {
        resolved = List.copyOf(resolved);
        unresolved = List.copyOf(unresolved);
        if (unresolved.isEmpty()) throw new IllegalArgumentException("Partial resolution requires unresolved input");
    }
}
