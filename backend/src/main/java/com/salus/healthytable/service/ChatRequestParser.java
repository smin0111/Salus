package com.salus.healthytable.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatRequestParser {

    private static final List<String> RECIPE_QUERY_STOPWORDS = List.of(
            "레시피", "요리", "만드는법", "만드는", "만들기", "만들어줘", "알려줘", "추천해줘",
            "추천", "식단", "방법", "조리법", "해줘", "해주세요", "좀", "오늘", "점심", "저녁", "아침",
            "들어간", "넣은", "있는", "없는");
    private static final List<String> RECIPE_CATEGORY_KEYWORDS = List.of(
            "찌개", "국", "탕", "볶음", "구이", "덮밥", "비빔밥", "찜", "조림", "무침", "샐러드", "파스타");

    boolean isRevisionRequest(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("바꿔") || message.contains("수정") || message.contains("변경")
                || message.contains("줄여") || message.contains("늘려") || message.contains("매운");
    }

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

    boolean isSaveToCalendarRequest(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("저장")
                && (message.contains("캘린더") || message.contains("식단") || message.contains("기록"));
    }

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

    String normalizeExcludedIngredientName(String ingredient) {
        if (ingredient == null) {
            return "";
        }
        return ingredient.replaceAll("[^가-힣a-zA-Z0-9]", "")
                .replaceAll("(으로|로|을|를|이|가|은|는|도|만)$", "")
                .trim();
    }

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
