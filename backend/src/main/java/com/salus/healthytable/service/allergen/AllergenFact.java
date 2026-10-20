package com.salus.healthytable.service.allergen;

import java.util.List;
import java.util.Objects;

import static com.salus.healthytable.service.allergen.AllergenEvidence.EvidenceSource;

/** 한 제품에서 같은 알레르겐 ID를 뒷받침하는 관찰 근거 묶음. 사용자별 안전 판정이 아니다. */
public record AllergenFact(
        String allergen,
        PresenceStatus status,
        List<AllergenEvidence> evidences,
        EvidenceSource strongestPresenceSource) {
    public AllergenFact {
        Objects.requireNonNull(allergen);
        Objects.requireNonNull(status);
        Objects.requireNonNull(strongestPresenceSource);
        evidences = List.copyOf(evidences);
        if (allergen.isBlank() || evidences.isEmpty()) {
            throw new IllegalArgumentException("Fact에는 알레르겐 ID와 positive Evidence가 필요합니다.");
        }
        if (evidences.stream().anyMatch(e -> !allergen.equals(e.normalizedAllergen().allergen()))) {
            throw new IllegalArgumentException("Fact에 다른 알레르겐 ID의 Evidence를 포함할 수 없습니다.");
        }
        if (evidences.stream().noneMatch(e -> e.source() == strongestPresenceSource)) {
            throw new IllegalArgumentException("Strongest source는 실제 Evidence에 존재해야 합니다.");
        }
    }
}
