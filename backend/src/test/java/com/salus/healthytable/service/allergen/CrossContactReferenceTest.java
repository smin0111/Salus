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
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.salus.healthytable.service.allergen.CrossContactReferenceValidation.*;
import static org.assertj.core.api.Assertions.assertThat;

class CrossContactReferenceTest {
    static final Dataset DATASET = load("/allergens/cross-contact-reference.json", Dataset.class);
    static final DeclarationReferenceValidation.Dataset DECLARATION = load(
            "/allergens/declaration-reference.json", DeclarationReferenceValidation.Dataset.class);
    static final AllergenRegistry REGISTRY = registry();
    static final AllergenCrossContactParser PARSER = new AllergenCrossContactParser(REGISTRY);
    static final List<Evaluation> EVALUATIONS = DATASET.cases().stream().map(c -> evaluate(c, PARSER)).toList();
    static final List<DeclarationReferenceValidation.Evaluation> DECLARATION_EVALUATIONS =
            DECLARATION.cases().stream().map(c -> DeclarationReferenceValidation.evaluate(
                    c, new AllergenDeclarationParser(REGISTRY))).toList();

    private static AllergenRegistry registry() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenRegistry(dictionary);
    }

    private static <T> T load(String resource, Class<T> type) {
        try (InputStream input = CrossContactReferenceTest.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing fixture: " + resource);
            return new ObjectMapper().readValue(input, type);
        } catch (IOException error) {
            throw new IllegalStateException("Invalid fixture: " + resource, error);
        }
    }

    static Stream<Evaluation> cases() { return EVALUATIONS.stream(); }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void strictlyMatchesEveryProductIncludingNotFound(Evaluation evaluation) {
        assertThat(evaluation.error()).as(evaluation.exceptionDetail()).isNull();
        assertThat(evaluation.mismatches()).as(evaluation.reference().id()).isEmpty();
        assertThat(evaluation.exact()).isTrue();
    }

    @Test
    void keepsTwentyStableIdsAndOriginalWorkbookDigest() throws Exception {
        assertThat(DATASET.cases()).extracting(ReferenceCase::id).containsExactlyElementsOf(
                IntStream.rangeClosed(1, 20).mapToObj(i -> "S%03d".formatted(i)).toList());
        assertThat(DATASET.cases()).extracting(ReferenceCase::gtin).doesNotHaveDuplicates();
        assertThat(DATASET.sourceSha256()).isEqualTo(DECLARATION.sourceSha256());
        byte[] workbook = Files.readAllBytes(Path.of("..", "docs", "reference", DATASET.sourceWorkbook()));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(workbook)))
                .isEqualTo(DATASET.sourceSha256());
        for (int i = 0; i < DATASET.cases().size(); i++) {
            ReferenceCase c = DATASET.cases().get(i);
            assertThat(c.source().workbookCell()).isEqualTo("Accepted_20!I" + (i + 2));
            assertThat(c.gtin()).isEqualTo(DECLARATION.cases().get(i).gtin());
            if (c.crossContact().availability() == CrossContactInput.Availability.READABLE) {
                assertThat(c.crossContact().raw()).isEqualTo(c.source().originalCrossContact());
            } else {
                assertThat(c.crossContact().raw()).isNull();
                assertThat(c.crossContact().note()).isNotBlank();
            }
        }
    }

    @Test
    void calculatesMeasuredAccuracyAndCoverageWithoutCountingAbsenceAsSuccess() {
        assertThat(EVALUATIONS.stream().filter(Evaluation::evaluable)).hasSize(10).allMatch(Evaluation::exact);
        assertThat(EVALUATIONS.stream().filter(Evaluation::covered)).hasSize(10);
        assertThat(EVALUATIONS.stream().filter(Evaluation::evaluable)
                .filter(e -> e.exact() && e.result().unparsedTokens().isEmpty())).hasSize(10);
        assertThat(EVALUATIONS.stream().map(e -> e.result().state())
                .filter(s -> s == CrossContactState.CROSS_CONTACT_NOT_FOUND)).hasSize(10);
        assertThat(EVALUATIONS.stream().flatMap(e -> e.result().unparsedTokens().stream()).toList())
                .isEmpty();
        assertThat(DECLARATION_EVALUATIONS).allMatch(DeclarationReferenceValidation.Evaluation::exact);
        assertThat(DECLARATION_EVALUATIONS.stream()
                .filter(DeclarationReferenceValidation.Evaluation::evaluableDeclaration)).hasSize(15);
    }

    @Test
    void calculatesFourGroupsAndLabelEvidenceUnion() throws IOException {
        Map<Group, List<String>> groups = groups(EVALUATIONS, DECLARATION_EVALUATIONS);
        assertThat(groups.get(Group.DECLARATION_ONLY)).containsExactly("S001", "S002", "S006", "S009", "S013", "S014");
        assertThat(groups.get(Group.CROSS_CONTACT_ONLY)).containsExactly("S016");
        assertThat(groups.get(Group.BOTH)).containsExactly("S003", "S005", "S007", "S008", "S011", "S012", "S017", "S018", "S019");
        assertThat(groups.get(Group.NEITHER)).containsExactly("S004", "S010", "S015", "S020");
        assertThat(EVALUATIONS.size() - groups.get(Group.NEITHER).size()).isEqualTo(16);
        String report = report(DATASET, EVALUATIONS, DECLARATION_EVALUATIONS);
        Path target = Path.of("target", "cross-contact-reference-report.txt");
        Files.createDirectories(target.getParent());
        Files.writeString(target, report);
        System.out.println(report);
    }

    @Test
    void unresolvedVersionConflictNeverSelectsAnUnverifiedLabel() {
        ReferenceCase s020 = DATASET.cases().get(19);
        assertThat(s020.source().issue()).isEqualTo("REFERENCE_VERSION_CONFLICT");
        assertThat(s020.crossContact().raw()).isNull();
        assertThat(s020.crossContact().availability()).isEqualTo(CrossContactInput.Availability.NOT_FOUND);
        assertThat(EVALUATIONS.get(19).covered()).isFalse();
        assertThat(EVALUATIONS.get(19).evaluable()).isFalse();
    }
}
