package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AllergenCrossContactParser} 테스트입니다. 교차접촉 문구를 근거로만 기록하고 안전 판정으로 확대하지 않는지 확인합니다.
 */
class AllergenCrossContactParserTest {
    private AllergenRegistry registry;
    private AllergenCrossContactParser parser;

    @BeforeEach
    void setUp() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        registry = new AllergenRegistry(dictionary);
        parser = new AllergenCrossContactParser(registry);
    }

    // 지원하는 3가지 교차접촉 문장 형식을 모두 인식해야 합니다.
    @ParameterizedTest
    @ValueSource(strings = {
            "밀을 사용한 제품과 같은 제조시설", "밀를 사용한 제품과 같은 제조시설",
            "밀 사용한 제품과 같은 제조시설", "밀와 같은 제조시설", "밀과 같은 제조시설",
            "밀 혼입가능", "밀 혼입 가능",
            "밀을사용한 제품과 같은 제조시설", "  밀을\n사용한\t제품과 같은 제조시설  ",
            "밀\u00a0혼입\u00a0가능"})
    void recognizesOnlyCrossContactEvidence(String raw) {
        CrossContactParseResult result = parser.parse(raw);
        assertThat(result.state()).isEqualTo(CrossContactState.CROSS_CONTACT_PRESENT);
        assertThat(result.rawText()).isEqualTo(raw);
        assertThat(result.unparsedTokens()).isEmpty();
        assertThat(result.evidence()).singleElement().satisfies(item -> {
            assertThat(item.source()).isEqualTo(AllergenEvidence.EvidenceSource.CROSS_CONTACT);
            assertThat(item.evidenceType()).isEqualTo(AllergenEvidence.EvidenceType.CROSS_CONTACT);
            assertThat(item.confidence()).isEqualTo(AllergenEvidence.MatchConfidence.CERTAIN);
            assertThat(item.rawText()).isEqualTo(raw);
            assertThat(item.matchedText()).isEqualTo("밀");
            assertThat(item.normalizedAllergen()).isEqualTo(new NormalizedAllergenRef("WHEAT", List.of()));
            assertThat(item.path()).isEmpty();
        });
    }

    // 원문을 그대로 보존하고, 괄호 안 쉼표는 목록 구분자로 나누지 않아야 합니다.
    @Test
    void preservesRawAndExplicitChildrenWithoutSplittingTheirCommas() {
        String token = "조개류(굴,\n 전복, 홍합 포함)";
        String raw = "  메밀, 땅콩, 게, 새우, " + token + "를 사용한 제품과 같은 제조시설\n";
        CrossContactParseResult result = parser.parse(raw);
        assertThat(result.rawText()).isEqualTo(raw);
        assertThat(result.normalizedText()).isEqualTo(
                "메밀, 땅콩, 게, 새우, 조개류(굴, 전복, 홍합 포함)를 사용한 제품과 같은 제조시설");
        assertThat(result.evidence()).extracting(e -> e.normalizedAllergen().allergen())
                .containsExactly("BUCKWHEAT", "PEANUT", "CRAB", "SHRIMP", "SHELLFISH");
        assertThat(result.evidence().get(4).matchedText()).isEqualTo(token);
        assertThat(result.evidence().get(4).normalizedAllergen().explicitChildren())
                .containsExactly("OYSTER", "ABALONE", "MUSSEL");
    }

    // 괄호에 명시된 자식(굴)만 보존하고, 상위 항목만 있을 때 자식을 추론하지 않아야 합니다.
    @ParameterizedTest
    @ValueSource(strings = {"조개류(굴)", "조개류(굴 포함)"})
    void retainsOnlyExplicitlyNamedChild(String token) {
        assertThat(parser.parse(token + " 혼입 가능").evidence()).singleElement()
                .extracting(AllergenEvidence::normalizedAllergen)
                .isEqualTo(new NormalizedAllergenRef("SHELLFISH", List.of("OYSTER")));
        assertThat(parser.parse("조개류 혼입 가능").evidence().get(0).normalizedAllergen().explicitChildren())
                .isEmpty();
    }

    // 지원하지 않는 토큰은 CERTAIN 근거가 되지 않고 unparsedTokens에 남아야 합니다.
    @ParameterizedTest
    @ValueSource(strings = {"버터", "우유향", "굴비", "밀크초콜릿",
            "미확인원료", "조개류(굴 제외)", "조개류(미확인)", "조개류(굴) 제외"})
    void unsupportedTokenNeverBecomesCertainEvidence(String token) {
        CrossContactParseResult result = parser.parse(token + ", 대두 혼입 가능");
        assertThat(result.unparsedTokens()).containsExactly(token);
        assertThat(result.evidence()).extracting(e -> e.normalizedAllergen().allergen()).containsExactly("SOY");
    }

    // 2.5단계: 상위 그룹과 종별 관찰은 두 Parser 모두 구분하고 계란/달걀 호환성은 유지한다.
    @Test
    void resolvedGroupPreservesDistinctLabelObservationsAndSpecificEggAliases() {
        assertThat(registry.aliasesLongestFirst()).contains(
                new AllergenAlias("알류", AllergenAlias.Relation.DIRECT_NAME, "EGG_GROUP"));
        assertThat(new AllergenDeclarationParser(registry).parse("알류 함유").evidence())
                .extracting(e -> e.normalizedAllergen().allergen()).containsExactly("EGG_GROUP");
        assertThat(parser.parse("알류 혼입 가능").evidence())
                .extracting(e -> e.normalizedAllergen().allergen()).containsExactly("EGG_GROUP");
        assertThat(parser.parse("계란, 달걀 혼입 가능").evidence())
                .extracting(e -> e.normalizedAllergen().allergen()).containsExactly("EGG", "EGG");
    }

    // 지원하지 않는 문장은 "교차접촉 없음"으로 추측하지 않고 예외를 던져야 합니다.
    @ParameterizedTest
    @ValueSource(strings = {"우유 함유", "해당사항 없음", "우유 혼입 불가능", "우유 혼입 가능성 없음",
            "우유를 사용한 제품과 같은 제조시설이 아닙니다", "우유 혼입가능이라고 추측",
            "우유, 밀을 사용하지 않은 제품과 같은 제조시설",
            "별도 교차접촉 문구 표시 없음(확인 라벨 기준)"})
    void rejectsUnsupportedSentencesRatherThanInferringAbsence(String raw) {
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("지원하지 않는 교차접촉 표시 형식입니다.");
    }

    // 괄호 짝이 틀리거나 빈 항목이 있으면 일부 근거도 반환하지 않고 실패해야 합니다.
    @ParameterizedTest
    @ValueSource(strings = {"우유) 혼입 가능", "조개류(굴 혼입 가능", "우유,,밀 혼입 가능",
            ",우유 혼입 가능", "우유, 혼입 가능", "조개류() 혼입 가능", "조개류(굴,) 혼입 가능"})
    void malformedListsFailWithoutReturningPartialEvidence(String raw) {
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    // 읽을 수 있다고 한 입력의 원문이 비어 있으면 오류여야 합니다.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void missingReadableInputIsAnError(String raw) {
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    // "표시 없음"과 "읽을 수 없음"은 서로 다른 상태이며 둘 다 근거가 없어야 합니다.
    @Test
    void absenceAndUnreadabilityAreDistinctAndHaveNoEvidence() {
        CrossContactParseResult absent = parser.parse(CrossContactInput.notFound());
        CrossContactParseResult unreadable = parser.parse(CrossContactInput.unreadable("우유..."));
        assertThat(absent.state()).isEqualTo(CrossContactState.CROSS_CONTACT_NOT_FOUND);
        assertThat(absent.rawText()).isNull();
        assertThat(unreadable.state()).isEqualTo(CrossContactState.UNREADABLE);
        assertThat(unreadable.rawText()).isEqualTo("우유...");
        for (CrossContactParseResult result : List.of(absent, unreadable,
                parser.parse(CrossContactInput.unreadable(null)))) {
            assertThat(result.evidence()).isEmpty();
            assertThat(result.unparsedTokens()).isEmpty();
            assertThat(result.normalizedText()).isNull();
        }
        assertThatThrownBy(() -> new CrossContactInput("우유 혼입 가능", CrossContactInput.Availability.NOT_FOUND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 결과 목록은 외부에서 수정할 수 없어야 합니다.
    @Test
    void resultListsAreImmutable() {
        CrossContactParseResult result = parser.parse("우유, 미확인 혼입 가능");
        assertThatThrownBy(() -> result.evidence().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.unparsedTokens().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    // 스프링이 두 파서를 같은 AllergenRegistry로 연결해야 합니다.
    @Test
    void springWiresBothParsersToTheSameRegistry() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                AllergenDeclarationParser.class, AllergenCrossContactParser.class,
                AllergenRegistry.class, AllergenDictionary.class)) {
            assertThat(context.getBean(AllergenCrossContactParser.class).parse("대두 혼입 가능").evidence())
                    .extracting(e -> e.normalizedAllergen().allergen()).containsExactly("SOY");
            assertThat(context.getBean(AllergenDeclarationParser.class).parse("대두 함유").evidence())
                    .extracting(e -> e.normalizedAllergen().allergen()).containsExactly("SOY");
        }
    }
}
