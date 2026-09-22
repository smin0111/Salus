package com.salus.healthytable.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 사용자 채팅 메시지를 키워드/정규식 규칙으로 해석하는 도우미입니다.
 *
 * "수정 요청인지", "상세 설명 요청인지", "어떤 재료를 빼 달라는지" 같은 판단을 LLM 없이 빠르게 합니다.
 * 규칙 기반이라 모든 표현을 이해하지는 못하므로, 판단이 애매하면 false/빈 목록을 반환하는 쪽으로 작성되어 있습니다.
 * 메서드 접근 제어자가 없는(package-private) 것은 같은 service 패키지에서만 쓰기 위해서입니다.
 */
@Service
public class ChatRequestParser {

    // 검색 키워드를 만들 때 제거할 불용어(요청 표현, 시간대 등)
    private static final List<String> RECIPE_QUERY_STOPWORDS = List.of(
            "레시피", "요리", "만드는법", "만드는", "만들기", "만들어줘", "알려줘", "추천해줘",
            "추천", "식단", "방법", "조리법", "해줘", "해주세요", "좀", "오늘", "점심", "저녁", "아침",
            "들어간", "넣은", "있는", "없는");
    // 요리 종류를 나타내는 키워드
    private static final List<String> RECIPE_CATEGORY_KEYWORDS = List.of(
            "찌개", "국", "탕", "볶음", "구이", "덮밥", "비빔밥", "찜", "조림", "무침", "샐러드", "파스타");

    // "바꿔", "수정", "줄여" 같은 표현이 있으면 기존 레시피 수정 요청으로 봅니다.
    boolean isRevisionRequest(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("바꿔") || message.contains("수정") || message.contains("변경")
                || message.contains("줄여") || message.contains("늘려") || message.contains("매운");
    }

    /**
     * "우유 안 들어간 다른 레시피"처럼 특정 재료를 뺀 다른 버전을 요청하는지 판단합니다.
     * 대안 요청 표현 + 제외 표현이 모두 있고, 실제로 뺄 재료 이름을 추출할 수 있어야 true입니다.
     */
    boolean isAlternativeExclusionRecipeRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.replaceAll("\\s+", "");
        boolean asksAlternative = normalized.contains("또다른")
                || normalized.contains("다른")
                || normalized.contains("버전")
                || normalized.contains("레시피");
        boolean excludesIngredient = normalized.contains("안들어간")
                || normalized.contains("없는")
                || normalized.contains("빼고")
                || normalized.contains("제외")
                || normalized.contains("말고");
        return asksAlternative && excludesIngredient && !extractExcludedIngredients(message).isEmpty();
    }

    /**
     * 직전 레시피를 "더 자세히" 설명해 달라는 후속 요청인지 판단합니다.
     * 새 요리 이름이 들어간 긴 레시피 요청처럼 보이면 후속 요청이 아닌 것으로 봅니다.
     */
    boolean isRecipeDetailFollowUp(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.replaceAll("\\s+", "");
        boolean asksForMoreDetail = normalized.contains("자세하게")
                || normalized.contains("자세히")
                || normalized.contains("상세하게")
                || normalized.contains("상세히")
                || normalized.contains("더알려")
                || normalized.contains("더자세")
                || normalized.contains("조금더")
                || normalized.contains("풀어서")
                || normalized.contains("초보자")
                || normalized.contains("왜하는지")
                || normalized.contains("조리법을더")
                || normalized.contains("만드는법을더");
        boolean hasNewFoodName = normalized.contains("레시피")
                && normalized.length() > 18
                && !normalized.startsWith("조리법")
                && !normalized.startsWith("만드는법");
        return asksForMoreDetail && !hasNewFoodName;
    }

    // "두부 대신 닭가슴살 써도 돼?"처럼 재료 대체 표현과 레시피 수정 의도가 함께 있으면 true입니다.
    boolean isIngredientSubstitutionFollowUp(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.replaceAll("\\s+", "");
        boolean substitution = normalized.contains("대신")
                || normalized.contains("대체")
                || normalized.contains("바꿔")
                || normalized.contains("바꾸")
                || normalized.contains("교체");
        boolean asksRecipeRevision = normalized.contains("어때")
                || normalized.contains("가능")
                || normalized.contains("될까")
                || normalized.contains("되나")
                || normalized.contains("써도")
                || normalized.contains("사용")
                || normalized.contains("넣어")
                || normalized.contains("레시피")
                || normalized.contains("만들");
        return substitution && asksRecipeRevision;
    }

    // 수정 요청이거나, 물음표/대체/칼로리/시간 등 기존 레시피에 대한 질문으로 보이면 true입니다.
    boolean isRevisionOrQuestion(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        if (isRevisionRequest(message)) {
            return true;
        }

        String normalized = message.replaceAll("\\s+", "");
        return normalized.contains("?")
                || normalized.contains("야?")
                || normalized.contains("까?")
                || normalized.contains("요?")
                || normalized.contains("대신")
                || normalized.contains("대체")
                || normalized.contains("빼고")
                || normalized.contains("제외")
                || normalized.contains("추가")
                || normalized.contains("넣어")
                || normalized.contains("들어")
                || normalized.contains("칼로리")
                || normalized.contains("열량")
                || normalized.contains("영양")
                || normalized.contains("시간")
                || normalized.contains("분걸려")
                || normalized.contains("어려워")
                || normalized.contains("쉬워");
    }

    // "저장"과 함께 캘린더/식단/기록이 언급되면 식단 캘린더 저장 요청으로 봅니다.
    boolean isSaveToCalendarRequest(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("저장")
                && (message.contains("캘린더") || message.contains("식단") || message.contains("기록"));
    }

    // 요리/레시피/음식 종류 관련 단어가 하나라도 있으면 레시피 관련 요청으로 넓게 판단합니다.
    boolean isRecipeRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.replaceAll("\\s+", "");
        return normalized.contains("요리")
                || normalized.contains("레시피")
                || normalized.contains("만들")
                || normalized.contains("추천")
                || normalized.contains("식단")
                || normalized.contains("먹")
                || normalized.contains("카레")
                || normalized.contains("찌개")
                || normalized.contains("국")
                || normalized.contains("탕")
                || normalized.contains("볶음")
                || normalized.contains("구이")
                || normalized.contains("덮밥")
                || normalized.contains("파스타")
                || normalized.contains("샐러드")
                || normalized.contains("냉장고");
    }

    /**
     * 메시지에서 빼 달라는 재료 이름을 추출합니다.
     * 예) "땅콩 빼고", "우유가 안 들어간", "새우는 말고" → [땅콩, 우유, 새우]
     */
    List<String> extractExcludedIngredients(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        String normalized = message.replaceAll("[,，.?!]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        java.util.Set<String> ingredients = new java.util.LinkedHashSet<>();
        List<java.util.regex.Pattern> patterns = List.of(
                java.util.regex.Pattern.compile("([^\\s,]+?)(?:이|가)?\\s*안\\s*들어간"),
                java.util.regex.Pattern.compile("([^\\s,]+?)(?:이|가)?\\s*없는"),
                java.util.regex.Pattern.compile("([^\\s,]+?)(?:을|를)?\\s*빼고"),
                java.util.regex.Pattern.compile("([^\\s,]+?)(?:을|를)?\\s*제외"),
                java.util.regex.Pattern.compile("([^\\s,]+?)(?:은|는)?\\s*말고")
        );

        for (java.util.regex.Pattern pattern : patterns) {
            java.util.regex.Matcher matcher = pattern.matcher(normalized);
            while (matcher.find()) {
                String ingredient = normalizeExcludedIngredientName(matcher.group(1));
                if (isLikelyIngredientName(ingredient)) {
                    ingredients.add(ingredient);
                }
            }
        }

        // 띄어쓰기 없이 붙여 쓴 경우("우유안들어간레시피")를 위한 보조 규칙입니다.
        if (ingredients.isEmpty()) {
            String compact = message.replaceAll("\\s+", "");
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(.+?)(?:이|가)?안들어간")
                    .matcher(compact);
            if (matcher.find()) {
                String ingredient = normalizeExcludedIngredientName(matcher.group(1)
                        .replaceAll(".*(알려줘|알려주세요|레시피|다른|또다른|버전)", ""));
                if (isLikelyIngredientName(ingredient)) {
                    ingredients.add(ingredient);
                }
            }
        }

        return new ArrayList<>(ingredients);
    }

    // "A 대신 B", "A를 대체해서 B" 형태에서 (A → B) 대체 쌍을 추출합니다.
    List<RecipeGenerationRequest.IngredientSubstitution> extractIngredientSubstitutions(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        String normalized = message.replaceAll("[,，.?!]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        List<RecipeGenerationRequest.IngredientSubstitution> substitutions = new ArrayList<>();
        List<java.util.regex.Pattern> patterns = List.of(
                java.util.regex.Pattern.compile("([^\\s]+?)(?:을|를|은|는)?\\s*대신\\s*([^\\s]+)"),
                java.util.regex.Pattern.compile("([^\\s]+?)(?:을|를|은|는)?\\s*대체(?:해서|로)?\\s*([^\\s]+)")
        );
        for (java.util.regex.Pattern pattern : patterns) {
            java.util.regex.Matcher matcher = pattern.matcher(normalized);
            while (matcher.find()) {
                String from = normalizeExcludedIngredientName(matcher.group(1));
                String to = normalizeExcludedIngredientName(matcher.group(2));
                if (isLikelyIngredientName(from) && isLikelyIngredientName(to)) {
                    substitutions.add(new RecipeGenerationRequest.IngredientSubstitution(from, to));
                }
            }
        }
        return substitutions;
    }

    // 기호를 제거하고 끝에 붙은 조사(을/를/이/가 등)를 떼어 재료 이름만 남깁니다.
    String normalizeExcludedIngredientName(String ingredient) {
        if (ingredient == null) {
            return "";
        }
        return ingredient.replaceAll("[^가-힣a-zA-Z0-9]", "")
                .replaceAll("(으로|로|을|를|이|가|은|는|도|만)$", "")
                .trim();
    }

    // 너무 길거나 "레시피", "다른" 같은 요청 표현이 섞인 값은 재료 이름이 아닌 것으로 봅니다.
    boolean isLikelyIngredientName(String ingredient) {
        if (ingredient == null || ingredient.isBlank()) {
            return false;
        }
        String compact = ingredient.replaceAll("\\s+", "");
        if (compact.length() > 20) {
            return false;
        }
        return !(compact.contains("레시피")
                || compact.contains("다른")
                || compact.contains("또")
                || compact.contains("있으면")
                || compact.contains("알려"));
    }

    /**
     * 메시지에서 레시피 검색용 키워드 후보를 만듭니다.
     * 전체 문장에서 불용어를 뺀 값, 단어별 값, 조사를 뗀 값, 흔한 오타 교정값(찌게→찌개)을 모두 후보로 넣습니다.
     */
    List<String> extractRecipeKeywords(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }

        String compact = message.replaceAll("[^가-힣a-zA-Z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        List<String> keywords = new ArrayList<>();
        addKeywordWithVariants(keywords, removeRecipeStopwords(compact));

        for (String token : compact.split("\\s+")) {
            String cleaned = removeRecipeStopwords(token);
            addKeywordWithVariants(keywords, cleaned);
            addKeywordWithVariants(keywords, stripCommonRecipeSuffix(cleaned));
        }

        return keywords;
    }

    String removeRecipeStopwords(String text) {
        String cleaned = text;
        for (String stopword : RECIPE_QUERY_STOPWORDS) {
            cleaned = cleaned.replace(stopword, " ");
        }
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    String stripCommonRecipeSuffix(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.replaceAll("(으로|로|을|를|이|가|은|는|에|의|도|만)$", "").trim();
    }

    void addKeywordWithVariants(List<String> keywords, String keyword) {
        addKeyword(keywords, keyword);
        addKeyword(keywords, normalizeCommonRecipeTypo(keyword));
    }

    String normalizeCommonRecipeTypo(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword
                .replace("찌게", "찌개")
                .replace("된장찌게", "된장찌개")
                .replace("김치찌게", "김치찌개")
                .replace("순두부찌게", "순두부찌개")
                .replace("부대찌게", "부대찌개");
    }

    // 2글자 미만, 불용어, 이미 들어간 키워드는 추가하지 않습니다.
    void addKeyword(List<String> keywords, String keyword) {
        if (keyword == null) {
            return;
        }
        String cleaned = keyword.replaceAll("\\s+", "").trim();
        if (cleaned.length() < 2 || RECIPE_QUERY_STOPWORDS.contains(cleaned) || keywords.contains(cleaned)) {
            return;
        }
        keywords.add(cleaned);
    }

}
