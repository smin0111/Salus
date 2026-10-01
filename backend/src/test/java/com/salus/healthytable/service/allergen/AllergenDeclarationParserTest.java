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

/**
 * {@link AllergenDeclarationParser} 테스트입니다. 공식 함유 표시만 확실한 근거로 기록하는지 확인합니다.
 */
class AllergenDeclarationParserTest {

    private final AllergenDeclarationParser parser = parser();

    // 실제 사전을 읽어 파서를 만듭니다.
    static AllergenDeclarationParser parser() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenDeclarationParser(new AllergenRegistry(dictionary));
    }

    // 공식 직접 명칭은 DIRECT_DECLARATION / CERTAIN 근거만 만들어야 합니다.
    @ParameterizedTest
    @CsvSource({
            "밀,WHEAT", "대두,SOY", "우유,MILK", "계란,EGG", "달걀,EGG",
            "돼지고기,PORK", "쇠고기,BEEF", "새우,SHRIMP", "게,CRAB", "토마토,TOMATO",
            "아황산류,SULFITE", "메밀,BUCKWHEAT", "땅콩,PEANUT", "고등어,MACKEREL",
            "복숭아,PEACH", "호두,WALNUT", "닭고기,CHICKEN", "오징어,SQUID", "잣,PINE_NUT",
            "조개류,SHELLFISH", "굴,OYSTER"
    })
    void directNamesProduceOnlyDeclarationEvidence(String text, String id) {
        String raw = text + " 함유";
        DeclarationParseResult result = parser.parse(raw);
        assertThat(result.state()).isEqualTo(DeclarationState.DECLARED_PRESENT);
        assertThat(result.unparsedTokens()).isEmpty();
        assertThat(result.evidence()).containsExactly(new AllergenEvidence(
                AllergenEvidence.EvidenceSource.DECLARATION, raw, text,
                new NormalizedAllergenRef(id, List.of()),
                AllergenEvidence.EvidenceType.DIRECT_DECLARATION,
                AllergenEvidence.MatchConfidence.CERTAIN, List.of()));
    }

    // 괄호 안 쉼표는 최상위 목록 구분자가 아니어야 합니다.
    @Test
    void parenthesizedCommasAreNotTopLevelSeparators() {
        DeclarationParseResult result = parser.parse("토마토, 조개류(굴, 전복, 홍합 포함), 대두 함유");
        assertThat(result.evidence()).extracting(AllergenEvidence::normalizedAllergen).containsExactly(
                new NormalizedAllergenRef("TOMATO", List.of()),
                new NormalizedAllergenRef("SHELLFISH", List.of("OYSTER", "ABALONE", "MUSSEL")),
                new NormalizedAllergenRef("SOY", List.of()));
        assertThat(result.evidence().get(1).matchedText()).isEqualTo("조개류(굴, 전복, 홍합 포함)");
        assertThat(result.unparsedTokens()).isEmpty();
    }

    // 괄호 없는 상위 항목은 자식을 만들어 내지 않아야 합니다.
    @Test
    void bareParentDoesNotInventExplicitChildren() {
        assertThat(parser.parse("조개류 함유").evidence().get(0).normalizedAllergen())
                .isEqualTo(new NormalizedAllergenRef("SHELLFISH", List.of()));
    }

    // 원문과 매칭 텍스트는 공백까지 그대로, 정규화 텍스트는 공백을 줄인 형태여야 합니다.
    @Test
    void rawAndMatchedTextRemainVerbatimWhileNormalizedTextCollapsesWhitespace() {
        String raw = " \n조개류 (굴,\n 홍합 포함),\t대두\u00a0함유  ";
        DeclarationParseResult result = parser.parse(raw);
        assertThat(result.rawText()).isEqualTo(raw);
        assertThat(result.normalizedText()).isEqualTo("조개류 (굴, 홍합 포함), 대두 함유");
        assertThat(result.evidence()).allSatisfy(item -> assertThat(item.rawText()).isEqualTo(raw));
        assertThat(result.evidence()).extracting(AllergenEvidence::matchedText)
                .containsExactly("조개류 (굴,\n 홍합 포함)", "대두");
    }

    // "해당사항 없음"은 매칭이 없는 상태가 아니라 명시적 DECLARED_NONE이어야 합니다.
    @Test
    void explicitNoneIsNotAnAbsenceOfMatches() {
        DeclarationParseResult result = parser.parse(" 해당사항  없음\n");
        assertThat(result.state()).isEqualTo(DeclarationState.DECLARED_NONE);
        assertThat(result.rawText()).isEqualTo(" 해당사항  없음\n");
        assertThat(result.normalizedText()).isEqualTo("해당사항 없음");
        assertThat(result.evidence()).isEmpty();
        assertThat(result.unparsedTokens()).isEmpty();
    }

    // 표시란을 찾지 못한 경우는 호출자가 명시적으로 NOT_FOUND를 전달해야 합니다.
    @Test
    void callerExplicitlyReportsMissingDeclaration() {
        DeclarationParseResult result = parser.parse(DeclarationInput.notFound());
        assertThat(result.state()).isEqualTo(DeclarationState.DECLARATION_NOT_FOUND);
        assertThat(result.rawText()).isNull();
        assertThat(result.normalizedText()).isNull();
        assertThat(result.evidence()).isEmpty();
    }

    // 읽을 수 없는 입력은 일부 텍스트에 알레르겐이 보여도 근거를 만들지 않아야 합니다.
    @Test
    void unreadableInputNeverCreatesEvidenceEvenIfPartialTextContainsAnAllergen() {
        DeclarationParseResult result = parser.parse(DeclarationInput.unreadable("우유 함유"));
        assertThat(result.state()).isEqualTo(DeclarationState.UNREADABLE);
        assertThat(result.rawText()).isEqualTo("우유 함유");
        assertThat(result.normalizedText()).isNull();
        assertThat(result.evidence()).isEmpty();
        assertThat(parser.parse(DeclarationInput.unreadable(null)).evidence()).isEmpty();
    }

    // 사전에 없는 이름, 파생어, 어휘 힌트는 확실한 직접 근거가 되면 안 됩니다.
    @ParameterizedTest
    @ValueSource(strings = {"밀크초콜릿", "굴비", "버터", "탈지분유", "우유향", "소고기맛베이스", "키위", "글루텐"})
    void unknownDerivedAndLexicalTokensDoNotBecomeCertainDirectEvidence(String token) {
        DeclarationParseResult result = parser.parse(token + " 함유");
        assertThat(result.state()).isEqualTo(DeclarationState.DECLARED_PRESENT);
        assertThat(result.evidence()).isEmpty();
        assertThat(result.unparsedTokens()).containsExactly(token);
    }

    // 일부만 인식되면 인식하지 못한 토큰을 나중 검토를 위해 남겨야 합니다.
    @Test
    void partialMatchesRetainUnknownTokensForLaterReview() {
        DeclarationParseResult result = parser.parse("우유, 키위 함유");
        assertThat(result.evidence()).extracting(item -> item.normalizedAllergen().allergen()).containsExactly("MILK");
        assertThat(result.unparsedTokens()).containsExactly("키위");
    }

    // 지원하지 않는 괄호 표현은 통째로 미인식 처리하고, 일부 자식만 승격하지 않아야 합니다.
    @ParameterizedTest
    @ValueSource(strings = {"조개류(굴 제외)", "조개류(굴비 포함)", "조개류(굴, 미등록조개 포함)",
            "조개류(굴(국산), 홍합 포함)", "조개류(굴 포함) 제외"})
    void unsupportedParenthesesRemainWholeWithoutPromotingPartialChildren(String token) {
        DeclarationParseResult result = parser.parse(token + ", 대두 함유");
        assertThat(result.evidence()).extracting(item -> item.normalizedAllergen().allergen()).containsExactly("SOY");
        assertThat(result.unparsedTokens()).containsExactly(token);
    }

    // 잘못된 입력을 "표시 없음"이나 "해당사항 없음"으로 조용히 바꾸지 않고 예외를 던져야 합니다.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n", "해당사항 없음(먹는샘물 라벨 기준)", "함유 표시 없음",
            "우유 미함유", "우유 함유 가능", "우유를 사용한 제품과 같은 제조시설에서 제조",
            "우유", "함유", "밀,,대두 함유", "밀, 함유", ",밀 함유",
            "조개류(굴 포함 함유", "조개류)굴( 함유", "조개류() 함유"})
    void invalidInputIsNotSilentlyConvertedToMissingOrNone(String text) {
        assertThatThrownBy(() -> parser.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    // NOT_FOUND 상태에는 원문을 함께 넣을 수 없어야 합니다.
    @Test
    void missingObservationCannotAlsoCarryText() {
        assertThatThrownBy(() -> new DeclarationInput("해당사항 없음", DeclarationInput.Availability.NOT_FOUND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 결과 컬렉션은 수정할 수 없는 스냅샷이어야 합니다.
    @Test
    void outputCollectionsAreImmutableSnapshots() {
        List<String> children = new ArrayList<>(List.of("OYSTER"));
        NormalizedAllergenRef ref = new NormalizedAllergenRef("SHELLFISH", children);
        children.clear();
        assertThat(ref.explicitChildren()).containsExactly("OYSTER");
        DeclarationParseResult result = parser.parse("우유 함유");
        assertThatThrownBy(() -> result.evidence().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.evidence().get(0).path().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.unparsedTokens().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
