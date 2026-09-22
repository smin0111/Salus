package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static com.salus.healthytable.service.allergen.AllergenEvidence.*;
import static com.salus.healthytable.service.allergen.PresenceStatus.*;
import static org.assertj.core.api.Assertions.*;

class AllergenEvidenceResolverTest {
    private final AllergenEvidenceResolver resolver = new AllergenEvidenceResolver();
    record Combination(String name, List<AllergenEvidence> input, PresenceStatus status, EvidenceSource strongest) {
        @Override public String toString() { return name; }
    }

    static AllergenEvidence evidence(EvidenceSource source, EvidenceType type, MatchConfidence confidence,
                                     String id, String text, List<String> path, List<String> children) {
        return new AllergenEvidence(source, text, text, new NormalizedAllergenRef(id, children), type, confidence, path);
    }
    static AllergenEvidence declaration(String id) {
        return evidence(EvidenceSource.DECLARATION, EvidenceType.DIRECT_DECLARATION, MatchConfidence.CERTAIN,
                id, "함유 표시", List.of(), List.of());
    }
    static AllergenEvidence ingredient(String id) {
        return evidence(EvidenceSource.INGREDIENT, EvidenceType.DERIVED_FROM, MatchConfidence.CERTAIN,
                id, "원재료", List.of("원재료"), List.of());
    }
    static AllergenEvidence cross(String id) {
        return evidence(EvidenceSource.CROSS_CONTACT, EvidenceType.CROSS_CONTACT, MatchConfidence.CERTAIN,
                id, "혼입 가능", List.of(), List.of());
    }
    static AllergenEvidence hint(String text) {
        return evidence(EvidenceSource.INGREDIENT, EvidenceType.LEXICAL_HINT, MatchConfidence.POSSIBLE,
                "MILK", text, List.of(text), List.of());
    }

    static Stream<Combination> combinations() {
        var d = declaration("SOY");
        var i = ingredient("SOY");
        var c = cross("SOY");
        var h = hint("버터향");
        return Stream.of(
                new Combination("Declaration only", List.of(d), CONFIRMED_PRESENT, EvidenceSource.DECLARATION),
                new Combination("Ingredient CERTAIN only", List.of(i), CONFIRMED_PRESENT, EvidenceSource.INGREDIENT),
                new Combination("Cross-contact only", List.of(c), CROSS_CONTACT_ONLY, EvidenceSource.CROSS_CONTACT),
                new Combination("Lexical hint only", List.of(h), POSSIBLE_PRESENT, EvidenceSource.INGREDIENT),
                new Combination("Declaration + Ingredient", List.of(d, i), CONFIRMED_PRESENT, EvidenceSource.DECLARATION),
                new Combination("Declaration + Cross-contact", List.of(d, c), CONFIRMED_PRESENT, EvidenceSource.DECLARATION),
                new Combination("Ingredient + Cross-contact", List.of(i, c), CONFIRMED_PRESENT, EvidenceSource.INGREDIENT),
                new Combination("All three", List.of(d, i, c), CONFIRMED_PRESENT, EvidenceSource.DECLARATION),
                new Combination("Possible + Cross-contact", List.of(h, cross("MILK")), POSSIBLE_PRESENT, EvidenceSource.INGREDIENT),
                new Combination("Multiple POSSIBLE", List.of(h, hint("우유향"), hint("밀크향")), POSSIBLE_PRESENT, EvidenceSource.INGREDIENT),
                new Combination("Declaration + Possible", List.of(declaration("MILK"), h), CONFIRMED_PRESENT, EvidenceSource.DECLARATION),
                new Combination("Ingredient DIRECT_NAME", List.of(evidence(EvidenceSource.INGREDIENT,
                        EvidenceType.DIRECT_NAME, MatchConfidence.CERTAIN, "SOY", "대두", List.of("대두"), List.of())),
                        CONFIRMED_PRESENT, EvidenceSource.INGREDIENT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combinations")
    void resolvesSourceSemanticsWithoutCountBasedEscalation(Combination combination) {
        // 입력 순서는 provenance 순서만 바꿀 수 있고 상태/strongest 판정은 바꾸지 않는다.
        for (List<AllergenEvidence> input : permutations(combination.input())) {
            var facts = resolver.resolve(input);
            assertThat(facts).singleElement().satisfies(f -> {
                assertThat(f.allergen()).isEqualTo(input.get(0).normalizedAllergen().allergen());
                assertThat(f.status()).isEqualTo(combination.status());
                assertThat(f.strongestPresenceSource()).isEqualTo(combination.strongest());
                assertThat(f.evidences()).containsExactlyElementsOf(input);
            });
            assertThat(resolver.resolve(input)).isEqualTo(facts);
        }
    }

    private static List<List<AllergenEvidence>> permutations(List<AllergenEvidence> values) {
        if (values.isEmpty()) return List.of(List.of());
        var output = new ArrayList<List<AllergenEvidence>>();
        for (int index = 0; index < values.size(); index++) {
            var remaining = new ArrayList<>(values);
            var first = remaining.remove(index);
            for (var suffix : permutations(remaining)) {
                var order = new ArrayList<AllergenEvidence>();
                order.add(first);
                order.addAll(suffix);
                output.add(order);
            }
        }
        return output;
    }

    @Test
    void silenceAndDeclaredNoneCannotCancelPositiveIngredientEvidence() {
        var ingredient = evidence(EvidenceSource.INGREDIENT, EvidenceType.DIRECT_NAME, MatchConfidence.CERTAIN,
                "APPLE", "사과", List.of("사과"), List.of());
        var none = new AllergenDeclarationParser(IngredientReferenceTest.REGISTRY).parse("해당사항 없음");
        assertThat(none.evidence()).isEmpty();
        var input = new ArrayList<>(none.evidence());
        input.add(ingredient);
        assertThat(resolver.resolve(input)).singleElement().satisfies(f -> {
            assertThat(f.allergen()).isEqualTo("APPLE");
            assertThat(f.status()).isEqualTo(CONFIRMED_PRESENT);
            assertThat(f.evidences()).containsExactly(ingredient);
        });
    }

    @Test
    void emptyMissingAndUnreadableSourcesProduceNoAbsenceFacts() {
        assertThat(resolver.resolve(List.of())).isEmpty();
        var registry = IngredientReferenceTest.REGISTRY;
        var d = new AllergenDeclarationParser(registry);
        var c = new AllergenCrossContactParser(registry);
        var input = new ArrayList<AllergenEvidence>();
        input.addAll(d.parse(DeclarationInput.notFound()).evidence());
        input.addAll(d.parse(DeclarationInput.unreadable("우유")).evidence());
        input.addAll(c.parse(CrossContactInput.notFound()).evidence());
        input.addAll(c.parse(CrossContactInput.unreadable("우유")).evidence());
        input.addAll(IngredientReferenceTest.EXTRACTOR.extractEvidence(
                IngredientReferenceTest.PARSER.parse(IngredientInput.unreadable("우유"))));
        assertThat(resolver.resolve(input)).isEmpty();
        assertThat(PresenceStatus.values()).containsExactly(CONFIRMED_PRESENT, POSSIBLE_PRESENT, CROSS_CONTACT_ONLY);
    }

    @Test
    void hierarchyIdsStaySeparateAndNeverProduceParentOrChildFacts() {
        assertThat(resolver.resolve(List.of(ingredient("QUAIL_EGG"))))
                .extracting(AllergenFact::allergen).containsExactly("QUAIL_EGG");
        assertThat(resolver.resolve(List.of(ingredient("OYSTER"))))
                .extracting(AllergenFact::allergen).containsExactly("OYSTER");
        var facts = resolver.resolve(List.of(ingredient("QUAIL_EGG"), declaration("EGG_GROUP"), ingredient("OYSTER")));
        assertThat(facts).extracting(AllergenFact::allergen).containsExactly("QUAIL_EGG", "EGG_GROUP", "OYSTER");
    }

    @Test
    void explicitChildrenUnionPreservesEachOriginalEvidenceWithoutChildFacts() {
        var a = evidence(EvidenceSource.DECLARATION, EvidenceType.DIRECT_DECLARATION, MatchConfidence.CERTAIN,
                "SHELLFISH", "조개류(굴 포함)", List.of(), List.of("OYSTER"));
        var b = evidence(EvidenceSource.CROSS_CONTACT, EvidenceType.CROSS_CONTACT, MatchConfidence.CERTAIN,
                "SHELLFISH", "조개류(전복, 홍합, 굴) 혼입 가능", List.of(), List.of("ABALONE", "MUSSEL", "OYSTER"));
        assertThat(resolver.resolve(List.of(a, b))).singleElement().satisfies(f -> {
            assertThat(f.allergen()).isEqualTo("SHELLFISH");
            assertThat(ResolverReferenceValidation.explicitChildren(f)).containsExactly("OYSTER", "ABALONE", "MUSSEL");
            assertThat(f.evidences()).containsExactly(a, b);
            assertThat(f.evidences().get(0).normalizedAllergen().explicitChildren()).containsExactly("OYSTER");
            assertThat(f.evidences().get(1).normalizedAllergen().explicitChildren()).containsExactly("ABALONE", "MUSSEL", "OYSTER");
        });
    }

    @Test
    void dedupUsesAllFieldsAndPreservesDifferentPathsRawTextAndChildren() {
        var a = ingredient("SOY");
        var duplicate = new AllergenEvidence(a.source(), a.rawText(), a.matchedText(), a.normalizedAllergen(),
                a.evidenceType(), a.confidence(), a.path());
        var raw = new AllergenEvidence(a.source(), "다른 전체 원문", a.matchedText(), a.normalizedAllergen(),
                a.evidenceType(), a.confidence(), a.path());
        var matched = new AllergenEvidence(a.source(), a.rawText(), "다른 매칭 조각", a.normalizedAllergen(),
                a.evidenceType(), a.confidence(), a.path());
        var path = new AllergenEvidence(a.source(), a.rawText(), a.matchedText(), a.normalizedAllergen(),
                a.evidenceType(), a.confidence(), List.of("소스B", "원재료"));
        var child = new AllergenEvidence(a.source(), a.rawText(), a.matchedText(),
                new NormalizedAllergenRef("SOY", List.of("명시된 자식")), a.evidenceType(), a.confidence(), a.path());
        var relation = new AllergenEvidence(a.source(), a.rawText(), a.matchedText(), a.normalizedAllergen(),
                EvidenceType.DIRECT_NAME, a.confidence(), a.path());
        assertThat(resolver.resolve(List.of(a, duplicate, raw, matched, path, child, relation))).singleElement()
                .satisfies(f -> assertThat(f.evidences()).containsExactly(a, raw, matched, path, child, relation));
        var soyHint = evidence(EvidenceSource.INGREDIENT, EvidenceType.LEXICAL_HINT, MatchConfidence.POSSIBLE,
                "SOY", a.rawText(), a.path(), List.of());
        assertThat(resolver.resolve(List.of(a, soyHint, declaration("SOY"), cross("SOY"))))
                .singleElement().satisfies(f -> assertThat(f.evidences()).hasSize(4));
    }

    @Test
    void doesNotMutateInputsAndReturnsImmutableSnapshots() {
        var input = new ArrayList<>(List.of(declaration("SOY"), ingredient("SOY"), ingredient("MILK")));
        var original = List.copyOf(input);
        var facts = resolver.resolve(input);
        assertThat(input).containsExactlyElementsOf(original);
        input.clear();
        assertThat(facts).hasSize(2);
        assertThat(facts.get(0).evidences()).hasSize(2);
        assertThatThrownBy(() -> facts.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> facts.get(0).evidences().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> facts.get(0).evidences().get(0).normalizedAllergen().explicitChildren().add("자식"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unsupportedSourceTypeConfidenceContractsFailExplicitlyIncludingLikely() {
        int rejected = 0;
        for (var source : EvidenceSource.values()) for (var type : EvidenceType.values())
            for (var confidence : MatchConfidence.values()) {
                boolean supported = confidence == MatchConfidence.CERTAIN
                        && ((source == EvidenceSource.DECLARATION && type == EvidenceType.DIRECT_DECLARATION)
                        || (source == EvidenceSource.CROSS_CONTACT && type == EvidenceType.CROSS_CONTACT)
                        || (source == EvidenceSource.INGREDIENT
                            && (type == EvidenceType.DIRECT_NAME || type == EvidenceType.DERIVED_FROM)));
                supported |= source == EvidenceSource.INGREDIENT && type == EvidenceType.LEXICAL_HINT
                        && confidence == MatchConfidence.POSSIBLE;
                if (!supported) {
                    var invalid = evidence(source, type, confidence, "SOY", "미지원", List.of(), List.of());
                    assertThatThrownBy(() -> resolver.resolve(List.of(declaration("SOY"), invalid)))
                            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Evidence 계약");
                    rejected++;
                }
            }
        assertThat(rejected).isEqualTo(40);
    }

    @Test
    void nullAndUnnormalizedIdsAreNotSilentlyDropped() {
        assertThatThrownBy(() -> resolver.resolve(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> resolver.resolve(Collections.singletonList(null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> resolver.resolve(List.of(ingredient(" ")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AllergenFact("SOY", CONFIRMED_PRESENT, List.of(), EvidenceSource.DECLARATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AllergenFact("SOY", CONFIRMED_PRESENT, List.of(ingredient("MILK")), EvidenceSource.INGREDIENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AllergenFact("SOY", CONFIRMED_PRESENT, List.of(ingredient("SOY")), EvidenceSource.DECLARATION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void springWiresResolverWithoutExternalDependencies() {
        try (var context = new AnnotationConfigApplicationContext(AllergenEvidenceResolver.class)) {
            assertThat(context.getBean(AllergenEvidenceResolver.class).resolve(List.of(cross("SOY"))))
                    .singleElement().extracting(AllergenFact::status).isEqualTo(CROSS_CONTACT_ONLY);
        }
    }
}
