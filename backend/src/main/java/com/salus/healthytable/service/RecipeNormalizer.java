package com.salus.healthytable.service;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사용자 메시지에서 "레시피", "알려줘" 같은 요청 표현과 조사를 떼어 내 검색용 요리 이름만 남기는 도우미입니다.
 * 예) "고추장닭날개 레시피 알려줘" → "고추장 닭날개"
 */
@Component
public class RecipeNormalizer {

    // "먹고싶어", "해먹고 싶은데", "끓여 먹고 싶어", "만들어 보고 싶어", "먹고파"처럼 바라는 말입니다. 요리 이름에 남으면 검색어와
    // "제목·본문에 요리 이름이 있는가" 확인이 모두 실패합니다(2026-10-20 "마늘 보쌈 먹고싶어 레시피 알려줘":
    // "마늘보쌈 만드는 법" 페이지를 찾고도 이름이 다르다며 버림). "먹고 파스타"의 "파"는 붙여 쓴 "먹고파"만 봅니다.
    private static final Pattern DESIRE = Pattern.compile(
            "\\s*(?:해|만들어|요리해|끓여|구워|볶아|튀겨|부쳐)?\\s*(?:먹고|먹구|만들고|만들어\\s*보고|해\\s*보고)(?:\\s*싶|파)[가-힣]*");

    public String normalize(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }

        // 1차 버전: 사용자의 피드백을 반영하여 오탐 요소를 최소화하고 단순 규칙 기반 문자열 치환 수행
        String normalized = withoutDesire(message);

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

    // 바라는 말을 지우고, 그 앞 낱말에 붙은 조사("보쌈이", "김치찌개가", "보쌈을", "김치찌개를")도 뗍니다.
    private static String withoutDesire(String message) {
        Matcher matcher = DESIRE.matcher(message);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(message, last, matcher.start());
            stripTrailingParticle(out);
            out.append(' ');
            last = matcher.end();
        }
        return out.append(message.substring(last)).toString();
    }

    // 받침 있는 낱말 뒤 "이·을", 받침 없는 낱말 뒤 "가·를"만 조사로 봅니다. "떡볶이·골뱅이·올갱이·팽이"처럼
    // "이"로 끝나는 요리·재료 이름은 둡니다.
    private static void stripTrailingParticle(StringBuilder text) {
        while (text.length() > 0 && Character.isWhitespace(text.charAt(text.length() - 1))) {
            text.setLength(text.length() - 1);
        }
        int wordStart = text.length();
        while (wordStart > 0 && !Character.isWhitespace(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        String word = text.substring(wordStart);
        if (word.length() < 3 || word.matches(".*(?:볶|뱅|갱|팽)이")) {
            return;
        }
        char particle = word.charAt(word.length() - 1);
        boolean batchim = hasBatchim(word.charAt(word.length() - 2));
        if ((batchim && (particle == '이' || particle == '을')) || (!batchim && (particle == '가' || particle == '를'))) {
            text.setLength(text.length() - 1);
        }
    }

    private static boolean hasBatchim(char syllable) {
        return syllable >= '가' && syllable <= '힣' && (syllable - '가') % 28 != 0;
    }
}
