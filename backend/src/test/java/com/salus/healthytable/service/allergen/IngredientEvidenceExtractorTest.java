package com.salus.healthytable.service.allergen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class IngredientEvidenceExtractorTest {
    private final IngredientTreeParser parser = new IngredientTreeParser();
    private final IngredientEvidenceExtractor extractor;

    IngredientEvidenceExtractorTest() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        extractor = new IngredientEvidenceExtractor(new AllergenRegistry(dictionary));
    }

    static Stream<AllergenLexicalRegressionTest.Case> lexicalCases() throws IOException {
        try (InputStream input = IngredientEvidenceExtractorTest.class.getResourceAsStream(
                "/allergens/allergen-lexical-regression.json")) {
            return new ObjectMapper().readValue(input, AllergenLexicalRegressionTest.Dataset.class).cases().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lexicalCases")
    void reusesAllTwentyFiveLexicalCasesThroughIngredientParsing(AllergenLexicalRegressionTest.Case c) {
        List<AllergenEvidence> evidence = extractor.extractEvidence(parser.parse(c.input()));
        assertThat(evidence).extracting(e -> new AllergenLexicalRegressionTest.Expected(
                e.normalizedAllergen().allergen(), AllergenAlias.Relation.valueOf(e.evidenceType().name()), e.confidence()))
                .containsExactlyInAnyOrderElementsOf(c.expected());
        assertThat(evidence).noneMatch(e -> c.forbiddenAllergens().contains(e.normalizedAllergen().allergen()));
        assertThat(evidence).allSatisfy(e -> {
            assertThat(e.source()).isEqualTo(AllergenEvidence.EvidenceSource.INGREDIENT);
            assertThat(e.rawText()).isEqualTo(c.input());
            assertThat(e.matchedText()).isEqualTo(c.input());
            assertThat(e.path()).containsExactly(c.input());
            assertThat(e.normalizedAllergen().explicitChildren()).isEmpty();
        });
    }

    @Test
    void nestedEvidenceIncludesFullPathAndNeverPromotesGenericParents() {
        String raw = "기타가공품[혼합제제[대두레시틴, 유화제], 정제소금], 설탕, 우유";
        List<AllergenEvidence> evidence = extractor.extractEvidence(parser.parse(raw));
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0)).isEqualTo(new AllergenEvidence(AllergenEvidence.EvidenceSource.INGREDIENT,
                raw, "대두레시틴", new NormalizedAllergenRef("SOY", List.of()),
                AllergenEvidence.EvidenceType.DERIVED_FROM, AllergenEvidence.MatchConfidence.CERTAIN,
                List.of("기타가공품", "혼합제제", "대두레시틴")));
        assertThat(evidence.get(1).path()).containsExactly("우유");
    }

    @Test
    void duplicatePathsAreCollapsedButDistinctPathsAndRelationsArePreserved() {
        var evidence = extractor.extractEvidence(parser.parse("소스A[대두유, 대두유], 소스B[대두레시틴], 우유, 우유"));
        assertThat(evidence).hasSize(3);
        assertThat(evidence).extracting(AllergenEvidence::path).containsExactly(
                List.of("소스A", "대두유"), List.of("소스B", "대두레시틴"), List.of("우유"));
        var oyster = extractor.extractEvidence(parser.parse("굴"));
        assertThat(oyster).hasSize(2).extracting(e -> e.normalizedAllergen().allergen())
                .containsExactlyInAnyOrder("OYSTER", "SHELLFISH");
    }

    @Test
    void annotationsDoNotBecomeCertaintyBySubstringOrInventedHierarchy() {
        var evidence = extractor.extractEvidence(parser.parse("정제수(물), 천일염(국산), 설탕(원당 100%), 알류"));
        assertThat(evidence).singleElement().satisfies(e -> {
            assertThat(e.normalizedAllergen()).isEqualTo(new NormalizedAllergenRef("EGG_GROUP", List.of()));
            assertThat(e.path()).containsExactly("알류");
        });
        assertThat(extractor.extractEvidence(parser.parse("밀: 미국산, 우유 제외")))
                .singleElement().satisfies(e -> {
                    assertThat(e.normalizedAllergen().allergen()).isEqualTo("WHEAT");
                    assertThat(e.matchedText()).isEqualTo("밀: 미국산");
                    assertThat(e.path()).containsExactly("밀: 미국산");
                });
        assertThat(extractor.extractEvidence(parser.parse(IngredientInput.unreadable("우유")))).isEmpty();
        assertThat(extractor.extractEvidence(parser.parse(IngredientInput.notFound()))).isEmpty();
    }

    @Test
    void springWiresSeparateParserAndExtractor() {
        try (var context = new AnnotationConfigApplicationContext(IngredientTreeParser.class,
                IngredientEvidenceExtractor.class, AllergenRegistry.class, AllergenDictionary.class)) {
            var tree = context.getBean(IngredientTreeParser.class).parse("탈지분유");
            assertThat(context.getBean(IngredientEvidenceExtractor.class).extractEvidence(tree)).hasSize(1);
        }
    }
}
