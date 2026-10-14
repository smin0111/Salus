package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.salus.healthytable.service.allergen.AllergenMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * "초보라서 자세히 알려줘"에 답하는 초보자 설명입니다. 레시피 단계마다 "왜, 어떻게"를 한 문장씩 덧붙입니다.
 *
 * <p>설명은 원문에 없는 내용이라 레시피 단계와 섞지 않고 따로 보여 주며, 그렇게 안내합니다. 원문에 없는 값을
 * 지어내지 않도록 숫자(분량·온도·시간)가 든 설명은 버리고, 등록 알레르기와 충돌하는 재료를 말하는 설명도 버립니다.
 * LLM이 없거나 실패하면 빈 결과이고, 호출하는 쪽은 원문 링크 안내로 대신합니다.
 */
@Slf4j
@Component
class RecipeBeginnerGuide {

    private static final int MAX_TIP_LENGTH = 90;
    private static final Pattern DIGIT = Pattern.compile("\\d");
    // 따라 하는 데 도움이 되는 팁의 표시: 확인 방법(색·소리·농도·모양, "~면", "~때까지")이나 주의("~지 않도록", "주의").
    // "깨끗하게 하면 맛이 훨씬 좋습니다"처럼 효과만 말하는 팁은 근거 없이 지어내기 쉬워 쓰지 않습니다(2026-10-12 봉골레).
    private static final Pattern PRACTICAL_CUE = Pattern.compile(
            "지\\s*않도록|않게|주의|조심|때까지|확인|색이|색으로|노릇|투명|소리|거품|농도|걸쭉|흐르|보이면|나면|되면|오르면|끓으면|타지|넘치|뭉치|퍼지|흐물");
    // 불 세기 표현. 단계에 없는 불 세기를 설명이 정하면 원문에 없는 조리 조건을 지어낸 것입니다("중간 불에서 조리해주세요").
    private static final Pattern HEAT = Pattern.compile("약\\s*불|중\\s*불|강\\s*불|센\\s*불|중간\\s*불|약한\\s*불|센\\s*불|중약\\s*불|중강\\s*불");

    private final ObjectProvider<RecipeTextLlmPort> llmPort;
    private final AllergenMatcher allergenMatcher;

    RecipeBeginnerGuide(ObjectProvider<RecipeTextLlmPort> llmPort, AllergenMatcher allergenMatcher) {
        this.llmPort = llmPort;
        this.allergenMatcher = allergenMatcher;
    }

    /**
     * 단계 수와 같은 길이의 설명 목록입니다. 쓸 수 없는 설명은 빈 문자열이고, 하나도 쓸 수 없으면 빈 결과입니다.
     */
    Optional<List<String>> explain(RecipeCandidate recipe, List<String> allergies) {
        RecipeTextLlmPort llm = llmPort.getIfAvailable();
        if (llm == null || recipe == null || recipe.steps().isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode response = llm.complete(prompt(recipe), schema(recipe.steps().size()));
            List<String> tips = new ArrayList<>();
            for (int i = 0; i < recipe.steps().size(); i++) {
                tips.add(usable(recipe.steps().get(i), response.path("tips").path(i).path("tip").asText(""), allergies));
            }
            if (tips.stream().allMatch(String::isBlank)) {
                log.info("[RecipeBeginnerGuide] Rejected. reason=NO_USABLE_TIP");
                return Optional.empty();
            }
            return Optional.of(List.copyOf(tips));
        } catch (Exception e) {
            log.warn("[RecipeBeginnerGuide] Failed. failureCategory={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // 원문에 없는 값을 지어낼 수 있는 숫자, 알레르기 충돌, 너무 긴 설명은 쓰지 않습니다.
    private String usable(String step, String tip, List<String> allergies) {
        String text = tip == null ? "" : tip.trim();
        if (text.isBlank() || text.length() > MAX_TIP_LENGTH || DIGIT.matcher(text).find()) {
            return "";
        }
        if (restates(step, text) || introducesHeat(step, text) || !PRACTICAL_CUE.matcher(text).find()) {
            return "";
        }
        if (allergies != null && !allergies.isEmpty() && !allergenMatcher.findConflicts(allergies, List.of(text)).isEmpty()) {
            return "";
        }
        return text;
    }

    /**
     * 설명이 단계 문장을 되풀이한 것인지 봅니다. 설명의 두 글자 묶음 중 단계에도 있는 비율이 절반 이상이면 되풀이입니다
     * ("부침가루와 달걀을 양푼이에 넣으면 재료들이 준비됩니다").
     */
    static boolean introducesHeat(String step, String tip) {
        String compactStep = step == null ? "" : step.replaceAll("\\s+", "");
        java.util.regex.Matcher matcher = HEAT.matcher(tip);
        while (matcher.find()) {
            if (!compactStep.contains(matcher.group().replaceAll("\\s+", ""))) {
                return true;
            }
        }
        return false;
    }

    static boolean restates(String step, String tip) {
        List<String> tipBigrams = bigrams(tip);
        if (tipBigrams.isEmpty()) {
            return true;
        }
        java.util.Set<String> stepBigrams = new java.util.HashSet<>(bigrams(step));
        long shared = tipBigrams.stream().filter(stepBigrams::contains).count();
        return shared * 2 >= tipBigrams.size();
    }

    private static List<String> bigrams(String text) {
        String compact = text == null ? "" : text.replaceAll("[^가-힣a-zA-Z]", "");
        List<String> result = new ArrayList<>();
        for (int i = 0; i + 1 < compact.length(); i++) {
            result.add(compact.substring(i, i + 2));
        }
        return result;
    }

    private String prompt(RecipeCandidate recipe) {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < recipe.steps().size(); i++) {
            steps.append(i + 1).append(". ").append(recipe.steps().get(i)).append('\n');
        }
        return """
                [요리] %s
                [재료] %s
                [조리 순서]
                %s
                요리를 처음 하는 사람이 위 조리 순서를 따라 할 수 있도록, 단계마다 짧은 도움말을 한 문장씩 쓰세요.
                - 도움말은 둘 중 하나만 쓰세요: 다 됐는지 눈·귀·손으로 확인하는 방법(색, 소리, 농도, 모양), 또는 초보가 흔히 하는 실수와 피하는 법.
                - 맛이 좋아지는 이유 같은 원리 설명은 쓰지 마세요. 확실하지 않은 이유를 지어내게 됩니다.
                - 단계 문장을 다른 말로 되풀이하지 마세요.
                - 숫자(분량, 온도, 시간)를 쓰지 마세요. 정확한 값은 원래 단계에 있습니다.
                - 재료 목록에 없는 재료를 더하거나 다른 재료로 바꾸라고 하지 마세요.
                - 쓸 말이 없거나 확실하지 않은 단계는 빈 문자열로 두세요.
                """.formatted(recipe.title(), String.join(", ", recipe.ingredients()), steps);
    }

    private Map<String, Object> schema(int steps) {
        Map<String, Object> tipProps = new LinkedHashMap<>();
        tipProps.put("step", Map.of("type", "integer"));
        tipProps.put("tip", Map.of("type", "string", "maxLength", MAX_TIP_LENGTH));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("required", List.of("step", "tip"));
        item.put("properties", tipProps);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("tips", Map.of("type", "array", "minItems", steps, "maxItems", steps, "items", item));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("tips"));
        schema.put("properties", props);
        return schema;
    }
}
