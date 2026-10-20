package com.salus.healthytable.service.allergen;

import java.util.List;
import java.util.Objects;

/** 한 input occurrence의 identity와 이를 지지한 exact DIRECT_NAME metadata. 안전 판정이 아니다. */
public record ResolvedProfileAllergen(
        NormalizedProfileAllergenTerm input,
        String allergenId,
        ProfileResolutionType resolutionType,
        List<AllergenAlias> matchedAliases) {
    public ResolvedProfileAllergen {
        Objects.requireNonNull(input);
        Objects.requireNonNull(allergenId);
        Objects.requireNonNull(resolutionType);
        matchedAliases = List.copyOf(matchedAliases);
        if (allergenId.isBlank() || matchedAliases.isEmpty()) {
            throw new IllegalArgumentException("Resolved identity requires an ID and exact alias provenance");
        }
        for (AllergenAlias alias : matchedAliases) {
            if (!alias.allergen().equals(allergenId) || alias.relation() != AllergenAlias.Relation.DIRECT_NAME
                    || !alias.text().equalsIgnoreCase(input.value().strip())) {
                throw new IllegalArgumentException("Resolved provenance must match the input and the same direct identity");
            }
        }
    }
}
