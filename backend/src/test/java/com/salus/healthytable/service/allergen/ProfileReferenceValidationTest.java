package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.salus.healthytable.service.allergen.ProfileReferenceValidation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileReferenceValidationTest {
    private final Case milk = ProfileReferenceTest.DATASET.cases().get(0);
    private final Expected miss = new Expected("UNRESOLVED", null, null, null, null, "REGISTRY_MISS");

    private List<String> errors(Case... cases) {
        return validate(new Dataset("PROFILE_RESOLUTION_CONTRACT", "Synthetic test", List.of(cases)), ProfileAllergenResolverTest.REGISTRY);
    }

    @Test
    void rejectsMissingDatasetMetadataAndEmptyCases() {
        assertThat(validate(null, ProfileAllergenResolverTest.REGISTRY)).isNotEmpty();
        assertThat(validate(new Dataset("WEB_VERIFIED_STRICT", "Synthetic", List.of(milk)), ProfileAllergenResolverTest.REGISTRY)).isNotEmpty();
        assertThat(validate(new Dataset("PROFILE_RESOLUTION_CONTRACT", "", List.of(milk)), ProfileAllergenResolverTest.REGISTRY)).isNotEmpty();
        assertThat(errors()).isNotEmpty();
    }

    @Test
    void rejectsDuplicateIdsAndDuplicateTermSourceButAllowsCrossSourceProvenance() {
        assertThat(errors(milk, milk)).anyMatch(error -> error.contains("duplicate ID"));
        assertThat(errors(milk, new Case("P999", milk.term(), milk.source(), milk.expected())))
                .anyMatch(error -> error.contains("duplicate term/source"));
        assertThat(errors(milk, new Case("P999", milk.term(), "CHAT_MESSAGE", milk.expected()))).isEmpty();
    }

    @Test
    void rejectsBlankTermsInvalidSourceAndMissingExpectation() {
        assertThat(errors(new Case("P999", " ", "STORED_HEALTH_PROFILE", miss))).anyMatch(e -> e.contains("blank term"));
        assertThat(errors(new Case("P999", "우유", "AGENT_CONTEXT", milk.expected()))).anyMatch(e -> e.contains("invalid source"));
        assertThat(errors(new Case("P999", "우유", null, milk.expected()))).anyMatch(e -> e.contains("invalid source"));
        assertThat(errors(new Case("P999", "우유", milk.source(), null))).anyMatch(e -> e.contains("missing expectation"));
    }

    @Test
    void rejectsUnknownIdsAndIncompleteResolvedExpectations() {
        assertThat(errors(new Case("P999", "우유", milk.source(), new Expected("RESOLVED", "FAKE", "EXACT_ALIAS", "우유", "DIRECT_NAME", null))))
                .anyMatch(e -> e.contains("unknown allergen ID"));
        assertThat(errors(new Case("P999", "우유", milk.source(), new Expected("RESOLVED", "MILK", null, "우유", "DIRECT_NAME", null))))
                .anyMatch(e -> e.contains("resolved expectation"));
        assertThat(errors(new Case("P999", "우유", milk.source(), new Expected("RESOLVED", "MILK", "EXACT_ALIAS", "버터", "DIRECT_NAME", null))))
                .anyMatch(e -> e.contains("not exact"));
        assertThat(errors(new Case("P999", "우유", milk.source(), new Expected("RESOLVED", "MILK", "EXACT_ALIAS", "우유", "DERIVED_FROM", null))))
                .anyMatch(e -> e.contains("resolved expectation"));
    }

    @Test
    void rejectsContradictoryUnresolvedExpectationsAndInvalidState() {
        assertThat(errors(new Case("P999", "키위", milk.source(), new Expected("UNRESOLVED", "MILK", null, null, null, "REGISTRY_MISS"))))
                .anyMatch(e -> e.contains("unresolved expectation"));
        assertThat(errors(new Case("P999", "키위", milk.source(), new Expected("UNRESOLVED", null, null, null, null, null))))
                .anyMatch(e -> e.contains("unresolved expectation"));
        assertThat(errors(new Case("P999", "키위", milk.source(), new Expected("UNRESOLVED", null, null, null, null, "SAFE"))))
                .anyMatch(e -> e.contains("unresolved expectation"));
        assertThat(errors(new Case("P999", "키위", milk.source(), new Expected("SAFE", null, null, null, null, null))))
                .anyMatch(e -> e.contains("invalid expected state"));
    }

    @Test
    void comparisonDetectsWrongIdentityWrongSourceAndDroppedInput() {
        var actual = evaluate(milk, ProfileAllergenResolverTest.RESOLVER);
        var wrongExpectation = new Case(milk.id(), milk.term(), milk.source(),
                new Expected("RESOLVED", "SOY", "EXACT_ALIAS", "우유", "DIRECT_NAME", null));
        assertThat(compare(wrongExpectation, actual.result()).exact()).isFalse();
        assertThat(compare(new Case(milk.id(), milk.term(), "CHAT_MESSAGE", milk.expected()), actual.result()).exact()).isFalse();
        var dropped = compare(milk, new CompleteProfileResolution(List.of()));
        assertThat(dropped.exact()).isFalse();
        assertThat(report(List.of(dropped))).contains("Total terms: 1", "Resolved: 0", "Exact cases: 0 / 1", "P001 FAIL");
    }

    @Test
    void comparisonDetectsWrongUnresolvedReasonAndResolverFailuresStayInDenominator() {
        var reference = new Case("P999", "키위", milk.source(), miss);
        var result = evaluate(reference, ProfileAllergenResolverTest.RESOLVER).result();
        assertThat(compare(new Case("P999", "키위", milk.source(),
                new Expected("UNRESOLVED", null, null, null, null, "AMBIGUOUS")), result).exact()).isFalse();
        AllergenRegistry registry = mock(AllergenRegistry.class);
        when(registry.findExactAliases("키위")).thenThrow(new IllegalStateException("lookup failed"));
        var failure = evaluate(reference, new ProfileAllergenResolver(registry));
        assertThat(failure.errors()).singleElement().asString().contains("IllegalStateException");
        assertThat(report(List.of(failure))).contains("Total terms: 1", "Resolved: 0", "Unresolved: 0", "Exact cases: 0 / 1", "FAIL ERROR");
        assertThat(report(List.of())).contains("Resolution rate: N/A");
    }

    @Test
    void syntheticAmbiguityIsReportedWithoutChangingProductionYamlOrProductReference() {
        AllergenRegistry registry = mock(AllergenRegistry.class);
        when(registry.findExactAliases("합성명")).thenReturn(List.of(
                new AllergenAlias("합성명", AllergenAlias.Relation.DIRECT_NAME, "MILK"),
                new AllergenAlias("합성명", AllergenAlias.Relation.DIRECT_NAME, "SOY")));
        var reference = new Case("SYN-P001", "합성명", "CHAT_REQUEST_PROFILE",
                new Expected("UNRESOLVED", null, null, null, null, "AMBIGUOUS"));
        var evaluation = evaluate(reference, new ProfileAllergenResolver(registry));
        assertThat(evaluation.exact()).isTrue();
        assertThat(report(List.of(evaluation))).contains("Resolved: 0", "Unresolved: 1", "AMBIGUOUS=1", "Exact cases: 1 / 1");
    }
}
