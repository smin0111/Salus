package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatIntentClassifier}의 레시피 요청 판별을 확인합니다.
 */
class ChatIntentClassifierTest {

    private final ChatIntentClassifier classifier = new ChatIntentClassifier();

    // 재료를 빼거나 바꾸는 "말고"·"대신"은 레시피 요청입니다(2026-10-14 "깍두기 레시피 알려줘, 무 말고 …"가 일반 대화로 감).
    @Test
    void ingredientExclusionOrSubstitutionStaysRecipeRequest() {
        assertThat(classifier.classify("깍두기 레시피 알려줘, 무 말고 다른 건 다 괜찮아"))
                .isEqualTo(ChatIntentClassifier.ChatIntent.RECIPE_REQUEST);
        assertThat(classifier.classify("버터 대신 올리브유로 만드는 파스타 레시피"))
                .isEqualTo(ChatIntentClassifier.ChatIntent.RECIPE_REQUEST);
    }

    // 레시피·요리 자체를 돌리는 말은 일반 대화입니다.
    @Test
    void redirectingAwayFromRecipesIsGeneralChat() {
        assertThat(classifier.classify("레시피 말고 그냥 얘기하자")).isEqualTo(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        assertThat(classifier.classify("요리 대신 다른 얘기 하자 레시피는 됐어")).isEqualTo(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        assertThat(classifier.classify("레시피 싫어")).isEqualTo(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
    }

    // 끼니만 말한 요청도 메뉴 추천입니다(2026-10-15 베타: 첫 화면 버튼 "간단한 10분 컷 아침 식사"가 일반 대화로 감).
    @Test
    void mealTimeRequestIsMenuRecommendation() {
        assertThat(classifier.classify("간단한 10분 컷 아침 식사")).isEqualTo(ChatIntentClassifier.ChatIntent.MENU_RECOMMENDATION);
        assertThat(classifier.classify("아침메뉴 뭐가 좋을까")).isEqualTo(ChatIntentClassifier.ChatIntent.MENU_RECOMMENDATION);
        assertThat(classifier.classify("점심 식사로 먹을 거")).isEqualTo(ChatIntentClassifier.ChatIntent.MENU_RECOMMENDATION);
    }
}
