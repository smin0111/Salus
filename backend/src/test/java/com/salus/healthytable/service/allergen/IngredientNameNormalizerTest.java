package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.salus.healthytable.service.allergen.IngredientReferenceTest.*;

class IngredientNameNormalizerTest {
    @ParameterizedTest
    @CsvSource({"밀: 미국산,밀,미국산,WHEAT", "새우: 중국산,새우,중국산,SHRIMP", "새우: 미국산,새우,미국산,SHRIMP"})
    void originAnnotationPreservesRawAndStructuralPath(String raw, String name, String origin, String allergen) {
        var node = PARSER.parse(raw).roots().get(0);
        assertThat(node.semanticName()).isEqualTo(new IngredientSemanticName(raw, name, List.of(origin)));
        assertThat(node.rawText()).isEqualTo(raw);
        assertThat(node.normalizedName()).isEqualTo(raw);
        assertThat(REGISTRY.findExactAliases(raw)).isEmpty();
        assertThat(EXTRACTOR.extractEvidence(PARSER.parse(raw))).singleElement().satisfies(e -> {
            assertThat(e.normalizedAllergen().allergen()).isEqualTo(allergen);
            assertThat(e.confidence()).isEqualTo(AllergenEvidence.MatchConfidence.CERTAIN);
            assertThat(e.evidenceType()).isEqualTo(AllergenEvidence.EvidenceType.DIRECT_NAME);
            assertThat(e.path()).containsExactly(raw);
            assertThat(e.rawText()).isEqualTo(raw);
            assertThat(e.matchedText()).isEqualTo(raw);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"미국산", "중국산", "미국산: 감자", "밀: 미국산 아님", "밀: 미국산 50%",
            "밀: 미국산: 기타", "밀: 불명", "밀：미국산", "밀: 미국산 제외", "우유 제외",
            "돼지감자", "밀가루향", "버터향료", "비프맛양념분말"})
    void unsupportedDescriptionsNeverBecomeShorterAlias(String raw) {
        assertThat(IngredientNameNormalizer.normalize(raw).normalizedName()).isEqualTo(raw);
        assertThat(EXTRACTOR.extractEvidence(PARSER.parse(raw))).isEmpty();
    }

    @Test
    void percentagesWhitespaceAndNonAllergenOriginsStaySeparateFromRegistryRecognition() {
        String raw = "  밀  30% :  미국산  ";
        assertThat(IngredientNameNormalizer.normalize(raw))
                .isEqualTo(new IngredientSemanticName(raw, "밀", List.of("미국산", "30%")));
        var milk = PARSER.parse("원유 100%(국산)").roots().get(0);
        assertThat(milk.semanticName())
                .isEqualTo(new IngredientSemanticName("원유 100%", "원유", List.of("100%")));
        assertThat(milk.rawText()).isEqualTo("원유 100%(국산)");
        assertThat(milk.children()).extracting(IngredientNode::name).containsExactly("국산");
        assertThat(IngredientNameNormalizer.normalize("팜유: 말레이시아산").normalizedName()).isEqualTo("팜유");
        assertThat(EXTRACTOR.extractEvidence(PARSER.parse("팜유: 말레이시아산"))).isEmpty();
        assertThatThrownBy(() -> milk.semanticName().annotations().add("추가"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @CsvSource({"버터향,유크림,MILK", "새우풍미유,새우,SHRIMP", "비프맛양념,쇠고기,BEEF"})
    void flavorParentCannotOverrideCertainChild(String parent, String child, String allergen) {
        String raw = parent + "(" + child + ")";
        var evidence = EXTRACTOR.extractEvidence(PARSER.parse(raw));
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).path()).containsExactly(parent);
        assertThat(evidence.get(0).evidenceType()).isEqualTo(AllergenEvidence.EvidenceType.LEXICAL_HINT);
        assertThat(evidence.get(0).confidence()).isEqualTo(AllergenEvidence.MatchConfidence.POSSIBLE);
        assertThat(evidence.get(1).path()).containsExactly(parent, child);
        assertThat(evidence.get(1).confidence()).isEqualTo(AllergenEvidence.MatchConfidence.CERTAIN);
        assertThat(evidence).allSatisfy(e -> {
            assertThat(e.normalizedAllergen().allergen()).isEqualTo(allergen);
            assertThat(e.rawText()).isEqualTo(raw);
        });
    }
}
