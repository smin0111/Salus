package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 법정 표시 대상 밖의 알레르기(과일·견과류·참깨 등)가 프로필 매칭에서 잡히는지,
 * 그리고 라벨 Registry에는 들어가지 않는지 확인합니다(2026-10-29 사전 보강).
 */
class ProfileOnlyAllergenTest {

    private static AllergenDictionary dictionary() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return dictionary;
    }

    private final AllergenMatcher matcher = new AllergenMatcher(dictionary());

    // 실제 매칭: 이름 그대로와 파생 재료 모두 잡힙니다.
    @Test
    void registeredProfileOnlyAllergiesMatchNamesAndDerivedIngredients() {
        assertThat(matcher.conflicts("사과", "사과식초 1큰술")).isTrue();
        assertThat(matcher.conflicts("사과", "애플소스 2큰술")).isTrue();
        assertThat(matcher.conflicts("사과 알레르기", "사과 1/2개")).isTrue();
        assertThat(matcher.conflicts("참깨", "참기름 1작은술")).isTrue();
        assertThat(matcher.conflicts("참깨", "깨 약간")).isTrue();
        assertThat(matcher.conflicts("헤이즐넛", "누텔라 2큰술")).isTrue();
        assertThat(matcher.conflicts("키위", "참다래 1개")).isTrue();
        assertThat(matcher.conflicts("아보카도", "과카몰리")).isTrue();
    }

    // 오탐 방지: 파인애플은 사과가 아니고, 들깨는 참깨가 아닙니다.
    @Test
    void falsePositiveGuards() {
        assertThat(matcher.conflicts("사과", "파인애플 100g")).isFalse();
        assertThat(matcher.conflicts("사과", "pineapple juice")).isFalse();
        assertThat(matcher.conflicts("참깨", "들깨가루 2큰술")).isFalse();
        assertThat(matcher.conflicts("참깨", "깻잎 5장")).isFalse();
    }

    // 묶음 "견과류"는 아몬드·호두·잣 등을 모두 잡지만, "호두"만 등록한 사람을 아몬드로 넓히지 않습니다.
    @Test
    void treeNutGroupCoversMembersWithoutWideningSingleNut() {
        for (String ingredient : List.of("아몬드 슬라이스", "호두 30g", "잣 1큰술", "캐슈넛", "피스타치오", "누텔라")) {
            assertThat(matcher.conflicts("견과류", ingredient)).as(ingredient).isTrue();
        }
        // 구성원의 별칭·파생어(walnut, 잣가루, 잣죽, 마지팬)도 견과류로 잡힙니다.
        for (String ingredient : List.of("walnut 30g", "잣가루 1큰술", "잣죽", "마지팬", "tree nuts 30g", "tree nut 30g", "mixed nuts")) {
            assertThat(matcher.conflicts("견과류", ingredient)).as(ingredient).isTrue();
        }
        assertThat(matcher.conflicts("견과류", "땅콩버터")).as("땅콩은 콩과로 견과류 묶음에 넣지 않음").isFalse();
        assertThat(matcher.conflicts("호두", "아몬드 슬라이스")).isFalse();
        assertThat(matcher.conflicts("잣", "아몬드 슬라이스")).isFalse();
        assertThat(matcher.conflicts("호두", "호두 30g")).isTrue();
        assertThat(matcher.conflicts("아몬드", "호두 30g")).isFalse();
    }

    // 라벨 Registry·함유 표시 Parser의 직접 명칭에는 추가되지 않습니다.
    @Test
    void profileOnlyAllergensAreNotLabelRegistryNames() {
        AllergenRegistry registry = new AllergenRegistry(dictionary());
        for (String term : List.of("사과", "참깨", "아몬드", "견과류", "키위")) {
            assertThat(registry.findDirectName(term)).as(term).isEmpty();
        }
        assertThat(registry.findDirectName("호두").orElseThrow().allergen()).isEqualTo("WALNUT");
    }
}
