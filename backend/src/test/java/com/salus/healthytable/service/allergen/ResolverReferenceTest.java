package com.salus.healthytable.service.allergen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.salus.healthytable.service.allergen.ResolverReferenceValidation.*;
import static org.assertj.core.api.Assertions.assertThat;

class ResolverReferenceTest {
    static final Dataset DATASET = load();
    static final AllergenEvidenceResolver RESOLVER = new AllergenEvidenceResolver();
    static final List<Evaluation> EVALUATIONS = DATASET.cases().stream().map(ResolverReferenceTest::evaluateCase).toList();

    private static Dataset load() {
        try (InputStream input = ResolverReferenceTest.class.getResourceAsStream("/allergens/resolver-reference.json")) {
            if (input == null) throw new IllegalStateException("Missing Resolver Reference");
            return new ObjectMapper().readValue(input, Dataset.class);
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    private static Evaluation evaluateCase(Case c) {
        var declaration = CrossContactReferenceTest.DECLARATION_EVALUATIONS.stream()
                .filter(e -> e.reference().id().equals(c.id())).findFirst().orElseThrow();
        var cross = CrossContactReferenceTest.EVALUATIONS.stream()
                .filter(e -> e.reference().id().equals(c.id())).findFirst().orElseThrow();
        var ingredient = IngredientReferenceTest.EVALUATIONS.stream()
                .filter(e -> e.reference().id().equals(c.id())).findFirst().orElseThrow();
        if (!c.gtin().equals(declaration.reference().gtin()) || !c.gtin().equals(cross.reference().gtin())
                || !c.gtin().equals(ingredient.reference().gtin())) {
            return failed(c, "Reference ID/GTIN mismatch");
        }
        if (c.excludedReason() != null) {
            if (!ingredient.reference().ingredient().expectedParseState().equals("CONFLICT")
                    || !c.excludedReason().equals(ingredient.reference().source().issue())
                    || ingredient.reference().ingredient().raw() != null) {
                return failed(c, "Excluded Reference conflict contract mismatch");
            }
            return evaluate(c, List.of(), RESOLVER);
        }
        // Parser failure/mismatch가 빈 positive 결과로 숨겨지거나 분모에서 제외되지 않는다.
        if (!declaration.exact() || !cross.exact() || !ingredient.exact()) {
            return failed(c, "Upstream parser/reference validation failed");
        }
        var input = new ArrayList<>(declaration.result().evidence());
        input.addAll(ingredient.evidence());
        input.addAll(cross.result().evidence());
        return evaluate(c, input, RESOLVER);
    }

    static Stream<Evaluation> cases() { return EVALUATIONS.stream(); }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void combinesActualParserOutputsAgainstIndependentExpectedFacts(Evaluation evaluation) {
        assertThat(evaluation.error()).as(evaluation.reference().id()).isNull();
        assertThat(evaluation.factMismatches()).isEmpty();
        assertThat(evaluation.detailMismatches()).isEmpty();
        assertThat(evaluation.exact()).isTrue();
        if (evaluation.evaluable()) {
            assertThat(RESOLVER.resolve(evaluation.input())).isEqualTo(evaluation.facts());
            assertThat(evaluation.facts().stream().flatMap(f -> f.evidences().stream()))
                    .containsExactlyInAnyOrderElementsOf(evaluation.input().stream().distinct().toList());
        }
    }

    @Test
    void stableIdsAndSourceConflictArePreserved() {
        assertThat(DATASET.cases()).extracting(Case::id).containsExactlyElementsOf(
                IntStream.rangeClosed(1, 20).mapToObj(i -> "S%03d".formatted(i)).toList());
        assertThat(DATASET.cases()).extracting(Case::gtin).doesNotHaveDuplicates();
        assertThat(EVALUATIONS.stream().filter(Evaluation::evaluable)).hasSize(19);
        for (Case c : DATASET.cases()) {
            assertThat(c.expectedFacts()).extracting(ExpectedFact::allergen).doesNotHaveDuplicates();
            assertThat(c.expectedFacts()).allSatisfy(f -> {
                assertThat(f.sources()).isNotEmpty().doesNotHaveDuplicates().contains(f.strongestPresenceSource());
                assertThat(f.explicitChildren()).doesNotHaveDuplicates();
                assertThat(f.evidenceCount()).isPositive();
            });
        }
        var conflict = EVALUATIONS.get(19);
        assertThat(conflict.reference().excludedReason()).isEqualTo("REFERENCE_VERSION_CONFLICT");
        assertThat(conflict.evaluable()).isFalse();
        assertThat(conflict.input()).isEmpty();
        assertThat(conflict.facts()).isEmpty();
        assertThat(IngredientReferenceTest.DATASET.cases().get(19).source().originalIngredient())
                .contains("양반 들기름김:", "양반 올리브김:");
    }

    @Test
    void reportsMetricsWithoutAddingSyntheticExamplesToProductDenominator() throws IOException {
        String report = report(EVALUATIONS);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "resolver-reference-report.txt"), report);
        System.out.println(report);
        assertThat(report).contains("19 / 19 (100.0%)", "16 / 19 (84.2%)", "Fact total: 116",
                "CONFIRMED_PRESENT: 40 facts, 15 products", "POSSIBLE_PRESENT: 0 facts, 0 products",
                "CROSS_CONTACT_ONLY: 76 facts, 10 products", "DECLARATION_ONLY=15", "INGREDIENT_ONLY=2",
                "CROSS_CONTACT_ONLY=76", "DECLARATION+INGREDIENT=22", "DECLARATION+CROSS_CONTACT=0",
                "INGREDIENT+CROSS_CONTACT=1", "ALL_THREE=0", "DECLARATION=37", "INGREDIENT=3",
                "CROSS_CONTACT=76", "min=1, max=7, average=1.319; retained=153");
        assertThat(EVALUATIONS).allMatch(Evaluation::exact);
    }

    @Test
    void unresolvedSevenOccurrencesRemainSixTermsAndAreNeverBackfilledFromDeclarations() {
        var before = IngredientReferenceTest.EVALUATIONS.stream().flatMap(e -> e.unsupported().stream()).toList();
        EVALUATIONS.forEach(e -> RESOLVER.resolve(e.input()));
        var after = IngredientReferenceTest.DATASET.cases().stream()
                .map(c -> IngredientReferenceValidation.evaluate(c, IngredientReferenceTest.PARSER,
                        IngredientReferenceTest.EXTRACTOR, IngredientReferenceTest.REGISTRY))
                .flatMap(e -> e.unsupported().stream()).toList();
        assertThat(after).containsExactlyElementsOf(before).hasSize(7);
        assertThat(after.stream().map(IngredientReferenceValidation.Unsupported::token).distinct())
                .containsExactlyInAnyOrder("유당", "레시틴", "젤라틴", "사골엑기스", "오뚜기참치간장분말", "쇠고기브이용");
        var pork = EVALUATIONS.get(13).facts().stream().filter(f -> f.allergen().equals("PORK")).findFirst().orElseThrow();
        assertThat(pork.evidences()).singleElement().satisfies(e ->
                assertThat(e.source()).isEqualTo(AllergenEvidence.EvidenceSource.DECLARATION));
        var soy = EVALUATIONS.get(10).facts().stream().filter(f -> f.allergen().equals("SOY")).findFirst().orElseThrow();
        assertThat(soy.evidences()).singleElement().satisfies(e ->
                assertThat(e.source()).isEqualTo(AllergenEvidence.EvidenceSource.DECLARATION));
        assertThat(EVALUATIONS.stream().flatMap(e -> e.facts().stream()).flatMap(f -> f.evidences().stream())
                .filter(e -> e.source() == AllergenEvidence.EvidenceSource.INGREDIENT))
                .noneMatch(e -> after.stream().anyMatch(u -> e.matchedText().equals(u.token())));
    }

    @Test
    void actualChildrenAndPossibleEvidenceSurviveConfirmedFacts() {
        var s019 = EVALUATIONS.get(18);
        var shellfish = s019.facts().stream().filter(f -> f.allergen().equals("SHELLFISH")).findFirst().orElseThrow();
        assertThat(ResolverReferenceValidation.explicitChildren(shellfish)).containsExactly("OYSTER");
        assertThat(s019.facts()).noneMatch(f -> f.allergen().equals("OYSTER"));
        var s013 = EVALUATIONS.get(12).facts().get(0);
        assertThat(s013.status()).isEqualTo(PresenceStatus.CONFIRMED_PRESENT);
        assertThat(s013.evidences()).extracting(AllergenEvidence::confidence).containsExactly(
                AllergenEvidence.MatchConfidence.CERTAIN, AllergenEvidence.MatchConfidence.POSSIBLE);
        var s011Sulfite = EVALUATIONS.get(10).facts().stream()
                .filter(f -> f.allergen().equals("SULFITE")).findFirst().orElseThrow();
        assertThat(s011Sulfite.status()).isEqualTo(PresenceStatus.CONFIRMED_PRESENT);
        assertThat(s011Sulfite.strongestPresenceSource()).isEqualTo(AllergenEvidence.EvidenceSource.INGREDIENT);
        assertThat(sources(s011Sulfite)).containsExactlyInAnyOrder(
                AllergenEvidence.EvidenceSource.INGREDIENT, AllergenEvidence.EvidenceSource.CROSS_CONTACT);
    }
}
