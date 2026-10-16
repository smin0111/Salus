package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.service.allergen.AllergenDictionary;
import com.salus.healthytable.service.allergen.AllergenMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recipe Agent가 알레르겐 "표기"만 지우고 파생 재료가 남은 레시피를 "제외 후 추천"으로 내보내지 않는지 확인합니다.
 *
 * <p>2026-10-09 실전 평가에서 우유 알레르기 사용자에게 두쫀쿠를 "우유: REMOVE, 수정 후 추천"으로 답했지만
 * 버터 60g·탈지분유 12g·화이트초콜릿이 그대로 남았습니다. 단계 문장의 "우유맛스틱" 때문에 표기가 등장해
 * 제거 가능으로 판정됐고, 최종 검증도 "우유" 글자만 확인했습니다.
 */
class AgentAllergyRemovalSafetyTest {

    private final AllergenMatcher matcher = matcher();

    private static AllergenMatcher matcher() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenMatcher(dictionary);
    }

    private final RecipeCandidate dujjonku = new RecipeCandidate(
            "두쫀쿠", "",
            List.of("피스타치오 스프레드 120g", "카다이프면 120g", "마시멜로우 150g", "버터 60g", "탈지분유 12g", "코코아파우더 50g"),
            List.of("버터를 녹인 냄비에 마시멜로우를 녹인다", "우유맛스틱으로 탈지분유를 대체하고 코코아파우더와 섞는다"),
            null, null, null, List.of("마시멜로우", "카다이프면"), List.of(), List.of());

    private UserRecipeContext milkAllergy() {
        return new UserRecipeContext(1L, List.of("우유"), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    // 표기("우유")가 한 번 나와도 파생 재료(버터·탈지분유)가 함께 있으면 제거가 아니라 차단해야 합니다.
    @Test
    void literalMentionWithDerivedIngredientsIsBlockedNotRemoved() {
        PolicyEvaluation evaluation = new AllergyPolicy(matcher).evaluate(dujjonku, milkAllergy());

        assertThat(evaluation.modifications()).isEmpty();
        assertThat(evaluation.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.ingredient()).isEqualTo("우유");
            assertThat(conflict.severity()).isEqualTo(ConflictSeverity.BLOCKING);
        });
    }

    // 표기만 있고 파생 재료가 없으면 그 재료만 빼서 제공하는 기존 동작은 유지합니다.
    @Test
    void literalOnlyIngredientIsStillRemovable() {
        RecipeCandidate latte = new RecipeCandidate("아이스 라떼", "", List.of("에스프레소 2샷", "우유 200ml", "얼음"),
                List.of("에스프레소에 우유와 얼음을 넣는다"), null, null, null, List.of("에스프레소"), List.of("우유"), List.of());

        PolicyEvaluation evaluation = new AllergyPolicy(matcher).evaluate(latte, milkAllergy());

        assertThat(evaluation.modifications()).anySatisfy(modification -> assertThat(modification.ingredient()).isEqualTo("우유"));
    }

    // 개인화 단계가 놓쳐도 최종 검증이 파생 재료까지 보고 막아야 합니다.
    @Test
    void finalValidationCatchesDerivedAllergens() {
        RecipeValidationResult result = new RecipeValidationPipeline(matcher).validate(dujjonku, milkAllergy());

        assertThat(result.valid()).isFalse();
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("우유"));
    }

    // 밀 알레르기에 간장·고추장은 차단하지 않고 성분 표시 확인을 안내합니다(2026-10-14 사용자 결정 "다").
    // 진짜 밀 재료(밀가루)는 그대로 막고, 안내는 막히지 않은 알레르기에만 붙입니다.
    @Test
    void wheatAllergyGetsLabelCheckNoticeForSoySauceButStillBlocksFlour() {
        UserRecipeContext wheat = new UserRecipeContext(1L, List.of("밀"), List.of(), List.of(), List.of(), List.of(), List.of());
        RecipeCandidate jorim = new RecipeCandidate("감자조림", "", List.of("감자 2개", "진간장 4큰술", "물엿 2큰술"),
                List.of("감자를 썬다.", "간장과 물엿을 넣고 조린다."), null, null, null, List.of("감자"), List.of(), List.of());

        PolicyEvaluation evaluation = new AllergyPolicy(matcher).evaluate(jorim, wheat);

        assertThat(evaluation.conflicts()).isEmpty();
        assertThat(evaluation.modifications()).isEmpty();
        assertThat(evaluation.userNotices()).singleElement().asString()
                .startsWith("간장은 제품에 따라 밀 성분이 들어 있을 수 있어요.").contains("성분 표시");

        RecipeCandidate jeon = new RecipeCandidate("감자전", "", List.of("감자 2개", "밀가루 2큰술", "간장 1큰술"),
                List.of("감자를 간다.", "밀가루를 섞어 부친다."), null, null, null, List.of("감자"), List.of(), List.of());
        PolicyEvaluation blocked = new AllergyPolicy(matcher).evaluate(jeon, wheat);
        assertThat(blocked.conflicts()).isNotEmpty();
        assertThat(blocked.userNotices()).noneMatch(notice -> notice.contains("성분 표시"));

        // 다른 알레르기에는 이 안내가 붙지 않습니다.
        UserRecipeContext egg = new UserRecipeContext(1L, List.of("계란"), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(new AllergyPolicy(matcher).evaluate(jorim, egg).userNotices()).isEmpty();
    }
}
