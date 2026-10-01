package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.salus.healthytable.service.allergen.IngredientReferenceValidation.Error;

import java.util.ArrayList;
import java.util.List;

import static com.salus.healthytable.service.allergen.IngredientReferenceValidation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngredientReferenceValidationTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "A[B|UNBALANCED_BRACKET", "A[B)|MISMATCHED_BRACKET",
            "A,,B|EMPTY_NODE", "A[B]extra|UNSUPPORTED_STRUCTURE"})
    void syntaxFailuresRemainInAccuracyDenominator(String raw, Error category) {
        Evaluation evaluation = evaluate(withRaw(IngredientReferenceTest.DATASET.cases().get(1), raw),
                IngredientReferenceTest.PARSER, IngredientReferenceTest.EXTRACTOR, IngredientReferenceTest.REGISTRY);
        assertThat(evaluation.error()).isEqualTo(category);
        assertThat(evaluation.evaluable()).isTrue();
        assertThat(evaluation.structuralExact()).isFalse();
        assertThat(evaluation.covered()).isFalse();
    }

    @Test
    void unexpectedExceptionsStayVisible() {
        IngredientTreeParser broken = mock(IngredientTreeParser.class);
        when(broken.parse(any(String.class))).thenThrow(new IllegalStateException("broken"));
        Evaluation e = evaluate(IngredientReferenceTest.DATASET.cases().get(1), broken,
                IngredientReferenceTest.EXTRACTOR, IngredientReferenceTest.REGISTRY);
        assertThat(e.error()).isEqualTo(Error.UNEXPECTED_EXCEPTION);
        assertThat(e.errorDetail()).contains("broken");
        assertThat(e.evaluable()).isTrue();
        assertThat(e.exact()).isFalse();
    }

    @Test
    void structureAndEvidenceFailuresAreNotConfused() {
        Evaluation baseline = IngredientReferenceTest.EVALUATIONS.get(1);
        Evaluation missingEvidence = compare(baseline.reference(), baseline.tree(), List.of(), IngredientReferenceTest.REGISTRY);
        assertThat(missingEvidence.structuralExact()).isTrue();
        assertThat(missingEvidence.evidenceMismatches()).isNotEmpty();
        IngredientTree wrong = new IngredientTree(IngredientTree.State.PARSED, baseline.tree().rawText(),
                baseline.tree().normalizedText(), List.of());
        Evaluation missingTree = compare(baseline.reference(), wrong, baseline.evidence(), IngredientReferenceTest.REGISTRY);
        assertThat(missingTree.structuralExact()).isFalse();
        assertThat(missingTree.evidenceMismatches()).isEmpty();
    }

    @Test
    void fullEvidenceContractAndDuplicatesAreChecked() {
        Evaluation baseline = IngredientReferenceTest.EVALUATIONS.get(1);
        List<AllergenEvidence> duplicate = new ArrayList<>(baseline.evidence());
        duplicate.add(baseline.evidence().get(0));
        assertThat(compare(baseline.reference(), baseline.tree(), duplicate,
                IngredientReferenceTest.REGISTRY).evidenceMismatches()).isNotEmpty();
        AllergenEvidence old = baseline.evidence().get(0);
        AllergenEvidence wrong = new AllergenEvidence(AllergenEvidence.EvidenceSource.DECLARATION, "changed",
                old.matchedText(), old.normalizedAllergen(), old.evidenceType(), old.confidence(), old.path());
        assertThat(compare(baseline.reference(), baseline.tree(), List.of(wrong),
                IngredientReferenceTest.REGISTRY).evidenceMismatches()).contains("Invalid ingredient evidence attributes");
    }

    @Test
    void generalUnmatchedIngredientsAreNotCountedAsUnsupportedAllergens() {
        Evaluation saltSugar = IngredientReferenceTest.EVALUATIONS.get(15);
        assertThat(saltSugar.tree().roots()).hasSize(9);
        assertThat(saltSugar.evidence()).isEmpty();
        assertThat(saltSugar.unsupported()).isEmpty();
        Evaluation reviewed = IngredientReferenceTest.EVALUATIONS.get(13);
        assertThat(reviewed.unsupported()).singleElement().satisfies(c -> {
            assertThat(c.token()).isEqualTo("젤라틴");
            assertThat(c.path()).containsExactly("젤라틴");
            assertThat(c.rawNode().strip()).isEqualTo("젤라틴");
        });
    }

    @Test
    void conflictIsExcludedButBadReadableInputIsNotDroppedFromReport() {
        List<Evaluation> evaluations = new ArrayList<>(IngredientReferenceTest.EVALUATIONS);
        Case bad = withRaw(evaluations.get(1).reference(), "A[B");
        evaluations.set(1, evaluate(bad, IngredientReferenceTest.PARSER,
                IngredientReferenceTest.EXTRACTOR, IngredientReferenceTest.REGISTRY));
        String report = report(IngredientReferenceTest.DATASET, evaluations);
        assertThat(report).contains("18 / 19 (94.7%)", "12 / 19 (63.2%)",
                "UNBALANCED_BRACKET=[S002]", "S020 PASS EXCLUDED: REFERENCE_VERSION_CONFLICT");
    }

    private static Case withRaw(Case c, String raw) {
        Ingredient i = c.ingredient();
        return new Case(c.id(), c.gtin(), c.productName(), c.source(), new Ingredient(raw, raw, i.note(),
                i.expectedParseState(), i.expectedRoots(), i.expectedNestedPaths(), i.expectedEvidence(), i.reviewedCandidates()));
    }
}
