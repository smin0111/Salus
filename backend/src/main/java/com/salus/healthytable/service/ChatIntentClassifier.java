package com.salus.healthytable.service;

import org.springframework.stereotype.Component;

@Component
public class ChatIntentClassifier {

    private static final String[] NEGATIVE_OR_REDIRECT_PHRASES = {
            "안땡겨", "싫어", "말고", "아니", "대신", "별로", "귀찮아"
    };

    private static final String[] RECIPE_KEYWORDS = {
            "레시피", "만드는법", "만드는방법", "조리법", "어떻게만들어", "끓이는법", "굽는법"
    };

    public enum ChatIntent {
        RECIPE_REQUEST,       // 레시피/조리법 직접 요청
        MENU_RECOMMENDATION,  // 메뉴 추천 요청
        GENERAL_CHAT,         // 잡담 및 일반 질답
        COOKING_QUESTION      // 요리 관련 일반 상식 질문
    }

    public ChatIntent classify(String message) {
        if (message == null || message.isBlank()) {
            return ChatIntent.GENERAL_CHAT;
        }

        // 공백 제거 및 소문자 정형화
        String cleanMsg = message.replaceAll("\\s+", "").toLowerCase();

        // 1. 레시피 요청 판별
        if (containsAny(cleanMsg, RECIPE_KEYWORDS)) {
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

    private boolean containsAny(String message, String[] keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
