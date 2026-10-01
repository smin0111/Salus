package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.salus.healthytable.service.allergen.AllergenEvidence.*;
import static com.salus.healthytable.service.allergen.ResolverReferenceValidation.*;
import static org.assertj.core.api.Assertions.*;

class ResolverReferenceValidationTest {
    @Test
    void missingDuplicateStatusAndSourceCompositionErrorsAreAllAccuracyFailures() {
        var baseline = ResolverReferenceTest.EVALUATIONS.get(0);
        var fact = baseline.facts().get(0);
        var wrongStatus = new AllergenFact(fact.allergen(), PresenceStatus.POSSIBLE_PRESENT,
                fact.evidences(), fact.strongestPresenceSource());
        var wrongSources = new AllergenFact(fact.allergen(), fact.status(), List.of(fact.evidences().get(0)),
                fact.strongestPresenceSource());
        for (var actual : List.of(List.<AllergenFact>of(), List.of(fact, fact), List.of(wrongStatus), List.of(wrongSources))) {
            var result = compare(baseline.reference(), baseline.input(), actual);
            assertThat(result.factExact()).isFalse();
            assertThat(result.factMismatches()).isNotEmpty();
            assertThat(result.evaluable()).isTrue();
        }
    }

    @Test
    void strongestAndFullEvidencePreservationAreSeparateStrictChecks() {
        var baseline = ResolverReferenceTest.EVALUATIONS.get(0);
        var fact = baseline.facts().get(0);
        var wrongStrongest = new AllergenFact(fact.allergen(), fact.status(), fact.evidences(), EvidenceSource.INGREDIENT);
        var result = compare(baseline.reference(), baseline.input(), List.of(wrongStrongest));
        assertThat(result.factExact()).isTrue(); // accuracy 계약은 ID/status/source composition
        assertThat(result.exact()).isFalse(); // strongest 및 원문 보존은 추가 strict 계약
        assertThat(result.detailMismatches()).isNotEmpty();
        var changed = new ArrayList<>(fact.evidences());
        var e = changed.get(0);
        changed.set(0, new AllergenEvidence(e.source(), "mutated", e.matchedText(), e.normalizedAllergen(),
                e.evidenceType(), e.confidence(), e.path()));
        var altered = new AllergenFact(fact.allergen(), fact.status(), changed, fact.strongestPresenceSource());
        assertThat(compare(baseline.reference(), baseline.input(), List.of(altered)).detailMismatches())
                .contains("Evidence loss, mutation or duplication");
    }

    @Test
    void resolverAndUpstreamFailuresStayInNineteenProductDenominator() {
        var evaluations = new ArrayList<>(ResolverReferenceTest.EVALUATIONS);
        var baseline = evaluations.get(0);
        var invalid = new AllergenEvidence(EvidenceSource.CROSS_CONTACT, "x", "x",
                new NormalizedAllergenRef("SOY", List.of()), EvidenceType.DIRECT_NAME,
                MatchConfidence.CERTAIN, List.of());
        var failed = evaluate(baseline.reference(), List.of(invalid), ResolverReferenceTest.RESOLVER);
        assertThat(failed.error()).contains("IllegalArgumentException");
        assertThat(failed.evaluable()).isTrue();
        evaluations.set(0, failed);
        assertThat(report(evaluations)).contains("Evaluable products: 19", "18 / 19 (94.7%)",
                "15 / 19 (78.9%)", "S001 FAIL", "S020 EXCLUDED: REFERENCE_VERSION_CONFLICT");
        evaluations.set(0, failed(baseline.reference(), "Upstream parser/reference validation failed"));
        assertThat(report(evaluations)).contains("18 / 19 (94.7%)", "S001 FAIL");
    }

    @Test
    void exclusionsCannotHideOtherProductsOrPretendToContainEvidence() {
        var reference = ResolverReferenceTest.DATASET.cases().get(0);
        var invalid = new Case(reference.id(), reference.gtin(), "REFERENCE_VERSION_CONFLICT", List.of());
        assertThatThrownBy(() -> evaluate(invalid, List.of(), ResolverReferenceTest.RESOLVER))
                .isInstanceOf(IllegalArgumentException.class);
        var conflict = ResolverReferenceTest.DATASET.cases().get(19);
        assertThatThrownBy(() -> evaluate(conflict, List.of(AllergenEvidenceResolverTest.declaration("SOY")),
                ResolverReferenceTest.RESOLVER)).isInstanceOf(IllegalArgumentException.class);
        assertThat(report(List.of())).contains("0 / 0 (N/A)", "min=N/A, max=N/A, average=N/A");
    }
}
