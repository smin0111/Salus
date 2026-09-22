package com.salus.healthytable.service;

import org.springframework.stereotype.Component;

/**
 * 채팅 메시지의 의도(레시피 요청, 메뉴 추천, 요리 질문, 일반 대화)를 키워드 규칙으로 분류합니다.
 *
 * LLM을 부르지 않는 단순 규칙 기반이라 빠르지만, 표현이 다양하면 틀릴 수 있습니다.
 * 분류 결과에 따라 ChatService가 레시피 생성 파이프라인을 탈지, 일반 대화로 답할지 결정합니다.
 */
@Component
public class ChatIntentClassifier {

    // "레시피 말고", "그건 싫어"처럼 요청을 거절하거나 방향을 바꾸는 표현
    private static final String[] NEGATIVE_OR_REDIRECT_PHRASES = {
            "안땡겨", "싫어", "말고", "아니", "대신", "별로", "귀찮아"
    };

    // 레시피를 직접 요청한다고 볼 수 있는 키워드 (공백을 제거한 형태로 비교합니다)
    private static final String[] RECIPE_KEYWORDS = {
            "레시피", "만드는법", "만드는방법", "조리법", "어떻게만들어", "끓이는법", "굽는법",
            "에이드", "주스", "스무디", "화채", "빙수"
    };

    // 채팅 의도 종류
    public enum ChatIntent {
        RECIPE_REQUEST,       // 레시피/조리법 직접 요청
        MENU_RECOMMENDATION,  // 메뉴 추천 요청
        GENERAL_CHAT,         // 잡담 및 일반 질답
        COOKING_QUESTION      // 요리 관련 일반 상식 질문
    }

    /**
     * 메시지를 분류합니다. 검사 순서(레시피 → 메뉴 추천 → 요리 질문 → 일반 대화)가 곧 우선순위입니다.
     */
    public ChatIntent classify(String message) {
        if (message == null || message.isBlank()) {
            return ChatIntent.GENERAL_CHAT;
        }

        // 공백 제거 및 소문자 정형화
        String cleanMsg = message.replaceAll("\\s+", "").toLowerCase();

        // 1. 레시피 요청 판별
        if (containsAny(cleanMsg, RECIPE_KEYWORDS)) {
            // 레시피 키워드가 있어도 부정/전환 표현이 함께 있으면 레시피 생성 대신 일반 대화로 처리합니다.
            if (containsAny(cleanMsg, NEGATIVE_OR_REDIRECT_PHRASES)) {
                return ChatIntent.GENERAL_CHAT;
            }
            return ChatIntent.RECIPE_REQUEST;
        }

        // 2. 메뉴 추천 판별
        if (cleanMsg.contains("추천") || cleanMsg.contains("뭐먹지")
                || cleanMsg.contains("점심메뉴") || cleanMsg.contains("저녁메뉴") || cleanMsg.contains("식단")) {
            return ChatIntent.MENU_RECOMMENDATION;
        }

        // 3. 요리 일반 질문 판별
        if (cleanMsg.contains("왜") || cleanMsg.contains("어떻게") || cleanMsg.contains("보관")
                || cleanMsg.contains("차이") || cleanMsg.contains("대체")) {
            return ChatIntent.COOKING_QUESTION;
        }

        return ChatIntent.GENERAL_CHAT;
    }

    // 키워드 중 하나라도 메시지에 포함되어 있으면 true입니다.
    private boolean containsAny(String message, String[] keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
