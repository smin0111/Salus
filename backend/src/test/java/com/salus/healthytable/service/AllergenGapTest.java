package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.service.allergen.AllergenDictionary;
import com.salus.healthytable.service.allergen.AllergenMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 현재 알레르기 판정 로직의 구멍을 드러내는 테스트.
 * 수정 전에는 실패해야 정상이다.
 */
class AllergenGapTest {

    private final ChatSafetyContextService service =
            new ChatSafetyContextService(null, null, null, matcher());

    private static AllergenMatcher matcher() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenMatcher(dictionary);
    }

    private ChatSafetyContextService.SafetyContext context(String... allergies) {
        return new ChatSafetyContextService.SafetyContext(
                List.of(allergies), List.of(), List.of(), List.of(), List.of(), true);
    }

    private Recipe recipe(String title, String... ingredients) {
        Recipe recipe = new Recipe();
        recipe.setTitle(title);
        recipe.setIngredients(List.of(ingredients));
        recipe.setSteps(List.of("재료를 손질한다", "조리한다"));
        return recipe;
    }

    @Test
    @DisplayName("1음절 알레르겐(밀)이 차단되어야 한다")
    void singleSyllableWheatIsBlocked() {
        List<String> conflicts = service.findAllergyConflicts(
                context("밀"), "칼국수", recipe("칼국수", "밀가루 200g", "육수 500ml"), "칼국수 레시피");
        assertThat(conflicts).isNotEmpty();
    }

    @Test
    @DisplayName("1음절 알레르겐(콩)이 차단되어야 한다")
    void singleSyllableSoyIsBlocked() {
        List<String> conflicts = service.findAllergyConflicts(
                context("콩"), "된장찌개", recipe("된장찌개", "콩 100g", "두부 1모"), "된장찌개 레시피");
        assertThat(conflicts).isNotEmpty();
    }

    @Test
    @DisplayName("파생 재료(우유→버터·치즈)가 차단되어야 한다")
    void derivedDairyIsBlocked() {
        List<String> conflicts = service.findAllergyConflicts(
                context("우유"), "크림파스타",
                recipe("크림파스타", "생크림 200ml", "버터 20g", "파르메산 치즈 30g"),
                "크림파스타 레시피");
        assertThat(conflicts).isNotEmpty();
    }

    @Test
    @DisplayName("파생 재료(계란→마요네즈)가 차단되어야 한다")
    void derivedEggIsBlocked() {
        List<String> conflicts = service.findAllergyConflicts(
                context("계란"), "감자샐러드",
                recipe("감자샐러드", "감자 3개", "마요네즈 3큰술"),
                "감자샐러드 레시피");
        assertThat(conflicts).isNotEmpty();
    }

    @Test
    @DisplayName("사용자가 '빼고'라고 써도 등록된 알레르기는 차단되어야 한다")
    void registeredAllergyIsNotBypassedByExclusionPhrase() {
        List<String> conflicts = service.findAllergyConflicts(
                context("땅콩"), "팟타이",
                recipe("팟타이", "쌀국수 200g", "땅콩 분태 20g"),
                "땅콩 빼고 팟타이 만들어줘");
        assertThat(conflicts).isNotEmpty();
    }

    @Test
    @DisplayName("오탐 방지: '게' 알레르기가 '고등어조림'에 걸리면 안 된다")
    void crabAllergyDoesNotFalselyMatchMackerel() {
        List<String> conflicts = service.findAllergyConflicts(
                context("게"), "고등어조림", recipe("고등어조림", "고등어 2마리", "무 1개"), "고등어조림 레시피");
        assertThat(conflicts).isEmpty();
    }
}
