package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AllergenMatcher} 테스트입니다. 실제 ko-allergens.yaml 사전으로 판정 규칙을 확인합니다.
 */
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
    @DisplayName("흔한 조개·계란·우유·밀 가공품 이름도 해당 알레르기와 충돌한다")
    void commonShellfishAndProcessedFoodsAreDetected() {
        // 2026-10-14 실사용 시뮬레이션: 조개류 알레르기에 꼬막비빔밥("꼬막 1kg")이 그대로 제공됨.
        assertThat(matcher.conflicts("조개류", "꼬막 1kg")).isTrue();
        assertThat(matcher.conflicts("조개류", "재첩국용 재첩 300g")).isTrue();
        assertThat(matcher.conflicts("조개류", "관자 6개")).isTrue();
        assertThat(matcher.conflicts("계란", "지단 조금")).isTrue();
        assertThat(matcher.conflicts("우유", "바닐라 아이스크림 1스쿱")).isTrue();
        assertThat(matcher.conflicts("밀", "냉동 만두 10개")).isTrue();
        assertThat(matcher.conflicts("밀", "또띠아 2장")).isTrue();
        assertThat(matcher.conflicts("밀", "모닝빵 4개")).isTrue();
        // 오탐 방지: 이름이 비슷해도 다른 재료는 걸리지 않습니다.
        assertThat(matcher.conflicts("조개류", "대파 1대, 관절에 좋은 사골")).isFalse();
        assertThat(matcher.conflicts("밀", "쌀 2컵, 찹쌀가루 3큰술")).isFalse();
        assertThat(matcher.conflicts("계란", "단호박 1/2개")).isFalse();
        assertThat(matcher.conflicts("우유", "아이스 아메리카노")).isFalse();
    }

    @Test
    @DisplayName("사이시옷·합성어·계란 이름 없는 표현도 알레르기와 충돌한다")
    void spellingVariantsAndEggExpressionsAreDetected() {
        // 2026-10-14 실사용 평가: 조개류 알레르기에 클램차우더 "조갯살 20g", 계란 알레르기 비빔밥에 "반숙 후라이"가 남음.
        assertThat(matcher.conflicts("조개류", "조갯살 20g")).isTrue();
        assertThat(matcher.conflicts("조개류", "클램 차우더")).isTrue();
        assertThat(matcher.conflicts("조개류", "굴전 부치기")).isTrue();
        assertThat(matcher.conflicts("굴", "생굴 200g")).isTrue();
        assertThat(matcher.conflicts("계란", "반숙 후라이를 올린다")).isTrue();
        assertThat(matcher.conflicts("계란", "스크램블해 익힌다")).isTrue();
        // 오탐 방지: 후라이팬은 계란이 아니고, 굴소스 없는 굴비(생선)·석류는 조개가 아닙니다.
        assertThat(matcher.conflicts("계란", "후라이팬에 기름을 두른다")).isFalse();
        assertThat(matcher.conflicts("조개류", "굴비 2마리, 석류 1/2개")).isFalse();
    }

    @Test
    @DisplayName("반죽 가루·짜장가루·모듬해물은 해당 알레르기와 충돌한다")
    void premixesAndMixedSeafoodAreDetected() {
        // 2026-10-14 다양한 요리 평가: 밀 알레르기에 "오코노미야끼 파우더", 대두 알레르기에 "짜장가루",
        // 오징어 알레르기에 "모듬해물"이 그대로 제공됨.
        assertThat(matcher.conflicts("밀", "오코노미야끼 파우더 200g")).isTrue();
        assertThat(matcher.conflicts("밀", "타코야키 분말 1컵")).isTrue();
        assertThat(matcher.conflicts("밀", "치킨너겟 20개")).isTrue();
        assertThat(matcher.conflicts("밀", "냉동 돈까스 2장")).isTrue();
        assertThat(matcher.conflicts("대두", "짜장가루 1.5국자")).isTrue();
        assertThat(matcher.conflicts("밀", "짜장가루 1.5국자")).isTrue();
        assertThat(matcher.conflicts("오징어", "모듬해물 400g")).isTrue();
        assertThat(matcher.conflicts("새우", "냉동 해물믹스 300g")).isTrue();
        assertThat(matcher.conflicts("조개류", "모둠해물 1팩")).isTrue();
        // 오탐 방지
        assertThat(matcher.conflicts("오징어", "해물파전용 부추 1줌")).isFalse();
        assertThat(matcher.conflicts("대두", "자장자장 재우기")).isFalse();
    }

    @Test
    @DisplayName("시판 카레와 춘장은 밀 알레르기와 충돌한다")
    void curryRouxAndChunjangConflictWithWheat() {
        // 2026-10-14 실사용 시뮬레이션: 밀 알레르기에 카레라이스 "고체 카레 1/2팩"이 그대로 제공됨.
        assertThat(matcher.conflicts("밀", "고체 카레 1/2팩")).isTrue();
        assertThat(matcher.conflicts("밀", "카레가루 3큰술")).isTrue();
        assertThat(matcher.conflicts("밀", "춘장 2큰술")).isTrue();
        assertThat(matcher.conflicts("밀", "사각어묵 4장")).isTrue();
        // 오탐 방지
        assertThat(matcher.conflicts("밀", "카놀라유 1큰술")).isFalse();
        assertThat(matcher.conflicts("우유", "고체 카레 1/2팩")).isFalse();
    }

    @Test
    @DisplayName("종류를 알 수 없는 견과류는 땅콩·호두·잣 알레르기 모두와 충돌한다")
    void unspecifiedNutsConflictWithEveryNutAllergy() {
        // 2026-10-14 실사용 시뮬레이션: 땅콩 알레르기에 팟타이 "견과류 1줌"이 그대로 제공됨.
        assertThat(matcher.conflicts("땅콩", "견과류 1줌")).isTrue();
        assertThat(matcher.conflicts("호두", "견과류를 절구에 빻는다")).isTrue();
        assertThat(matcher.conflicts("잣", "하루견과 1봉")).isTrue();
        // 오탐 방지: 견과가 아닌 재료는 걸리지 않습니다.
        assertThat(matcher.conflicts("땅콩", "결과적으로 바삭해진다")).isFalse();
        assertThat(matcher.conflicts("땅콩", "코코넛 밀크 200ml, 도넛 1개")).isFalse();
        assertThat(matcher.conflicts("우유", "견과류 1줌")).isFalse();
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
    @DisplayName("굴의 기존 shellfish 탐지와 미등록 한 글자 literal fallback을 보존한다")
    void oysterRetainsExistingShellfishTermsAndUnknownSingleCharacterUsesLiteralFallback() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        assertThat(dictionary.matchTermsFor("굴")).containsExactly(
                "조개", "조개류", "shellfish", "굴", "전복", "홍합", "바지락", "모시조개", "가리비", "소라", "굴소스", "조개육수",
                // 2026-10-14 보강(꼬막비빔밥이 조개류 알레르기에 제공된 사례)
                "꼬막", "재첩", "대합", "동죽", "관자", "키조개", "맛조개",
                // 2026-10-14 보강(클램차우더 "조갯살"이 조개류 알레르기에 제공된 사례, 한 글자 "굴" 합성어)
                "조갯살", "클램", "clam", "굴전", "굴국", "굴밥", "굴무침", "생굴", "석화",
                // 2026-10-14 보강(팔보채 "모듬해물"이 오징어 알레르기에 제공된 사례)
                "모듬해물", "모둠해물", "해물믹스", "냉동해물", "씨푸드믹스");
        assertThat(matcher.conflicts("굴", "굴 100g")).isTrue();
        assertThat(matcher.conflicts("굴", "굴소스 1큰술")).isTrue();
        assertThat(matcher.conflicts("굴", "조개육수 200ml")).isTrue();
        assertThat(matcher.conflicts("굴", "소금 1g")).isFalse();
        assertThat(dictionary.isKnown("김")).isFalse();
        assertThat(new AllergenRegistry(dictionary).findExactAliases("김")).isEmpty();
        assertThat(dictionary.matchTermsFor("김")).containsExactly("김");
        assertThat(matcher.conflicts("김", "김 2장")).isTrue();
        assertThat(matcher.conflicts("김", "김치 100g")).isFalse();
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
