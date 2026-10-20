package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.service.RecipeNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 계획의 요리 이름에 제외 구절이 섞이지 않는지 확인합니다. 섞이면 웹 검색과 "원문에 요리 이름이 있는가" 확인이
 * 모두 실패합니다(2026-10-09 "김치찌개 레시피 알려줘, 돼지고기는 빼줘").
 */
class RecipeRequestPlannerDishNameTest {

    private final DefaultRecipeRequestPlanner planner = new DefaultRecipeRequestPlanner(new RecipeNormalizer());

    @Test
    void exclusionClauseIsRemovedFromDishName() {
        assertThat(planner.plan("김치찌개 레시피 알려줘, 돼지고기는 빼줘", false).dishName()).isEqualTo("김치찌개");
        assertThat(planner.plan("양파 빼고 된장찌개 레시피 알려줘", false).dishName()).isEqualTo("된장찌개");
        assertThat(planner.plan("돼지고기 없는 김치찌개 레시피", false).dishName()).isEqualTo("김치찌개");
    }

    // 인분 표현은 요리 이름에서 뺍니다(2026-10-17 "우유 알레르기 있어. 2인분 파스타 레시피 알려줘"가 "2인분 파스타"로 검색·표시).
    @Test
    void servingsWordsAreRemovedFromDishName() {
        assertThat(planner.plan("2인분 파스타 레시피 알려줘", false).dishName()).isEqualTo("파스타");
        assertThat(planner.plan("샌드위치 레시피 1인분용으로 알려줘", false).dishName()).isEqualTo("샌드위치");
        assertThat(planner.plan("김치찌개 두 인분 레시피", false).dishName()).isEqualTo("김치찌개");
    }

    // 바라는 말은 요리 이름과 검색어에서 뺍니다(2026-10-20 "마늘 보쌈 먹고싶어"로 검색하고 "마늘보쌈 만드는 법" 페이지를 버림).
    @Test
    void desireWordsAreRemovedFromDishNameAndQueries() {
        RecipeResearchPlan plan = planner.plan("마늘 보쌈 먹고싶어 레시피 알려줘", false);
        assertThat(plan.dishName()).isEqualTo("마늘 보쌈");
        assertThat(plan.searchQueries()).containsExactly("마늘 보쌈 레시피", "마늘 보쌈 만드는 법");
        assertThat(planner.plan("마늘 보쌈 해먹고 싶은데 레시피 알려줘", false).dishName()).isEqualTo("마늘 보쌈");
        assertThat(planner.plan("마늘보쌈이 먹고 싶어요. 레시피 알려줘", false).dishName()).isEqualTo("마늘보쌈");
        assertThat(planner.plan("김치찌개가 먹고파 레시피 알려줘", false).dishName()).isEqualTo("김치찌개");
        assertThat(planner.plan("감바스를 만들어 보고 싶어 레시피 알려줘", false).dishName()).isEqualTo("감바스");
        assertThat(planner.plan("레시피 알려줘 된장찌개 끓여 먹고 싶어", false).dishName()).isEqualTo("된장찌개");
    }

    // "이"로 끝나는 요리 이름과 "먹"이 든 요리 이름은 그대로입니다.
    @Test
    void desireRemovalKeepsDishNamesEndingWithI() {
        assertThat(planner.plan("떡볶이 먹고싶어 레시피 알려줘", false).dishName()).isEqualTo("떡볶이");
        assertThat(planner.plan("골뱅이 먹고 싶어 레시피 알려줘", false).dishName()).isEqualTo("골뱅이");
        assertThat(planner.plan("닭꼬치가 먹고 싶어 레시피", false).dishName()).isEqualTo("닭꼬치");
        assertThat(planner.plan("먹물 파스타 레시피 알려줘", false).dishName()).isEqualTo("먹물 파스타");
    }

    @Test
    void dishNameWithoutExclusionIsUnchanged() {
        assertThat(planner.plan("두쫀쿠 레시피 알려줘", false).dishName()).isEqualTo("두쫀쿠");
        assertThat(planner.plan("참치김치찌개 레시피 알려줘", false).dishName()).isEqualTo("참치김치찌개");
    }

    // 제외 재료는 요리 이름에서 빠져도 따로 그대로 뽑혀야 합니다.
    @Test
    void exclusionIsStillExtractedSeparately() {
        assertThat(planner.extractExplicitExclusions("김치찌개 레시피 알려줘, 돼지고기는 빼줘")).contains("돼지고기");
    }

    // "다른 두쫀쿠 레시피"의 "다른"은 요리 이름이 아닙니다. 이름이 남지 않으면 빈 이름(직전 요리의 다른 레시피)입니다.
    @Test
    void anotherWordsAreNotPartOfDishName() {
        assertThat(planner.plan("다른 두쫀쿠 레시피 알려줘", true).dishName()).isEqualTo("두쫀쿠");
        assertThat(planner.plan("두쫀쿠 다른 레시피 알려줘", true).dishName()).isEqualTo("두쫀쿠");
        assertThat(planner.plan("다른 레시피 알려줘", true).dishName()).isEmpty();
    }

    // "다른 짬뽕탕 레시피로 찾아줘봐"를 "짬뽕탕 로 찾아줘"로 읽어 페이지 이름 확인에서 떨어지던 문제(2026-10-12)
    @Test
    void requestTailsAreNotPartOfDishName() {
        assertThat(planner.plan("다른 짬뽕탕 레시피로 찾아줘봐", true).dishName()).isEqualTo("짬뽕탕");
        assertThat(planner.plan("짬뽕탕 찾아줘", true).dishName()).isEqualTo("짬뽕탕");
    }

    // 두 요리를 함께 물으면 첫 요리를 찾고 나머지는 따로 알려 줍니다. 한 요리 이름 안의 "와"는 나누지 않습니다.
    @Test
    void multipleDishesAreSplitButDishNamesWithConnectorsStay() {
        assertThat(planner.plan("참치김밥 레시피랑 유부초밥 레시피 알려줘", true).dishName()).isEqualTo("참치김밥");
        assertThat(planner.otherRequestedDishes("참치김밥 레시피랑 유부초밥 레시피 알려줘")).containsExactly("유부초밥");
        assertThat(planner.otherRequestedDishes("떡볶이랑 순대 레시피 알려줘")).containsExactly("순대");
        assertThat(planner.plan("새우와 브로콜리 볶음 레시피 알려줘", true).dishName()).isEqualTo("새우와 브로콜리 볶음");
        assertThat(planner.otherRequestedDishes("김치찌개 레시피 알려줘")).isEmpty();
    }

    // 2026-10-12 실사용: "김치전 도", "그냥 김치전"을 요리 이름으로 찾다 실패
    @Test
    void leftoverParticlesAndFillersAreNotPartOfDishName() {
        assertThat(planner.plan("오징어 안 들어간 김치전 레시피도 알려줘", true).dishName()).isEqualTo("김치전");
        assertThat(planner.plan("그냥 김치전 레시피 알려줘", true).dishName()).isEqualTo("김치전");
    }

    // 2026-10-12 "명란이 안 들어간"을 "명란이"로 읽어 "저염 명란젓"을 빼지 못함. "오이"는 "오"로 자르지 않음.
    @Test
    void exclusionsDropSubjectParticleButKeepShortNames() {
        assertThat(planner.extractExplicitExclusions("명란이 안 들어간 계란말이 레시피도 알려줘")).containsExactly("명란");
        assertThat(planner.extractExplicitExclusions("당근이 없는 카레 알려줘")).containsExactly("당근");
        assertThat(planner.extractExplicitExclusions("오이 빼줘")).containsExactly("오이");
        // 한 글자 재료와 대화에서 말한 알레르기 재료도 뺍니다(2026-10-14 "나 배 알러지 있어서 배는 빼줘"에 배가 그대로 남음).
        // 알레르기를 말한 구절은 요리 이름에서 뗍니다(2026-10-14 "육회 배 알러지 있어"로 검색해 실패).
        assertThat(DefaultRecipeRequestPlanner.withoutExclusionClause("육회 , 나 배 알러지 있어")).isEqualTo("육회");
        assertThat(DefaultRecipeRequestPlanner.withoutExclusionClause("땅콩 알레르기 있어요 쿠키")).isEqualTo("쿠키");
        assertThat(DefaultRecipeRequestPlanner.withoutExclusionClause("깍두기 , 무 말고 다른 건 다 괜찮아")).isEqualTo("깍두기");
        // 맞장구·지시어와 다시 조르는 말은 요리 이름이 아닙니다(2026-10-14 "그래"로 그래놀라, "알려달라고"로 출처 없음).
        assertThat(DefaultRecipeRequestPlanner.withoutExclusionClause("그래 그럼 이거 말고 다른 로")).isEmpty();
        assertThat(planner.plan("다른 삼겹살 배추찜 레시피 알려달라고", UserRecipeContext.empty(null)).dishName())
                .isEqualTo("삼겹살 배추찜");
        // "랑"으로 끝나는 고유명사는 요리를 나누는 연결어가 아닙니다(2026-10-14 "편스토랑 마늘간장치킨" → "편스토").
        assertThat(DefaultRecipeRequestPlanner.splitDishes("편스토랑 마늘간장치킨")).containsExactly("편스토랑 마늘간장치킨");
        assertThat(DefaultRecipeRequestPlanner.splitDishes("떡볶이랑 순대")).containsExactly("떡볶이", "순대");
        // 방송·사이트 이름은 요리 이름에서 떼고 검색어 앞에만 붙입니다. 사람 이름은 작성자 확인을 위해 남깁니다.
        RecipeResearchPlan show = planner.plan("편스토랑 마늘간장치킨 레시피 알려줘", UserRecipeContext.empty(null));
        assertThat(show.dishName()).isEqualTo("마늘간장치킨");
        assertThat(show.searchQueries()).contains("편스토랑 마늘간장치킨 레시피");
        assertThat(planner.plan("류수영 어남선생 제육볶음 레시피 알려줘", UserRecipeContext.empty(null)).dishName())
                .isEqualTo("류수영 어남선생 제육볶음");
        // 낱말 순서만 다른 제목도 같은 요리입니다(2026-10-14 "류수영 어남선생 제육볶음" 출처 없음).
        assertThat(new RecipeSourceQualityAssessor().dishMatches("류수영 어남선생 제육볶음", "어남선생 류수영 제육볶음 황금 레시피")).isTrue();
        assertThat(RecipeSourceQualityAssessor.containsAllWords("류수영 어남선생 제육볶음", "류수영 된장찌개")).isFalse();
        assertThat(DefaultRecipeRequestPlanner.splitDishes("김밥이랑 떡볶이")).containsExactly("김밥", "떡볶이");
        assertThat(StructuredRecipePageAdapter.isCollectionWithoutDish("240920 편스토랑 레시피 모음", "마늘간장치킨")).isTrue();
        assertThat(StructuredRecipePageAdapter.isCollectionWithoutDish("마늘간장치킨 레시피 모음 3가지", "마늘간장치킨")).isFalse();
        assertThat(planner.extractExplicitExclusions("나 배 알러지 있어서 배는 빼줘")).containsExactly("배");
        assertThat(planner.extractExplicitExclusions("배 알러지 있다고")).containsExactly("배");
        assertThat(planner.extractExplicitExclusions("무 빼고 다시 알려줘")).containsExactly("무");
        assertThat(planner.extractExplicitExclusions("나 땅콩 알레르기 있어")).containsExactly("땅콩");
        // 재료가 아닌 한 글자 말과 "알레르기 없어요"는 빼지 않습니다.
        assertThat(planner.extractExplicitExclusions("좀 빼줘")).isEmpty();
        assertThat(planner.extractExplicitExclusions("우유 알레르기는 없어요")).isEmpty();
        assertThat(planner.extractExplicitExclusions("돼지고기는 빼줘")).containsExactly("돼지고기");
    }
}
