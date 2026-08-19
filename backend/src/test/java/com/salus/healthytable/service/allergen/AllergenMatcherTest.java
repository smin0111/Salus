package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AllergenMatcherTest {

    private final AllergenMatcher matcher = matcher();

    private static AllergenMatcher matcher() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenMatcher(dictionary);
    }

    @Test
    @DisplayName("사전이 식약처 표시 대상을 모두 담고 있다")
    void dictionaryIsLoaded() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        assertThat(dictionary.size()).isGreaterThanOrEqualTo(19);
        assertThat(dictionary.isKnown("우유")).isTrue();
        assertThat(dictionary.isKnown("밀")).isTrue();
        assertThat(dictionary.isKnown("대두")).isTrue();
    }

    @Test
    @DisplayName("한 글자 알레르겐은 파생 재료로 탐지된다")
    void singleSyllableAllergensAreDetectedThroughDerivedTerms() {
        assertThat(matcher.conflicts("밀", "칼국수 면 200g")).isTrue();
        assertThat(matcher.conflicts("밀", "부침가루 100g")).isTrue();
        assertThat(matcher.conflicts("콩", "된장 2큰술")).isTrue();
        assertThat(matcher.conflicts("게", "게살 100g")).isTrue();
        assertThat(matcher.conflicts("잣", "잣가루 약간")).isTrue();
    }

    @Test
    @DisplayName("한 글자 알레르겐은 재료명 자체로도 탐지된다")
    void singleSyllableAllergensMatchExactToken() {
        assertThat(matcher.conflicts("밀", "밀 200g")).isTrue();
        assertThat(matcher.conflicts("게", "게 2마리")).isTrue();
    }

    @Test
    @DisplayName("한 글자 알레르겐이 무관한 단어에 걸리지 않는다")
    void singleSyllableAllergensDoNotFalselyMatch() {
        assertThat(matcher.conflicts("밀", "밀크티 1잔")).isFalse();
        assertThat(matcher.conflicts("게", "고등어 2마리")).isFalse();
    }

    @Test
    @DisplayName("우유 알레르기가 유제품 파생 재료를 잡는다")
    void milkAllergyCatchesDairyDerivatives() {
        for (String ingredient : List.of("버터 20g", "체다 치즈 2장", "생크림 200ml", "연유 1큰술", "요거트 100g")) {
            assertThat(matcher.conflicts("우유", ingredient))
                    .withFailMessage("우유 알레르기가 '%s'를 잡지 못했습니다", ingredient)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("대두 알레르기가 장류와 두부를 잡는다")
    void soyAllergyCatchesFermentedAndTofu() {
        for (String ingredient : List.of("두부 1모", "된장 1큰술", "간장 2큰술", "두유 200ml", "유부 5장")) {
            assertThat(matcher.conflicts("대두", ingredient))
                    .withFailMessage("대두 알레르기가 '%s'를 잡지 못했습니다", ingredient)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("별칭으로 등록해도 같은 알레르겐으로 판정된다")
    void aliasesResolveToSameAllergen() {
        assertThat(matcher.conflicts("달걀", "마요네즈 3큰술")).isTrue();
        assertThat(matcher.conflicts("계란", "마요네즈 3큰술")).isTrue();
        assertThat(matcher.conflicts("소고기", "차돌박이 200g")).isTrue();
        assertThat(matcher.conflicts("쇠고기", "차돌박이 200g")).isTrue();
    }

    @Test
    @DisplayName("'우유 알레르기'처럼 수식어가 붙어도 해석한다")
    void declarationWithSuffixIsResolved() {
        assertThat(matcher.conflicts("우유 알레르기", "버터 20g")).isTrue();
    }

    @Test
    @DisplayName("사전에 없는 알레르기 표기도 판정을 건너뛰지 않는다")
    void unknownAllergyStillMatchesLiterally() {
        assertThat(matcher.conflicts("키위", "키위 2개")).isTrue();
        assertThat(matcher.conflicts("키위", "사과 2개")).isFalse();
    }

    @Test
    @DisplayName("알레르기가 없으면 충돌도 없다")
    void noAllergiesMeansNoConflicts() {
        assertThat(matcher.findConflicts(List.of(), List.of("버터 20g"))).isEmpty();
        assertThat(matcher.findConflicts(null, List.of("버터 20g"))).isEmpty();
    }

    @Test
    @DisplayName("여러 알레르기가 각각 보고된다")
    void multipleAllergiesAreReportedIndividually() {
        List<String> conflicts = matcher.findConflicts(
                List.of("우유", "밀", "땅콩"),
                List.of("크림파스타", "생크림 200ml", "파스타면 200g"));
        assertThat(conflicts).containsExactlyInAnyOrder("우유", "밀");
    }
}
