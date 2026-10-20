package com.salus.healthytable.service.allergen;

import java.time.LocalDate;
import java.util.Objects;

public record DeclarationCoverageAssessment(
        String allergen, String jurisdiction, LocalDate applicableDate, RegulatoryDateBasis dateBasis,
        String ruleSetId, RegulatoryLifecycle ruleLifecycle, String regulatoryParent,
        DeclarationCoverageStatus status, DeclarationCoverageReason reason) {
    public DeclarationCoverageAssessment {
        Objects.requireNonNull(allergen);
        Objects.requireNonNull(jurisdiction);
        Objects.requireNonNull(dateBasis);
        Objects.requireNonNull(status);
        Objects.requireNonNull(reason);
    }
}
