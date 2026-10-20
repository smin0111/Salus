package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 채팅 답변 텍스트(RecipeReplyFormatter 형식)에서 제목, 열량, 재료, 조리 순서 등을 다시 읽어 내는 파서입니다.
 * 후속 요청(재료 제외, 캘린더 저장)에서 직전 레시피 텍스트를 구조화 데이터로 되돌릴 때 사용합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeReplyParser {

    private static final List<String> RECIPE_CATEGORY_KEYWORDS = List.of(
            "찌개", "국", "탕", "볶음", "구이", "덮밥", "비빔밥", "찜", "조림", "무침", "샐러드", "파스타");

    private final RecipeResponseSanitizer recipeResponseSanitizer;

    // 답변의 첫 줄(마크다운 기호 제거)을 제목으로 사용하고, 40자를 넘으면 자릅니다.
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

    /**
     * 후속 요청에 사용할 "요리 이름만" 추출합니다.
     * 예) "된장찌개 2인분 레시피입니다." → "된장찌개"
     * 1) 앞 단어 최대 5개 중 요리 이름처럼 끝나는(찌개, 볶음, 밥 등) 가장 긴 접두어
     * 2) 같은 접두어가 뒤에 반복되는 경우 그 접두어
     * 3) 짧은 외국어 요리명 두 단어, 그래도 없으면 첫 단어
     */
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

    // 앞에서부터 size개의 단어를 공백으로 이어 붙입니다.
    String joinTokens(String[] tokens, int size) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < size && i < tokens.length; i++) {
            values.add(tokens[i]);
        }
        return String.join(" ", values).trim();
    }

    // 단어가 요리 종류를 나타내는 말(찌개, 볶음, 밥, 스테이크 등)로 끝나면 true입니다.
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

    // 2~12자의 짧은 단어인지 확인합니다(외국 요리명 두 단어 조합 판단용).
    boolean isShortForeignTitleToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String normalized = token.replaceAll("[^가-힣a-zA-Z0-9]", "");
        return normalized.length() >= 2 && normalized.length() <= 12;
    }

    // 텍스트에서 처음 나오는 "숫자 + kcal/칼로리"를 열량으로 읽습니다. 없으면 null입니다.
    Integer extractCalories(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)\\s*(kcal|칼로리)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    // 문자열을 JSON 문자열 리터럴("...")로 만듭니다. 역슬래시, 큰따옴표, 줄바꿈을 이스케이프합니다.
    String quoteJson(String text) {
        if (text == null) {
            return "\"\"";
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /**
     * 레시피 답변 전체를 Recipe 객체로 파싱합니다.
     * [재료]와 [조리 순서] 섹션이 모두 있어야 하며, 없거나 파싱 중 오류가 나면 null을 반환합니다.
     * 한 줄씩 읽으면서 "지금 어느 섹션 안에 있는지"(inIngredients/inSteps)를 상태로 기억하는 방식입니다.
     */
    Recipe parseRecipeFromReply(String title, String reply) {
        try {
            if (reply == null || reply.isBlank()) {
                return null;
            }
            java.util.regex.Matcher ingredientHeaderMatcher = java.util.regex.Pattern
                    .compile("\\[재료(?:\\s*-\\s*(\\d+)인분)?\\]")
                    .matcher(reply);
            if (!ingredientHeaderMatcher.find() || !reply.contains("[조리 순서]")) {
                return null;
            }

            Recipe recipe = new Recipe();
            recipe.setTitle(title);

            String[] lines = reply.split("\n");
            StringBuilder descriptionBuilder = new StringBuilder();
            List<String> ingredients = new ArrayList<>();
            List<String> steps = new ArrayList<>();
            Integer servings = ingredientHeaderMatcher.group(1) == null
                    ? null
                    : Integer.parseInt(ingredientHeaderMatcher.group(1));
            Integer calories = null;
            Integer caloriesPerServing = null;
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
                    java.util.regex.Matcher calMatcher = java.util.regex.Pattern
                            // "1인분당"이라고 명시된 경우에만 1인분 열량으로 저장합니다(기준 인분이 불명확한 값을 추측하지 않음).
                            .compile("열량:\\s*(?:(1인분당)\\s*약\\s*)?(\\d+)kcal")
                            .matcher(trimmed);
                    if (calMatcher.find()) {
                        calories = Integer.parseInt(calMatcher.group(2));
                        if (calMatcher.group(1) != null) {
                            caloriesPerServing = calories;
                        }
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
                java.util.regex.Matcher sectionServingsMatcher = java.util.regex.Pattern
                        .compile("\\[재료(?:\\s*-\\s*(\\d+)인분)?\\]")
                        .matcher(trimmed);
                if (sectionServingsMatcher.matches()) {
                    inIngredients = true;
                    inSteps = false;
                    if (servings == null && sectionServingsMatcher.group(1) != null) {
                        servings = Integer.parseInt(sectionServingsMatcher.group(1));
                    }
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
                // 조리 순서 뒤에 붙은 안내 문구(조리 단계가 아닌 문장)가 나오면 조리 순서 섹션을 끝냅니다.
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
            recipe.setBaseServings(servings);
            recipe.setCalories(calories);
            recipe.setCaloriesPerServing(caloriesPerServing);
            recipe.setDifficulty(difficulty);
            recipe.setCookingTime(cookingTime);

            return recipe;
        } catch (Exception e) {
            log.warn("[RecipeReplyParser] category=RECIPE_REPLY_PARSE_EXCEPTION, exceptionClass={}", e.getClass().getSimpleName());
            return null;
        }
    }

}
