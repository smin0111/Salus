package com.salus.healthytable.service.allergen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.salus.healthytable.service.allergen.IngredientReferenceValidation.*;
import static org.assertj.core.api.Assertions.assertThat;

class IngredientReferenceTest {
    static final Dataset DATASET = load();
    static final IngredientTreeParser PARSER = new IngredientTreeParser();
    static final AllergenRegistry REGISTRY = registry();
    static final IngredientEvidenceExtractor EXTRACTOR = new IngredientEvidenceExtractor(REGISTRY);
    static final List<Evaluation> EVALUATIONS = DATASET.cases().stream()
            .map(c -> evaluate(c, PARSER, EXTRACTOR, REGISTRY)).toList();

    private static Dataset load() {
        try (InputStream input = IngredientReferenceTest.class.getResourceAsStream("/allergens/ingredient-reference.json")) {
            if (input == null) throw new IllegalStateException("Missing Ingredient Reference");
            return new ObjectMapper().readValue(input, Dataset.class);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static AllergenRegistry registry() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenRegistry(dictionary);
    }
    static Stream<Evaluation> cases() { return EVALUATIONS.stream(); }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void validatesTwentyReferenceEntries(Evaluation evaluation) {
        assertThat(evaluation.error()).as(evaluation.errorDetail()).isNull();
        assertThat(evaluation.structureMismatches()).isEmpty();
        assertThat(evaluation.evidenceMismatches()).isEmpty();
        if (evaluation.tree() != null) IngredientTreeParserTest.assertVerbatim(
                evaluation.tree().roots(), evaluation.reference().ingredient().raw());
    }

    @Test
    void originalWorkbookAndTwentyStableIdsArePreserved() throws Exception {
        assertThat(DATASET.cases()).extracting(Case::id).containsExactlyElementsOf(
                IntStream.rangeClosed(1, 20).mapToObj(i -> "S%03d".formatted(i)).toList());
        assertThat(DATASET.cases()).extracting(Case::gtin).doesNotHaveDuplicates();
        byte[] workbook = Files.readAllBytes(Path.of("..", "docs", "reference", DATASET.sourceWorkbook()));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(workbook))).isEqualTo(DATASET.sourceSha256());
        for (int i = 0; i < DATASET.cases().size(); i++) {
            Case c = DATASET.cases().get(i);
            assertThat(c.source().workbookCell()).isEqualTo("Accepted_20!G" + (i + 2));
            if (c.ingredient().expectedParseState().equals("PARSED")) {
                assertThat(c.ingredient().raw()).isEqualTo(c.source().originalIngredient());
            }
        }
    }

    @Test
    void calculatesIndependentIngredientMetricsAndWritesAuditReport() throws IOException {
        String report = report(DATASET, EVALUATIONS);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "ingredient-reference-report.txt"), report);
        System.out.println(report);
        assertThat(EVALUATIONS.stream().filter(Evaluation::evaluable)).hasSize(19).allMatch(Evaluation::structuralExact);
        assertThat(EVALUATIONS.stream().filter(Evaluation::covered)).hasSize(13);
        assertThat(EVALUATIONS.stream().flatMap(e -> e.evidence().stream())).hasSize(39);
        assertThat(EVALUATIONS.stream().flatMap(e -> e.unsupported().stream())).hasSize(7);
        assertThat(EVALUATIONS).allMatch(Evaluation::exact);
    }

    @Test
    void versionConflictRetainsOriginalComponentsWithoutChoosingEitherVersion() {
        Evaluation conflict = EVALUATIONS.get(19);
        assertThat(conflict.reference().ingredient().raw()).isNull();
        assertThat(conflict.reference().ingredient().note()).isNotBlank();
        assertThat(conflict.reference().source().originalIngredient()).contains("양반 들기름김:", "양반 올리브김:");
        assertThat(conflict.reference().source().issue()).isEqualTo("REFERENCE_VERSION_CONFLICT");
        assertThat(conflict.evaluable()).isFalse();
        assertThat(conflict.evidence()).isEmpty();
        assertThat(conflict.tree()).isNull();
    }
}
