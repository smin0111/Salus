package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "재료를 빼고 제공해도 되는가"를 가르는 기준을 고정한다.
 *
 * <p>이 구분이 무너지면 크림 파스타에서 "우유"만 지우고 버터·치즈가 남은 레시피를
 * 우유 알레르기 사용자에게 제공하게 된다.
 */
class AllergenRemovabilityTest {

    private final AllergenMatcher matcher = matcher();

    private static AllergenMatcher matcher() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenMatcher(dictionary);
    }

    @Test
    @DisplayName("재료명이 그대로 있으면 분리 가능한 것으로 본다")
    void literalIngredientIsRemovable() {
        List<String> texts = List.of("참치김밥", "참치 1캔", "들깨 1큰술", "김 2장");
        assertThat(matcher.matchesLiterally("들깨", texts)).isTrue();
    }

    @Test
    @DisplayName("파생 재료로만 걸리면 분리 가능하지 않다")
    void derivedOnlyConflictIsNotRemovable() {
        List<String> texts = List.of("크림파스타", "생크림 200ml", "버터 20g", "치즈 30g");

        // 충돌은 잡히지만
        assertThat(matcher.findConflicts(List.of("우유"), texts)).containsExactly("우유");
        // "우유"라는 재료 자체는 없으므로 지울 대상이 없다
        assertThat(matcher.matchesLiterally("우유", texts)).isFalse();
    }

    @Test
    @DisplayName("밀가루만 있는 레시피에서 '밀'은 지울 대상이 아니다")
    void wheatInFlourIsNotRemovable() {
        List<String> texts = List.of("칼국수", "밀가루 200g", "애호박 1개");

        assertThat(matcher.findConflicts(List.of("밀"), texts)).containsExactly("밀");
        assertThat(matcher.matchesLiterally("밀", texts)).isFalse();
    }

    @Test
    @DisplayName("재료명과 파생 재료가 함께 있으면 분리 가능으로 본다")
    void literalPresenceWinsWhenBothAppear() {
        List<String> texts = List.of("우유 200ml", "버터 10g");
        assertThat(matcher.matchesLiterally("우유", texts)).isTrue();
    }
}
