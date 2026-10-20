package com.salus.healthytable.service.allergen;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 미해결 term을 삭제하지 않는다. 후보 ID는 진단용이며 resolved profile entry가 아니다. */
public record UnresolvedProfileAllergen(
        NormalizedProfileAllergenTerm input,
        ProfileUnresolvedReason reason,
        Set<String> candidateAllergenIds) {
    public UnresolvedProfileAllergen {
        Objects.requireNonNull(input);
        Objects.requireNonNull(reason);
        candidateAllergenIds = Collections.unmodifiableSet(new TreeSet<>(candidateAllergenIds));
        if (candidateAllergenIds.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Candidate ID must not be blank");
        }
        boolean valid = switch (reason) {
            case REGISTRY_MISS -> candidateAllergenIds.isEmpty();
            case AMBIGUOUS -> candidateAllergenIds.size() > 1;
            case NO_PROFILE_ELIGIBLE_ALIAS -> !candidateAllergenIds.isEmpty();
        };
        if (!valid) throw new IllegalArgumentException("Candidate IDs contradict unresolved reason: " + reason);
    }
}
