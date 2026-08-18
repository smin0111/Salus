package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeReplyParser {

    private static final List<String> RECIPE_CATEGORY_KEYWORDS = List.of(
            "찌개", "국", "탕", "볶음", "구이", "덮밥", "비빔밥", "찜", "조림", "무침", "샐러드", "파스타");

    private final RecipeResponseSanitizer recipeResponseSanitizer;

    String extractRecipeTitle(String text) {
        if (text == null || text.isBlank()) {
            return "AI 추천 식단";
        }
        String firstLine = text.lines()
                .map(line -> line.replaceAll("[#*`]", "").trim())
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("AI 추천 식단");
        firstLine = firstLine.replace("요리 이름:", "").replace("메뉴:", "").trim();
        return firstLine.length() > 40 ? firstLine.substring(0, 40) + "..." : firstLine;
    }

    String extractFollowUpRecipeTitle(String text) {
        if (text == null || text.isBlank()) {
            return "AI 추천 식단";
        }
        String firstLine = text.lines()
                .map(line -> line.replaceAll("[#*`]", "").trim())
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("AI 추천 식단");

        firstLine = firstLine
                .replace("요리 이름:", "")
                .replace("메뉴:", "")
                .replaceFirst("\\s*레시피입니다\\.?\\s*$", "")
                .replaceFirst("\\s*레시피\\s*입니다\\.?\\s*$", "")
                .replaceFirst("\\s*조리\\s*시간:.*$", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (firstLine.isBlank()) {
            return "AI 추천 식단";
        }

        String[] tokens = firstLine.split(" ");
        int maxPrefix = Math.min(tokens.length, 5);
        for (int size = maxPrefix; size >= 1; size--) {
            String prefix = joinTokens(tokens, size);
            String lastToken = tokens[size - 1];
            if (looksLikeDishTitleToken(lastToken)) {
                return prefix;
            }
        }

        for (int size = 1; size <= maxPrefix; size++) {
            String prefix = joinTokens(tokens, size);
            String rest = firstLine.length() > prefix.length()
                    ? firstLine.substring(prefix.length()).trim()
                    : "";
            if (!rest.isBlank() && rest.contains(prefix)) {
                return prefix;
            }
        }

        if (tokens.length >= 2 && isShortForeignTitleToken(tokens[0]) && isShortForeignTitleToken(tokens[1])) {
            return tokens[0] + " " + tokens[1];
        }
        return tokens[0];
    }

    String joinTokens(String[] tokens, int size) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < size && i < tokens.length; i++) {
            values.add(tokens[i]);
        }
        return String.join(" ", values).trim();
    }

    boolean looksLikeDishTitleToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String normalized = token.replaceAll("[^가-힣a-zA-Z0-9]", "");
        if (normalized.isBlank()) {
            return false;
        }
        for (String keyword : RECIPE_CATEGORY_KEYWORDS) {
            if (normalized.endsWith(keyword)) {
                return true;
            }
        }
        return normalized.endsWith("밥")
                || normalized.endsWith("면")
                || normalized.endsWith("죽")
                || normalized.endsWith("튀김")
                || normalized.endsWith("전")
                || normalized.endsWith("김치")
                || normalized.endsWith("스테이크")
                || normalized.endsWith("웰링턴")
                || normalized.endsWith("카레")
                || normalized.endsWith("커리")
                || normalized.endsWith("피자")
                || normalized.endsWith("라면")
                || normalized.endsWith("국수")
                || normalized.endsWith("수프")
                || normalized.endsWith("스프")
                || normalized.endsWith("샌드위치")
                || normalized.endsWith("버거");
    }

    boolean isShortForeignTitleToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String normalized = token.replaceAll("[^가-힣a-zA-Z0-9]", "");
        return normalized.length() >= 2 && normalized.length() <= 12;
    }

    Integer extractCalories(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)\\s*(kcal|칼로리)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    String quoteJson(String text) {
        if (text == null) {
            return "\"\"";
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    Recipe parseRecipeFromReply(String title, String reply) {
        try {
            if (reply == null || reply.isBlank()) {
                return null;
            }
            if (!reply.contains("[재료]") || !reply.contains("[조리 순서]")) {
                return null;
            }

            Recipe recipe = new Recipe();
            recipe.setTitle(title);

            String[] lines = reply.split("\n");
            StringBuilder descriptionBuilder = new StringBuilder();
            List<String> ingredients = new ArrayList<>();
            List<String> steps = new ArrayList<>();
            Integer calories = null;
            Integer difficulty = 2; // 보통 기본값
            Integer cookingTime = null;

            boolean inIngredients = false;
            boolean inSteps = false;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                // 조리 시간, 열량, 난이도 추출
                if (trimmed.startsWith("조리 시간:") || trimmed.contains("열량:") || trimmed.contains("난이도:")) {
                    java.util.regex.Matcher timeMatcher = java.util.regex.Pattern.compile("조리\\s*시간:\\s*(\\d+)분").matcher(trimmed);
                    if (timeMatcher.find()) {
                        cookingTime = Integer.parseInt(timeMatcher.group(1));
                    }
                    java.util.regex.Matcher calMatcher = java.util.regex.Pattern.compile("열량:\\s*(\\d+)kcal").matcher(trimmed);
                    if (calMatcher.find()) {
                        calories = Integer.parseInt(calMatcher.group(1));
                    }
                    java.util.regex.Matcher diffMatcher = java.util.regex.Pattern.compile("난이도:\\s*(\\S+)").matcher(trimmed);
                    if (diffMatcher.find()) {
                        String diffStr = diffMatcher.group(1);
                        if (diffStr.contains("쉬움") || diffStr.contains("1")) {
                            difficulty = 1;
                        } else if (diffStr.contains("어려움") || diffStr.contains("어려") || diffStr.contains("3")) {
                            difficulty = 3;
                        } else {
                            difficulty = 2;
                        }
                    }
                    continue;
                }

                // 섹션 구분자 체크
                if (trimmed.equals("[재료]")) {
                    inIngredients = true;
                    inSteps = false;
                    continue;
                }
                if (trimmed.equals("[조리 순서]")) {
                    inIngredients = false;
                    inSteps = true;
                    continue;
                }
                if (trimmed.startsWith("[건강 주의]") || trimmed.startsWith("위 내용은 Salus") || trimmed.contains("Salus AI 가이드")) {
                    inIngredients = false;
                    inSteps = false;
                    continue;
                }
                if (inSteps && recipeResponseSanitizer.isNonCookingStepNote(trimmed)) {
                    inSteps = false;
                    continue;
                }

                // 데이터 수집
                if (inIngredients) {
                    if (trimmed.startsWith("-")) {
                        ingredients.add(trimmed.substring(1).trim());
                    } else if (!trimmed.startsWith("[")) {
                        ingredients.add(trimmed);
                    }
                }
                if (inSteps) {
                    if (java.util.regex.Pattern.compile("^\\d+\\s*\\.\\s*").matcher(trimmed).find()) {
                        steps.add(trimmed.replaceFirst("^\\d+\\s*\\.\\s*", ""));
                    } else if (!trimmed.startsWith("[")) {
                        steps.add(trimmed);
                    }
                }

                // 소개글 수집 (섹션이 활성화되지 않은 상태의 텍스트)
                if (!inIngredients && !inSteps && !trimmed.startsWith("조리 시간:")
                        && !trimmed.endsWith("레시피입니다.") && !trimmed.endsWith("레시피입니다")
                        && !trimmed.equals(title)
                        && !trimmed.startsWith("[건강") && !trimmed.startsWith("-")) {
                    descriptionBuilder.append(trimmed).append(" ");
                }
            }

            recipe.setDescription(descriptionBuilder.toString().trim());
            recipe.setIngredients(ingredients);
            recipe.setSteps(steps);
            recipe.setCalories(calories);
            recipe.setDifficulty(difficulty);
            recipe.setCookingTime(cookingTime);

            return recipe;
        } catch (Exception e) {
            log.warn("[RecipeReplyParser] category=RECIPE_REPLY_PARSE_EXCEPTION, exceptionClass={}", e.getClass().getSimpleName());
            return null;
        }
    }

}
