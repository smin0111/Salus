package com.salus.healthytable.service.allergen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static com.salus.healthytable.service.allergen.IngredientReferenceTest.*;

class IngredientCandidateRegressionTest {
    record Occurrence(String productId, List<String> path) {}
    record Candidate(String term, String classification, String expectedAllergen, String expectedRelation,
                     String expectedConfidence, String outcome, String reason, List<String> sources,
                     List<Occurrence> occurrences) {}
    record Review(String scope, String reviewedAt, List<Candidate> candidates) {}
    static final Review REVIEW = load();

    private static Review load() {
        try (InputStream input = IngredientCandidateRegressionTest.class.getResourceAsStream(
                "/allergens/ingredient-candidate-regression.json")) {
            if (input == null) throw new IllegalStateException("Missing candidate regression fixture");
            return new ObjectMapper().readValue(input, Review.class);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    static Stream<Candidate> candidates() { return REVIEW.candidates().stream(); }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("candidates")
    void reviewsEveryCandidateIndependentlyAndAtEveryOriginalReferencePath(Candidate c) {
        var tree = PARSER.parse(c.term());
        var evidence = EXTRACTOR.extractEvidence(tree);
        assertExpected(c, evidence);
        // 직접 별칭 추가는 공용 라벨 Registry에도 반영된다. 파생/힌트/원산지는 표시 명칭으로 승격하지 않는다.
        List<String> direct = c.classification().equals("CERTAIN_ALIAS")
                ? List.of(c.expectedAllergen()) : List.of();
        assertThat(new AllergenDeclarationParser(REGISTRY).parse(c.term() + " 함유").evidence())
                .extracting(e -> e.normalizedAllergen().allergen()).containsExactlyElementsOf(direct);
        assertThat(new AllergenCrossContactParser(REGISTRY).parse(c.term() + " 혼입 가능").evidence())
                .extracting(e -> e.normalizedAllergen().allergen()).containsExactlyElementsOf(direct);
        assertThat(tree.rawText()).isEqualTo(c.term());
        assertThat(tree.roots()).singleElement().satisfies(node -> {
            assertThat(node.rawText()).isEqualTo(c.term());
            assertThat(node.semanticName().raw()).isEqualTo(c.term());
        });
        assertThat(c.reason()).isNotBlank();
        assertThat(c.occurrences()).isNotEmpty();
        for (Occurrence occurrence : c.occurrences()) {
            var actual = EVALUATIONS.stream()
                    .filter(e -> e.reference().id().equals(occurrence.productId())).findFirst().orElseThrow();
            assertThat(actual.error()).isNull();
            assertThat(actual.reference().ingredient().reviewedCandidates())
                    .anyMatch(review -> review.path().equals(occurrence.path()));
            assertExpected(c, actual.evidence().stream().filter(e -> e.path().equals(occurrence.path())).toList());
            assertThat(actual.unsupported().stream().anyMatch(u -> u.path().equals(occurrence.path())))
                    .isEqualTo(c.expectedAllergen() == null);
        }
    }

    private static void assertExpected(Candidate c, List<AllergenEvidence> evidence) {
        if (c.expectedAllergen() == null) {
            assertThat(evidence).as(c.term()).isEmpty();
        } else {
            assertThat(evidence).as(c.term()).singleElement().satisfies(e -> {
                assertThat(e.normalizedAllergen().allergen()).isEqualTo(c.expectedAllergen());
                assertThat(e.evidenceType().name()).isEqualTo(c.expectedRelation());
                assertThat(e.confidence().name()).isEqualTo(c.expectedConfidence());
                assertThat(e.source()).isEqualTo(AllergenEvidence.EvidenceSource.INGREDIENT);
                assertThat(e.normalizedAllergen().explicitChildren()).isEmpty();
                assertThat(e.rawText()).contains(e.matchedText());
            });
        }
    }

    @Test
    void candidateInventoryAndMeasuredOutcomesAccountForAllThirtyOccurrences() throws IOException {
        assertThat(REVIEW.scope()).contains("NOT product Gold");
        assertThat(REVIEW.candidates()).hasSize(24).extracting(Candidate::term).doesNotHaveDuplicates();
        var occurrences = REVIEW.candidates().stream().flatMap(c -> c.occurrences().stream()).toList();
        var original = DATASET.cases().stream().flatMap(c -> c.ingredient().reviewedCandidates().stream()
                .map(r -> new Occurrence(c.id(), r.path()))).toList();
        assertThat(occurrences).hasSize(30).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(original);
        Map<String, String> categories = Map.of("CERTAIN_ALIAS", "RESOLVED_CERTAIN",
                "CERTAIN_DERIVED", "RESOLVED_CERTAIN", "ANNOTATED_KNOWN_INGREDIENT", "NORMALIZED_ANNOTATION",
                "LEXICAL_HINT", "RESOLVED_POSSIBLE", "SOURCE_UNRESOLVED", "UNRESOLVED_AMBIGUOUS",
                "NEEDS_REVIEW", "UNRESOLVED_AMBIGUOUS", "GENERIC_NON_ALLERGEN", "NON_ALLERGEN_GENERIC");
        Map<String, Long> unique = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Candidate c : REVIEW.candidates()) {
            assertThat(c.outcome()).isEqualTo(categories.get(c.classification()));
            unique.merge(c.outcome(), 1L, Long::sum);
            counts.merge(c.outcome(), (long) c.occurrences().size(), Long::sum);
        }
        assertThat(unique).containsExactlyInAnyOrderEntriesOf(Map.of("RESOLVED_CERTAIN", 12L,
                "RESOLVED_POSSIBLE", 3L, "NORMALIZED_ANNOTATION", 3L, "UNRESOLVED_AMBIGUOUS", 6L));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("RESOLVED_CERTAIN", 14L,
                "RESOLVED_POSSIBLE", 3L, "NORMALIZED_ANNOTATION", 6L, "UNRESOLVED_AMBIGUOUS", 7L));
        var unsupported = EVALUATIONS.stream().flatMap(e -> e.unsupported().stream()).toList();
        assertThat(unsupported).hasSize(7);
        assertThat(unsupported.stream().map(IngredientReferenceValidation.Unsupported::token).distinct())
                .containsExactlyInAnyOrder("유당", "레시틴", "젤라틴", "사골엑기스", "오뚜기참치간장분말", "쇠고기브이용");
        StringBuilder report = new StringBuilder("Candidate triage: review regression, NOT product Gold\n");
        unique.forEach((key, value) -> report.append(key).append(": unique=").append(value)
                .append(", occurrences=").append(counts.get(key)).append('\n'));
        report.append("NON_ALLERGEN_GENERIC: unique=0, occurrences=0\n")
                .append("Measured unresolved: occurrences=").append(unsupported.size())
                .append(", unique=").append(unsupported.stream()
                        .map(IngredientReferenceValidation.Unsupported::token).distinct().count())
                .append(" (before: 30/24)\n");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "ingredient-candidate-report.txt"), report);
        System.out.println(report);
    }

    @Test
    void declarationCannotBackfillUnspecifiedIngredientSources() {
        for (String raw : List.of("레시틴", "젤라틴")) {
            var tree = PARSER.parse(raw);
            assertThat(EXTRACTOR.extractEvidence(tree)).isEmpty();
            assertThat(new AllergenDeclarationParser(REGISTRY).parse("대두, 돼지고기 함유").evidence()).hasSize(2);
            assertThat(EXTRACTOR.extractEvidence(tree)).isEmpty();
        }
        var explicit = EXTRACTOR.extractEvidence(PARSER.parse("젤라틴(돼지), 레시틴(대두)"));
        assertThat(explicit).hasSize(2).allMatch(e -> e.path().size() == 2);
        assertThat(explicit).extracting(e -> e.normalizedAllergen().allergen())
                .containsExactlyInAnyOrder("PORK", "SOY");
    }
}
