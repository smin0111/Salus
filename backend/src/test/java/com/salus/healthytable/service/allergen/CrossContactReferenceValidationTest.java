package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static com.salus.healthytable.service.allergen.CrossContactReferenceValidation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossContactReferenceValidationTest {
    @ParameterizedTest
    @ValueSource(strings = {"state", "missing", "extra", "children", "unsupported", "source", "type",
            "confidence", "raw", "path", "matched"})
    void strictEvaluationRejectsEveryMaterialMismatch(String field) {
        Evaluation baseline = CrossContactReferenceTest.EVALUATIONS.get(6);
        CrossContactParseResult original = baseline.result();
        List<AllergenEvidence> evidence = new ArrayList<>(original.evidence());
        AllergenEvidence old = evidence.get(evidence.size() - 1);
        AllergenEvidence altered = new AllergenEvidence(
                field.equals("source") ? AllergenEvidence.EvidenceSource.DECLARATION : old.source(),
                field.equals("raw") ? "changed" : old.rawText(),
                field.equals("matched") ? "changed" : old.matchedText(),
                field.equals("children") ? new NormalizedAllergenRef("SHELLFISH", List.of("OYSTER")) : old.normalizedAllergen(),
                field.equals("type") ? AllergenEvidence.EvidenceType.DIRECT_DECLARATION : old.evidenceType(),
                field.equals("confidence") ? AllergenEvidence.MatchConfidence.POSSIBLE : old.confidence(),
                field.equals("path") ? List.of("ingredient") : old.path());
        evidence.set(evidence.size() - 1, altered);
        if (field.equals("missing")) evidence.remove(0);
        if (field.equals("extra")) evidence.add(old);
        CrossContactParseResult wrong = new CrossContactParseResult(
                field.equals("state") ? CrossContactState.CROSS_CONTACT_NOT_FOUND : original.state(),
                original.rawText(), original.normalizedText(), evidence,
                field.equals("unsupported") ? List.of("unknown") : original.unparsedTokens());
        assertThat(compare(baseline.reference(), wrong).exact()).isFalse();
    }

    @Test
    void unexpectedOrMissingUnsupportedTokenIsNotAHit() {
        Evaluation baseline = CrossContactReferenceTest.EVALUATIONS.get(4);
        CrossContactParseResult old = baseline.result();
        assertThat(compare(baseline.reference(), new CrossContactParseResult(old.state(), old.rawText(),
                old.normalizedText(), old.evidence(), List.of("알류"))).exact()).isFalse();
        // 실제 Reference의 gap이 해결되어도 unknown 누락 검증은 별도 합성 입력으로 유지한다.
        CrossContact expected = baseline.reference().crossContact();
        ReferenceCase synthetic = new ReferenceCase("SYNTHETIC-UNKNOWN", baseline.reference().gtin(),
                baseline.reference().productName(), baseline.reference().source(),
                new CrossContact(expected.raw(), expected.note(), expected.availability(), expected.normalized(),
                        expected.expectedState(), expected.expectedAllergens(), expected.expectedExplicitChildren(),
                        List.of("미확인"), expected.expectedMatchedTexts()));
        assertThat(compare(synthetic, old).exact()).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "우유) 혼입 가능|UNBALANCED_PARENTHESES", "조개류(굴 혼입 가능|UNBALANCED_PARENTHESES",
            "우유,,밀 혼입 가능|EMPTY_TOKEN", "우유 함유|UNSUPPORTED_LABEL_FORMAT"})
    void parseErrorsRemainInAccuracyDenominator(String raw, ParseError category) {
        ReferenceCase reference = withInput(CrossContactReferenceTest.DATASET.cases().get(2),
                raw, CrossContactInput.Availability.READABLE, CrossContactState.CROSS_CONTACT_PRESENT);
        Evaluation evaluation = evaluate(reference, CrossContactReferenceTest.PARSER);
        assertThat(evaluation.error()).isEqualTo(category);
        assertThat(evaluation.evaluable()).isTrue();
        assertThat(evaluation.exact()).isFalse();
        assertThat(evaluation.covered()).isFalse();
    }

    @Test
    void unexpectedExceptionIsReportedAndDoesNotBecomeNotFound() {
        AllergenCrossContactParser broken = mock(AllergenCrossContactParser.class);
        when(broken.parse(any(CrossContactInput.class))).thenThrow(new IllegalStateException("broken registry"));
        Evaluation evaluation = evaluate(CrossContactReferenceTest.DATASET.cases().get(2), broken);
        assertThat(evaluation.error()).isEqualTo(ParseError.UNEXPECTED_EXCEPTION);
        assertThat(evaluation.exceptionDetail()).contains("broken registry");
        assertThat(evaluation.evaluable()).isTrue();
        assertThat(evaluation.exact()).isFalse();
        assertThat(evaluation.result()).isNull();
    }

    @Test
    void notFoundAndUnreadableAreExcludedEvenWhenStatePassthroughPasses() {
        ReferenceCase original = CrossContactReferenceTest.DATASET.cases().get(0);
        ReferenceCase unreadable = withInput(original, null, CrossContactInput.Availability.UNREADABLE,
                CrossContactState.UNREADABLE);
        for (ReferenceCase reference : List.of(original, unreadable)) {
            Evaluation evaluation = evaluate(reference, CrossContactReferenceTest.PARSER);
            assertThat(evaluation.exact()).isTrue();
            assertThat(evaluation.evaluable()).isFalse();
            assertThat(evaluation.covered()).isFalse();
        }
    }

    @Test
    void mixedReportUsesActualOutcomesAndRecordsAllErrorCategories() {
        List<Evaluation> evaluations = new ArrayList<>(CrossContactReferenceTest.EVALUATIONS);
        for (int index : List.of(2, 4)) {
            ReferenceCase bad = withInput(evaluations.get(index).reference(), "우유 함유",
                    CrossContactInput.Availability.READABLE, CrossContactState.CROSS_CONTACT_PRESENT);
            evaluations.set(index, evaluate(bad, CrossContactReferenceTest.PARSER));
        }
        String report = report(CrossContactReferenceTest.DATASET, evaluations,
                CrossContactReferenceTest.DECLARATION_EVALUATIONS);
        assertThat(report).contains("Accuracy (READABLE/PRESENT only): 8 / 10 (80.0%)",
                "Cross-contact Coverage: 8 / 20 (40.0%)", "UNSUPPORTED_LABEL_FORMAT=2",
                "UNBALANCED_PARENTHESES=0", "EMPTY_TOKEN=0", "UNEXPECTED_EXCEPTION=0",
                "Unsupported Token Count: 0; unique: 0", "REFERENCE_VERSION_CONFLICT");
    }

    @Test
    void unionRequiresMatchingIdsInsteadOfSilentlyDroppingProducts() {
        assertThatThrownBy(() -> groups(CrossContactReferenceTest.EVALUATIONS.subList(0, 19),
                CrossContactReferenceTest.DECLARATION_EVALUATIONS)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ReferenceCase withInput(ReferenceCase reference, String raw,
            CrossContactInput.Availability availability, CrossContactState state) {
        CrossContact old = reference.crossContact();
        return new ReferenceCase(reference.id(), reference.gtin(), reference.productName(), reference.source(),
                new CrossContact(raw, old.note(), availability, raw, state, old.expectedAllergens(),
                        old.expectedExplicitChildren(), old.expectedUnsupportedTokens(), old.expectedMatchedTexts()));
    }
}
