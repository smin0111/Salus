package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngredientTreeParserTest {
    private final IngredientTreeParser parser = new IngredientTreeParser();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "밀, 대두, 우유|밀;대두;우유",
            "혼합제제[대두레시틴, 유화제], 설탕|혼합제제;혼합제제/대두레시틴;혼합제제/유화제;설탕",
            "기타가공품[혼합제제[대두레시틴, 유화제], 정제소금], 설탕|기타가공품;기타가공품/혼합제제;기타가공품/혼합제제/대두레시틴;기타가공품/혼합제제/유화제;기타가공품/정제소금;설탕",
            "조미액(간장, 설탕, 정제소금), 물|조미액;조미액/간장;조미액/설탕;조미액/정제소금;물",
            "A[B(C, D), E], F|A;A/B;A/B/C;A/B/D;A/E;F",
            "A[B, C[D, E]], F|A;A/B;A/C;A/C/D;A/C/E;F",
            "A{B(C, D), E}, F|A;A/B;A/B/C;A/B/D;A/E;F",
            "A［B（C, D）, E］, F|A;A/B;A/B/C;A/B/D;A/E;F",
            "정제수(물), 천일염(국산), 설탕(원당 100%)|정제수;정제수/물;천일염;천일염/국산;설탕;설탕/원당",
            "복합유산균 1,000억 이상(LGG유산균 250억 이상)/250mL, 설탕|복합유산균 1,000억 이상;복합유산균 1,000억 이상/LGG유산균 250억 이상;설탕",
            "대두 100%(국산), 코코아분말(싱가포르산) 1.1%|대두;대두/국산;코코아분말;코코아분말/싱가포르산"
    })
    void preservesCompleteSyntheticTree(String raw, String expectedPaths) {
        IngredientTree tree = parser.parse(raw);
        assertThat(tree.state()).isEqualTo(IngredientTree.State.PARSED);
        assertThat(tree.rawText()).isEqualTo(raw);
        assertThat(paths(tree.roots(), List.of()).stream().map(p -> String.join("/", p)))
                .containsExactly(expectedPaths.split(";"));
        assertVerbatim(tree.roots(), raw);
    }

    @Test
    void namesAndRawRetainSpacingAndPercentWhileLookupNameIsSeparate() {
        String raw = " \n혼합제제[ 대두레시틴,\t유화제 ], 우유  3% ";
        IngredientTree tree = parser.parse(raw);
        assertThat(tree.rawText()).isEqualTo(raw);
        assertThat(tree.roots().get(0).rawText()).isEqualTo(" \n혼합제제[ 대두레시틴,\t유화제 ]");
        assertThat(tree.roots().get(0).children().get(1).rawText()).isEqualTo("\t유화제 ");
        assertThat(tree.roots().get(1).name()).isEqualTo("우유  3%");
        assertThat(tree.roots().get(1).normalizedName()).isEqualTo("우유");
        assertThatThrownBy(() -> tree.roots().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tree.roots().get(0).children().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "A[B, C|UNBALANCED_BRACKET", "A], B|UNBALANCED_BRACKET",
            "A[B, C)|MISMATCHED_BRACKET", "A[(B])|MISMATCHED_BRACKET",
            "A[]|EMPTY_NODE", "A,,B|EMPTY_NODE", ",A|EMPTY_NODE", "A,|EMPTY_NODE",
            "[A]|EMPTY_NODE", "100%|EMPTY_NODE",
            "A[B] extra|UNSUPPORTED_STRUCTURE", "A[B](C)|UNSUPPORTED_STRUCTURE"
    })
    void classifiesMalformedInputWithoutReturningPartialTree(String raw, IngredientParseException.Kind kind) {
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOfSatisfying(IngredientParseException.class,
                error -> assertThat(error.kind()).isEqualTo(kind));
    }

    @Test
    void nestingLimitFailsExplicitlyInsteadOfStackOverflow() {
        assertThatThrownBy(() -> parser.parse("A[".repeat(66) + "B" + "]".repeat(66)))
                .isInstanceOfSatisfying(IngredientParseException.class,
                        error -> assertThat(error.kind()).isEqualTo(IngredientParseException.Kind.UNSUPPORTED_STRUCTURE));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n", "\u00a0"})
    void absentReadableTextIsNotAParsedEmptyTree(String raw) {
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(IngredientParseException.class);
    }

    @Test
    void observationStatesAreDistinct() {
        assertThat(parser.parse(IngredientInput.notFound()).state()).isEqualTo(IngredientTree.State.NOT_FOUND);
        IngredientTree unreadable = parser.parse(IngredientInput.unreadable("우유..."));
        assertThat(unreadable.state()).isEqualTo(IngredientTree.State.UNREADABLE);
        assertThat(unreadable.rawText()).isEqualTo("우유...");
        assertThat(unreadable.normalizedText()).isNull();
        assertThat(unreadable.roots()).isEmpty();
    }

    static List<List<String>> paths(List<IngredientNode> nodes, List<String> ancestors) {
        List<List<String>> result = new ArrayList<>();
        for (IngredientNode node : nodes) {
            List<String> path = new ArrayList<>(ancestors);
            path.add(node.normalizedName());
            result.add(List.copyOf(path));
            result.addAll(paths(node.children(), path));
        }
        return result;
    }

    static void assertVerbatim(List<IngredientNode> nodes, String context) {
        for (IngredientNode node : nodes) {
            assertThat(context).contains(node.rawText());
            assertThat(node.rawText()).contains(node.name());
            assertVerbatim(node.children(), node.rawText());
        }
    }
}
