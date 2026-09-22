package com.salus.healthytable.service.allergen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 합성 lexical unit 테스트. 실제품 Reference/원재료 Evidence/안전 판정과 별개다. */
class AllergenLexicalRegressionTest {
    record Expected(String allergen, AllergenAlias.Relation relation, AllergenEvidence.MatchConfidence confidence) {}
    record Case(String id, String input, List<Expected> expected, List<String> forbiddenAllergens) {
        @Override public String toString() { return id + ": " + input; }
    }
    record Dataset(String scope, String provenance, List<Case> cases) {}

    private static final AllergenRegistry REGISTRY = registry();
    private static final Dataset DATASET = load();

    private static AllergenRegistry registry() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenRegistry(dictionary);
    }

    private static Dataset load() {
        try (InputStream input = AllergenLexicalRegressionTest.class.getResourceAsStream(
                "/allergens/allergen-lexical-regression.json")) {
            if (input == null) throw new IllegalStateException("Missing synthetic fixture");
            return new ObjectMapper().readValue(input, Dataset.class);
        } catch (IOException error) {
            throw new IllegalStateException("Invalid synthetic fixture", error);
        }
    }

    static Stream<Case> cases() { return DATASET.cases().stream(); }

    private static List<Expected> actual(Case c) {
        return REGISTRY.findExactAliases(c.input()).stream()
                .map(alias -> new Expected(alias.allergen(), alias.relation(), alias.confidence())).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void preservesWholeTokenRelationAndConfidenceWithoutFalsePositives(Case c) {
        assertThat(actual(c)).containsExactlyInAnyOrderElementsOf(c.expected());
        assertThat(actual(c)).noneMatch(e -> c.forbiddenAllergens().contains(e.allergen()));

        // Registry에 파생어/힌트가 등록돼도 공식 표시의 직접 명칭으로 승격하지 않는다.
        List<String> direct = c.expected().stream()
                .filter(e -> e.relation() == AllergenAlias.Relation.DIRECT_NAME)
                .map(Expected::allergen).toList();
        DeclarationParseResult declaration = new AllergenDeclarationParser(REGISTRY).parse(c.input() + " 함유");
        CrossContactParseResult cross = new AllergenCrossContactParser(REGISTRY).parse(c.input() + " 혼입 가능");
        assertThat(declaration.evidence()).extracting(e -> e.normalizedAllergen().allergen())
                .containsExactlyElementsOf(direct);
        assertThat(cross.evidence()).extracting(e -> e.normalizedAllergen().allergen())
                .containsExactlyElementsOf(direct);
        List<String> unsupported = direct.isEmpty() ? List.of(c.input()) : List.of();
        assertThat(declaration.unparsedTokens()).containsExactlyElementsOf(unsupported);
        assertThat(cross.unparsedTokens()).containsExactlyElementsOf(unsupported);
    }

    @ParameterizedTest
    @CsvSource({"메밀,밀,BUCKWHEAT", "땅콩,콩,PEANUT", "완두콩,콩,", "강낭콩,콩,",
            "땅콩호박,땅콩,", "땅콩호박,콩,", "굴비,굴,"})
    void wholeLexicalUnitPrecedesShorterAliasesWithoutSubstringFallback(
            String longer, String shorter, String expected) {
        assertThat(REGISTRY.findExactAliases(shorter)).isNotEmpty();
        List<AllergenAlias> matches = REGISTRY.findExactAliases(longer);
        if (expected == null) {
            assertThat(matches).isEmpty();
        } else {
            assertThat(matches).extracting(AllergenAlias::allergen).containsExactly(expected);
            List<String> ordered = REGISTRY.aliasesLongestFirst().stream().map(AllergenAlias::text).toList();
            assertThat(ordered.indexOf(longer)).isLessThan(ordered.indexOf(shorter));
        }
        assertThat(matches).noneMatch(alias -> alias.text().equals(shorter));
    }

    @Test
    void lexicalLookupPreservesAllExactRelationsAndImmutableOrdering() {
        assertThat(REGISTRY.aliasesLongestFirst()).isSortedAccordingTo(
                Comparator.comparingInt((AllergenAlias alias) -> alias.text().length()).reversed());
        assertThat(REGISTRY.findExactAliases("굴")).containsExactlyInAnyOrder(
                new AllergenAlias("굴", AllergenAlias.Relation.DIRECT_NAME, "OYSTER"),
                new AllergenAlias("굴", AllergenAlias.Relation.DERIVED_FROM, "SHELLFISH"));
        assertThat(REGISTRY.findDirectName("굴").orElseThrow().allergen()).isEqualTo("OYSTER");
        assertThatThrownBy(() -> REGISTRY.findExactAliases("굴").clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void lookupDoesNotInterpretPhrasesOrEraseMeaningfulPunctuation() {
        for (String text : List.of("우유 함유", "우유, 대두", "탈지 분유", "땅콩-호박", "")) {
            assertThat(REGISTRY.findExactAliases(text)).as(text).isEmpty();
        }
        assertThat(REGISTRY.findExactAliases("  탈지분유\n")).singleElement()
                .extracting(AllergenAlias::allergen).isEqualTo("MILK");
    }

    @Test
    void reportsSyntheticResultsSeparatelyFromProductReference() throws IOException {
        assertThat(DATASET.scope()).isEqualTo("SYNTHETIC_LEXICAL_REGRESSION");
        assertThat(DATASET.cases()).hasSize(25).extracting(Case::id).doesNotHaveDuplicates();
        assertThat(DATASET.cases()).allMatch(c -> c.id().startsWith("SYN-"));
        long passed = DATASET.cases().stream().filter(c -> actual(c).equals(c.expected())
                && actual(c).stream().noneMatch(e -> c.forbiddenAllergens().contains(e.allergen()))).count();
        StringBuilder report = new StringBuilder(DATASET.scope() + "\n" + DATASET.provenance() + "\n");
        report.append("Cases: ").append(DATASET.cases().size()).append("; passed: ").append(passed)
                .append("; failed: ").append(DATASET.cases().size() - passed).append('\n');
        DATASET.cases().forEach(c -> report.append(c.id()).append(" ").append(c.input()).append(" -> ")
                .append(actual(c)).append("; forbidden=").append(c.forbiddenAllergens()).append('\n'));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "allergen-lexical-regression-report.txt"), report);
        System.out.println(report);
        assertThat(passed).isEqualTo(DATASET.cases().size());
    }
}
