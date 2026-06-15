package com.mychefai.healthytable.service;

import org.springframework.stereotype.Component;

@Component
public class RecipeNormalizer {

    public String normalize(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }

        // 1차 버전: 사용자의 피드백을 반영하여 오탐 요소를 최소화하고 단순 규칙 기반 문자열 치환 수행
        String normalized = message;

        // 불필요한 공통 조사 및 접미사, 요청 서술어 제거
        normalized = normalized.replaceAll("(?i)레시피", "");
        normalized = normalized.replaceAll("(?i)만드는\\s*법", "");
        normalized = normalized.replaceAll("(?i)만드는\\s*방법", "");
        normalized = normalized.replaceAll("(?i)조리법", "");
        normalized = normalized.replaceAll("(?i)끓이는\\s*법", "");
        normalized = normalized.replaceAll("(?i)굽는\\s*법", "");
        normalized = normalized.replaceAll("(?i)알려\\s*줘\\s*봐", "");
        normalized = normalized.replaceAll("(?i)알려줘", "");
        normalized = normalized.replaceAll("(?i)알려주세요", "");
        normalized = normalized.replaceAll("(?i)보여줘", "");
        normalized = normalized.replaceAll("(?i)추천해줘", "");
        normalized = normalized.replaceAll("(?i)어떻게\\s*만들어", "");
        normalized = normalized.replaceAll("(?i)만들어줘", "");
        normalized = normalized.replaceAll("(?i)추천", "");

        // 출처/사람 이름이 붙은 요청과 붙여 쓴 요리명을 검색 친화적으로 정리
        normalized = normalized.replaceAll("([가-힣a-zA-Z0-9]+)의\\s+", "$1 ");
        normalized = normalized.replaceAll("고추장\\s*닭\\s*날개", "고추장 닭날개");
        normalized = normalized.replaceAll("닭\\s*날개", "닭날개");
        normalized = normalized.replaceAll("(고추장)(닭날개)", "$1 $2");
        normalized = normalized.replaceAll("(닭날개)(조림|구이|볶음|튀김|찜)", "$1 $2");

        // 문장 부호 제거
        normalized = normalized.replaceAll("[?。.!]", "");

        // 은/는/이/가/을/를/의/도 등 핵심 명사 뒤에 붙는 단순 조사 제거 (예: "동파육을" -> "동파육", "동파육의" -> "동파육")
        normalized = normalized.replaceAll("(으로|로|을|를|이|가|은|는|에|의|도|만|봐)$", "");

        return normalized.trim();
    }
}
