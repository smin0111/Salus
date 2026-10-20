package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.dto.ChatDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/*
 * Recipe Agent 파이프라인의 후반부 단계들(재료 수정 → 최종 검증 → 답변 조립)을 모아 둔 파일입니다.
 */

/**
 * 개인화 판단(RecipePersonalizationDecision)에 따라 후보 레시피의 재료와 조리 단계를 수정합니다.
 * - REMOVE: 재료 줄과 조리 단계 속 해당 재료 언급 제거
 * - SUBSTITUTE_OR_REDUCE: 당류 재료를 "알룰로스 소량"으로 대체
 * - RECOMMEND_ALTERNATIVE 판정: 대체 메뉴 구성
 */
@Component
class RecipeModificationService {

    RecipeCandidate apply(RecipeCandidate original, RecipePersonalizationDecision decision) {
        if (original == null || decision == null) {
            return original;
        }
        RecipeCandidate candidate = original;
        for (RecipeModification modification : decision.modifications()) {
            if ("REMOVE".equals(modification.action())) {
                candidate = removeIngredient(candidate, modification.ingredient());
            } else if ("SUBSTITUTE_OR_REDUCE".equals(modification.action())) {
                candidate = reduceOrSubstitute(candidate, modification);
            }
        }
        if (decision.decisionType() == RecipeDecisionType.RECOMMEND_ALTERNATIVE) {
            candidate = buildAlternative(candidate, decision);
        }
        return candidate;
    }

    private RecipeCandidate removeIngredient(RecipeCandidate candidate, String ingredient) {
        List<String> ingredients = candidate.ingredients().stream()
                .map(value -> withoutListedItem(value, ingredient))
                .filter(value -> !AgentText.mentions(value, ingredient))
                .toList();
        List<String> steps = candidate.steps().stream()
                .map(step -> removeIngredientFromStep(step, ingredient))
                .filter(step -> !step.isBlank())
                .toList();
        return candidate.withIngredientsAndSteps(ingredients, steps);
    }

    /**
     * "다양한버섯 (느타리/표고/팽이)"처럼 한 줄에 나열한 재료면 그 항목만 지웁니다. 줄째 지우면 다른 버섯까지 사라집니다
     * (2026-10-14 버섯전골 "팽이버섯은 빼줘"). 나열한 항목이 아니면 그대로 두어 호출하는 쪽이 줄째 지웁니다.
     */
    private String withoutListedItem(String line, String ingredient) {
        if (line == null || ingredient == null || ingredient.isBlank()) {
            return line;
        }
        String item = java.util.regex.Pattern.quote(ingredient.trim());
        String separators = "[/,·]";
        String removed = line.replaceAll("(\\(\\s*)" + item + "\\s*" + separators + "\\s*", "$1")
                .replaceAll("\\s*" + separators + "\\s*" + item + "(?=\\s*[)/,·])", "");
        return removed.equals(line) ? line : removed;
    }

    // 재료 이름에서 수량/단위를 떼고, 조리 문장에서 그 재료와 조사를 지웁니다. 남는 내용이 없으면 단계를 없앱니다.
    private String removeIngredientFromStep(String step, String ingredient) {
        if (step == null || step.isBlank() || ingredient == null || ingredient.isBlank()) {
            return step == null ? "" : step.trim();
        }
        String rootIngredient = ingredient.replaceAll("\\d+/\\d+|\\d+(?:\\.\\d+)?", "")
                .replaceAll("\\s*(g|kg|ml|mL|개|장|큰술|작은술|컵|캔|공기)\\s*", "")
                .trim();
        // 한 글자 재료("배")는 낱말 단위로만 찾습니다. "배추", "배달"의 "배"를 지우지 않습니다(2026-10-14).
        String ingredientPattern = rootIngredient.length() == 1
                ? "(?<![가-힣])" + java.util.regex.Pattern.quote(rootIngredient) + "(?![가-힣&&[^은는이가을를와과도로의만에랑채즙슬]])"
                : java.util.regex.Pattern.quote(rootIngredient);
        // 재료 바로 뒤의 분량("깻잎 한 묶음", "깻잎 10장")도 함께 지웁니다. 남기면 앞 재료의 분량처럼 읽힙니다
        // (2026-10-12 "오이 1개, 깻잎 한 묶음" → "오이 1개 한 묶음").
        String quantity = "(?:\\s*(?:\\d+(?:[./~-]\\d+)?|한|두|세|네|다섯|반)\\s*"
                + "(?:묶음|장|개|g|kg|ml|mL|큰술|작은술|스푼|숟가락|컵|줌|대|쪽|알|캔|봉지|팩|단|T|t)"
                // 분량 뒤 조사("양파 1/2개를")도 함께 지웁니다(2026-10-14 제육덮밥 "1/2개를 슬라이스하여"가 남음).
                + "(?:을|를|은|는|이|가|도)?(?![가-힣a-zA-Z]))?";
        // 한 문장 전체가 그 재료만 다루면("감자는 먹기 좋은 크기로 잘라주세요.") 단계째 지웁니다. 재료 이름만 지우면
        // "먹기 좋은 크기로 잘라주세요."처럼 무엇을 자르는지 없는 단계가 남습니다(2026-10-14 닭볶음탕 "감자는 빼줘").
        if (step.matches("\\s*" + ingredientPattern + "(?:은|는|을|를|도)\\s+[^,]*?[.!]?\\s*")
                && !step.matches(".*(?:고|며|서|하여|해서)\\s+\\S.*")) {
            return "";
        }
        // 그 재료만 다루는 앞 절("오이는 채 썰고 계란을 …")은 절째로 지웁니다. 재료 이름만 지우면 "채 썰고 계란을 …"처럼
        // 무엇을 써는지 알 수 없는 조각이 남습니다(2026-10-14 비빔국수 "오이는 빼줘").
        // 쉼표까지 이어지는 절이면 쉼표까지 지웁니다("오징어는 내장을 제거하고 링 모양으로 썰고, …" 전체).
        // 2026-10-14 해물파전: 첫 연결어미("제거하고")까지만 지워 "링 모양으로 썰고,"가 남음.
        String withoutClause = step.replaceAll("(^|[\\s,])" + ingredientPattern + "(?:은|는|도)\\s+[^,.]*(?:고|며|서)\\s*,\\s*", "$1");
        withoutClause = withoutClause.replaceAll("(^|[\\s,])" + ingredientPattern + "(?:은|는|도)\\s+[^,.]*?(?:고|며|서)(?:\\s*,)?\\s+", "$1");
        // 단계 첫머리나 앞 절 뒤에서 그 재료만 목적어로 삼는 절("계란을 반숙으로 튀겨 준비하고, …")도 지웁니다.
        // "빵가루, 계란을 넣고"처럼 다른 재료와 나열된 경우는 동사를 함께 쓰므로 지우지 않습니다(2026-10-14 함박스테이크).
        withoutClause = withoutClause.replaceAll("(^|(?:고|며|서)\\s*,?\\s+)" + ingredientPattern + "(?:을|를)\\s+[^,.]*?(?:고|며|서)(?:\\s*,)?\\s+", "$1");
        // 절 안에서 그 재료만 목적어인 경우("파기름 위에 양파 1/2개를 슬라이스하여 넣고 물을 붓는다")도 절째 지웁니다.
        // 재료 이름만 지우면 "파기름 위에 슬라이스하여 넣고"처럼 무엇을 써는지 알 수 없습니다(2026-10-14 제육덮밥).
        // 쉼표가 낀 절이나 "빵가루와 계란을"처럼 다른 재료와 함께 쓰인 경우는 지우지 않습니다.
        String connective = "(?:고|며|서|하여|해서)";
        // 조사가 바로 붙는 분량("1/2개를")입니다. 위 quantity는 뒤에 조사·글자가 오지 않을 때만 맞습니다.
        String amount = "(?:\\s*(?:\\d+(?:[./~-]\\d+)?|한|두|세|네|다섯|반)\\s*"
                + "(?:묶음|장|개|g|kg|ml|mL|큰술|작은술|스푼|숟가락|컵|줌|대|쪽|알|캔|봉지|팩|단|T|t))?";
        // 절 끝은 "-고/-며/-서/-하여"와 "썰어·넣어"처럼 "-어/-아"로 이어지는 동사입니다(2026-10-14 육회 "채썬 배 1/2개를
        // 가늘게 채 썰어 고기에 …"가 "채썬 가늘게 채 썰어"로 남음).
        String clauseEnd = "(?:고|며|서|하여|해서|[가-힣]어|[가-힣]아)";
        withoutClause = withoutClause.replaceAll("(^|" + connective + "\\s*,?\\s+)[^,.]*?(?<![와과랑]\\s)" + ingredientPattern
                + "(?:\\s*\\([^)]*\\))?" + amount + "(?:을|를)\\s+[^,.]*?" + clauseEnd
                // 이어지는 동사("슬라이스하여 넣고")까지 한 절로 봅니다. "냄비에서"처럼 장소 조사로 끝나는 낱말은 넣지 않습니다.
                + "(?:\\s+[가-힣]+(?:고|며|하여|해서))*(?:\\s*,)?\\s+", "$1");
        // 그 재료가 주어인 조건 절("양파가 어느 정도 익으면 대파를 넣는다")은 조건째 지웁니다.
        withoutClause = withoutClause.replaceAll("(^|" + connective + "\\s*,?\\s+)" + ingredientPattern + "(?:이|가)\\s+[^,.]*?면\\s*,?\\s+", "$1");
        // 나열 중간의 재료를 지우면 앞 낱말에 목적격 조사를 옮겨 붙입니다("빵가루, 계란을 넣고" → "빵가루를 넣고").
        // "빵가루와 계란을"처럼 와/과/랑으로 이은 경우도 같습니다. "사과"처럼 원래 "과"로 끝나는 낱말을 자르지 않도록
        // 앞 낱말은 두 글자 이상만 봅니다.
        java.util.regex.Matcher listed = java.util.regex.Pattern.compile("([가-힣]{2,}?)(?:\\s*,\\s*|(?:와|과|랑)\\s+)" + ingredientPattern
                + "(?:\\s*\\([^)]*\\))?" + amount + "(?:을|를)(?![가-힣])").matcher(withoutClause);
        StringBuilder relinked = new StringBuilder();
        while (listed.find()) {
            listed.appendReplacement(relinked, java.util.regex.Matcher.quoteReplacement(AgentText.withObjectParticle(listed.group(1))));
        }
        listed.appendTail(relinked);
        withoutClause = relinked.toString();
        // 재료로 만든 낱말("오이채", "레몬즙")은 낱말째 지웁니다. 재료 이름만 지우면 "담고채,"처럼 남습니다.
        withoutClause = withoutClause.replaceAll("(^|\\s)" + ingredientPattern + "(?:채|즙|가루|슬라이스)(?:을|를|와|과)?(?:\\s*,\\s*|(?=[\\s.]|$))", "$1");
        // 괄호로 적은 분량("계란(2개)")도 함께 지웁니다. 남기면 "다진 마늘(1스푼)(2개)"처럼 앞 재료에 붙습니다(2026-10-14).
        // 주격·보조사("양파가 익으면", "양파도")도 지웁니다. 남기면 "가 어느 정도 익으면"처럼 됩니다(2026-10-14 제육덮밥).
        String cleaned = withoutClause.replaceAll("\\s*,?\\s*" + ingredientPattern + "(?:\\s*\\([^)]*\\))?" + quantity + "(?:을|를|와|과|이|가|은|는|도)?(?![가-힣])", "")
                .replace(rootIngredient.length() == 1 ? "\u0000" : ingredient, "")
                .replaceAll("\\s*,\\s*,", ",")
                .replaceAll(",\\s*(?:을|를|와|과)\\s+", " ")
                .replaceAll("\\s+와\\s+을", "을")
                .replaceAll("\\s+과\\s+을", "을")
                .replaceAll("\\s{2,}", " ")
                .trim();
        String normalized = RecipeCandidate.normalize(cleaned);
        if (normalized.isBlank() || normalized.equals("넣습니다") || normalized.equals("올립니다")) {
            return "";
        }
        return cleaned;
    }

    // 설탕/시럽/꿀 같은 당류 재료를 "알룰로스 소량"으로 바꿉니다.
    private RecipeCandidate reduceOrSubstitute(RecipeCandidate candidate, RecipeModification modification) {
        List<String> ingredients = new ArrayList<>();
        boolean changed = false;
        for (String ingredient : candidate.ingredients()) {
            if (AgentText.containsAnyNormalized(ingredient, List.of("설탕", "시럽", "꿀", "올리고당", "물엿"))) {
                ingredients.add("알룰로스 소량");
                changed = true;
            } else {
                ingredients.add(ingredient);
            }
        }
        if (!changed) {
            ingredients = candidate.ingredients();
        }
        return candidate.withIngredientsAndSteps(ingredients, candidate.steps());
    }

    // 바나나가 들어간 레시피면 미리 정한 무설탕 대체 메뉴를, 아니면 당류 재료만 뺀 레시피를 반환합니다.
    private RecipeCandidate buildAlternative(RecipeCandidate candidate, RecipePersonalizationDecision decision) {
        if (AgentText.containsAnyNormalized(candidate.title() + " " + String.join(" ", candidate.ingredients()), List.of("바나나"))) {
            return new RecipeCandidate(
                    "설탕 없는 구운 바나나와 무가당 그릭요거트",
                    "추가 설탕 없이 바나나 자체의 단맛을 살리는 대체 메뉴입니다.",
                    List.of("바나나 1/2개", "무가당 그릭요거트 100g", "계피 약간"),
                    List.of("바나나는 반으로 잘라 팬이나 오븐에서 겉면만 가볍게 익힙니다.", "무가당 그릭요거트를 곁들이고 계피를 약간 뿌립니다."),
                    candidate.calories(),
                    1,
                    8,
                    List.of("바나나", "무가당 그릭요거트"),
                    List.of("계피"),
                    List.of());
        }
        return candidate.withIngredientsAndSteps(
                candidate.ingredients().stream()
                        .filter(ingredient -> !AgentText.containsAnyNormalized(ingredient, List.of("설탕", "시럽", "꿀", "올리고당", "물엿")))
                        .toList(),
                candidate.steps());
    }
}

/**
 * 개인화된 최종 레시피를 마지막으로 검증합니다.
 * 알레르기 재료나 사용자가 제외한 재료가 남아 있거나, 재료/조리 순서가 비어 있으면 실패입니다.
 */
@Component
class RecipeValidationPipeline {

    // 최종 레시피에 알레르겐이 남았는지 공용 매처로 검사합니다(표기뿐 아니라 파생 재료까지).
    private final com.salus.healthytable.service.allergen.AllergenMatcher allergenMatcher;

    @org.springframework.beans.factory.annotation.Autowired
    RecipeValidationPipeline(com.salus.healthytable.service.allergen.AllergenMatcher allergenMatcher) {
        this.allergenMatcher = allergenMatcher;
    }

    // 기본 알레르겐 사전으로 매처를 만들어 쓰는 생성자입니다(테스트용). 검사 강도는 운영과 같습니다.
    RecipeValidationPipeline() {
        this(defaultMatcher());
    }

    private static com.salus.healthytable.service.allergen.AllergenMatcher defaultMatcher() {
        com.salus.healthytable.service.allergen.AllergenDictionary dictionary =
                new com.salus.healthytable.service.allergen.AllergenDictionary();
        dictionary.load();
        return new com.salus.healthytable.service.allergen.AllergenMatcher(dictionary);
    }

    RecipeValidationResult validate(RecipeCandidate personalizedRecipe, UserRecipeContext context) {
        com.salus.healthytable.service.ChatProgress.report("건강정보에 맞는지 레시피를 검증하고 있어요");
        List<String> reasons = new ArrayList<>();
        if (personalizedRecipe == null) {
            return new RecipeValidationResult(false, List.of("personalized recipe is missing"));
        }
        // 표기("우유")만 보지 않고 파생 재료(버터·분유 등)까지 검사합니다. 개인화 단계가 놓쳐도 여기서 막습니다.
        List<String> finalTexts = new ArrayList<>(personalizedRecipe.ingredients());
        finalTexts.addAll(personalizedRecipe.steps());
        for (String allergy : allergenMatcher.findConflicts(context.allergies(), finalTexts)) {
            reasons.add("알레르기 재료가 최종 레시피에 남아 있습니다: " + allergy);
        }
        for (String allergy : context.allergies()) {
            if (personalizedRecipe.containsIngredient(allergy) && !reasons.contains("알레르기 재료가 최종 레시피에 남아 있습니다: " + allergy)) {
                reasons.add("알레르기 재료가 최종 레시피에 남아 있습니다: " + allergy);
            }
        }
        // 제외 재료도 파생 재료(돼지고기 → 삼겹살 등)까지 검사합니다. 사전에 없는 재료는 글자 그대로 봅니다.
        List<String> excludedConflicts = allergenMatcher.findConflicts(context.explicitlyExcludedIngredients(), finalTexts);
        for (String excluded : context.explicitlyExcludedIngredients()) {
            if (AgentText.exclusionForms(excluded).stream().anyMatch(form -> AgentText.mentionsAny(finalTexts, form)
                    || AgentText.mentions(personalizedRecipe.title(), form))
                    || excludedConflicts.contains(excluded.trim())) {
                reasons.add("사용자 제외 재료가 최종 레시피에 남아 있습니다: " + excluded);
            }
        }
        if (personalizedRecipe.ingredients().isEmpty()) {
            reasons.add("최종 레시피 재료가 비어 있습니다.");
        }
        if (personalizedRecipe.steps().isEmpty()) {
            reasons.add("최종 레시피 조리 순서가 비어 있습니다.");
        }
        return new RecipeValidationResult(reasons.isEmpty(), reasons);
    }
}

/**
 * Recipe Agent 결과를 사용자에게 보여 줄 답변 텍스트와 레시피 카드로 조립합니다.
 * 원본 레시피 → 출처 → 개인화 판단 → (복용약) → 변경 재료 → 최종 판단 → 레시피 순으로 구성합니다.
 * 차단(BLOCK)이거나 검증에 실패하면 레시피 본문 대신 제공 제한 이유만 보여 줍니다.
 */
@Component
class RecipeResponseComposer {

    String compose(PersonalizedRecipeResult result, RecipeValidationResult validation) {
        RecipePersonalizationDecision decision = result.decision();
        RecipeCandidate original = result.originalRecipe();
        RecipeCandidate personalized = result.personalizedRecipe();
        StringBuilder reply = new StringBuilder();
        // 출처를 못 찾았으면 안전 차단처럼 보이는 내부 형식 대신 사람이 읽는 문장으로 답합니다
        // (2026-10-12 "참치김밥 랑 유부초밥"에 "[원본 레시피 기준] … 안전상 제공 차단"이 그대로 노출됨).
        if (result.sources().isEmpty() && original.ingredients().isEmpty()) {
            reply.append("'").append(blank(original.title(), "요청한 요리"))
                    .append("' 레시피를 믿을 만한 출처에서 찾지 못했어요. 레시피를 지어내지 않기 위해 이번에는 답을 드리지 않았습니다.\n")
                    .append("요리 이름을 다르게 적어 보시거나(정식 이름, 띄어쓰기) 다른 요리를 물어봐 주세요.");
            decision.userNotices().forEach(notice -> reply.append("\n- ").append(notice));
            return reply.toString().trim();
        }
        // 제공하지 않는 레시피도 내부 판정 형식 대신 사람이 읽는 문장으로 답합니다
        // (2026-10-12 "오징어 안 들어간 김치전"에 "[원본 레시피 기준] … 안전상 제공 차단"이 그대로 노출됨).
        if (decision.decisionType() == RecipeDecisionType.BLOCK || !validation.valid()) {
            return blockedReply(result, validation);
        }
        reply.append("[원본 레시피 기준]\n");
        reply.append("- 요리명: ").append(blank(original.title(), "요청한 요리")).append("\n");
        if (!original.ingredients().isEmpty()) {
            reply.append("- 일반 구성: ").append(String.join(", ", original.ingredients())).append("\n");
        }
        reply.append("\n[출처 근거]\n");
        if (result.sources().isEmpty()) {
            reply.append("- 검증된 레시피 출처를 확보하지 못해 전체 레시피를 임의 생성하지 않았습니다.\n");
        } else {
            result.sources().stream().limit(3).forEach(source -> reply.append("- ")
                    .append(blank(source.title(), "출처 레시피"))
                    .append(source.url() == null || source.url().isBlank() ? "" : " (" + source.url() + ")")
                    // 승인 카탈로그(내부 DB)가 아닌 출처는 사람이 검토하지 않은 근거임을 밝힙니다.
                    .append(source.sourceType() == RecipeSourceType.INTERNAL_DB ? "" : " [외부 출처, 검토 전]")
                    .append("\n"));
            if (result.sources().stream().anyMatch(source -> source.sourceType() != RecipeSourceType.INTERNAL_DB)) {
                reply.append("- 외부 출처는 사람이 검토하지 않은 근거입니다. 자세한 내용과 사진은 원문 링크에서 확인해 주세요.\n");
            }
        }
        reply.append("\n[개인화 판단]\n");
        for (String notice : decision.userNotices()) {
            reply.append("- ").append(notice).append("\n");
        }
        if (decision.userNotices().isEmpty()) {
            reply.append("- 등록된 건강정보를 확인했습니다.\n");
        }
        if (decision.conflicts().stream().anyMatch(conflict -> conflict.type() == RecipeConflictType.CHRONIC_CONDITION)) {
            reply.append("- 섭취 가능 여부와 적정량은 개인 상태에 따라 다를 수 있습니다.\n");
        }
        appendMedicationSection(reply, decision);
        reply.append("\n[변경 또는 제외된 재료]\n");
        if (decision.modifications().isEmpty()) {
            reply.append("- 없음\n");
        } else {
            decision.modifications().forEach(modification -> reply.append("- ")
                    .append(modification.ingredient()).append(": ")
                    .append(modification.action())
                    .append(modification.replacement() == null ? "" : " -> " + modification.replacement())
                    .append(" (").append(modification.reason()).append(")\n"));
        }
        reply.append("\n[최종 판단]\n");
        reply.append(toKoreanDecision(decision.decisionType())).append("\n\n");

        if (decision.decisionType() == RecipeDecisionType.BLOCK || !validation.valid()) {
            reply.append("[제공 제한 이유]\n");
            decision.conflicts().forEach(conflict -> reply.append("- ").append(conflict.reason()).append("\n"));
            validation.reasons().forEach(reason -> reply.append("- ").append(reason).append("\n"));
            return reply.toString().trim();
        }

        reply.append("[개인화된 최종 레시피]\n");
        reply.append(blank(personalized.title(), original.title())).append("\n\n");
        if (!personalized.description().isBlank()) {
            reply.append(personalized.description()).append("\n\n");
        }
        reply.append("[재료]\n");
        personalized.ingredients().forEach(ingredient -> reply.append("- ").append(ingredient).append("\n"));
        reply.append("\n[조리 순서]\n");
        for (int i = 0; i < personalized.steps().size(); i++) {
            reply.append(i + 1).append(". ").append(personalized.steps().get(i)).append("\n");
        }
        return reply.toString().trim();
    }

    /**
     * 제공하지 않는 이유를 사람이 읽는 문장으로 씁니다. 알레르기·제외 재료 이름과 안내 문구는 그대로 남기고,
     * 같은 이유를 두 번 쓰지 않습니다.
     */
    private String blockedReply(PersonalizedRecipeResult result, RecipeValidationResult validation) {
        RecipePersonalizationDecision decision = result.decision();
        String title = blank(result.originalRecipe().title(), "요청한 요리");
        java.util.LinkedHashSet<String> reasons = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> cautions = new java.util.LinkedHashSet<>();
        java.util.Set<String> explained = new java.util.HashSet<>();
        for (RecipeConflict conflict : decision.conflicts()) {
            if (conflict.type() == RecipeConflictType.USER_EXCLUSION && "user-request-derived".equals(conflict.evidenceReference())) {
                reasons.add(conflict.reason());
                explained.add(conflict.ingredient());
            } else if (conflict.type() == RecipeConflictType.USER_EXCLUSION && conflict.severity() == ConflictSeverity.HIGH) {
                reasons.add(AgentText.withSubjectParticle(conflict.ingredient()) + " 이 요리의 핵심 재료라 빼면 다른 요리가 돼요.");
                explained.add(conflict.ingredient());
            } else if (conflict.type() == RecipeConflictType.MEDICATION_INTERACTION) {
                // 복용약 근거는 아래 [복용약 반영] 섹션에 확인 문구와 함께 씁니다.
                continue;
            } else if (conflict.severity() == ConflictSeverity.BLOCKING || conflict.severity() == ConflictSeverity.HIGH) {
                reasons.add(conflict.reason());
            } else if (conflict.type() == RecipeConflictType.ALLERGY
                    || conflict.type() == RecipeConflictType.CHRONIC_CONDITION
                    || conflict.type() == RecipeConflictType.DIETARY_RESTRICTION) {
                // 차단 이유는 아니어도 건강 정보와 관련된 주의는 남깁니다. 다른 요리를 고를 때도 필요한 정보입니다.
                // 레시피를 주지 않으므로 핵심 아닌 제외 재료 안내는 쓰지 않습니다.
                cautions.add(conflict.reason());
            }
        }
        for (String reason : validation.reasons()) {
            if (reason.startsWith("사용자 제외 재료가 최종 레시피에 남아 있습니다")
                    && explained.stream().anyMatch(reason::contains)) {
                continue;
            }
            reasons.add(reason);
        }
        StringBuilder reply = new StringBuilder();
        reply.append("'").append(title).append("' 레시피는 이번에 제공할 수 없어요.\n");
        reasons.forEach(reason -> reply.append("- ").append(reason).append("\n"));
        decision.userNotices().forEach(notice -> reply.append("- ").append(notice).append("\n"));
        cautions.stream().filter(caution -> !reasons.contains(caution))
                .forEach(caution -> reply.append("- 참고: ").append(caution).append("\n"));
        if (decision.conflicts().stream().anyMatch(conflict -> conflict.type() == RecipeConflictType.CHRONIC_CONDITION)) {
            reply.append("- 섭취 가능 여부와 적정량은 개인 상태에 따라 다를 수 있습니다.\n");
        }
        // 차단 답변에서도 복용약 근거와 의사·약사 확인 문구를 빠뜨리지 않습니다.
        appendMedicationSection(reply, decision);
        if (!result.sources().isEmpty()) {
            reply.append("\n[출처 근거]\n");
            result.sources().stream().limit(3).forEach(source -> reply.append("- ")
                    .append(blank(source.title(), "출처 레시피"))
                    .append(source.url() == null || source.url().isBlank() || source.url().contains("salus.internal")
                            ? "" : " (" + source.url() + ")")
                    .append("\n"));
        }
        reply.append("\n다른 요리를 물어보시거나, 빼고 싶은 재료를 바꿔서 다시 물어봐 주세요.");
        return reply.toString().trim();
    }

    // 개인화된 레시피로 레시피 카드를 만듭니다. 안내 문구(userNotices)를 주의 문구 자리에 넣습니다.
    ChatDto.RecipeCard toRecipeCard(RecipeCandidate candidate, RecipePersonalizationDecision decision) {
        return new ChatDto.RecipeCard(
                null,
                candidate.title(),
                candidate.description(),
                candidate.ingredients(),
                candidate.steps(),
                null,
                candidate.calories(),
                null,
                candidate.difficulty(),
                candidate.cookingTime(),
                null,
                decision.userNotices());
    }

    // 판정 enum을 사용자용 한국어 문구로 바꿉니다.
    private String toKoreanDecision(RecipeDecisionType decisionType) {
        return switch (decisionType) {
            case ALLOW -> "그대로 추천";
            case ALLOW_WITH_NOTICE -> "주의와 함께 추천";
            case MODIFY -> "수정 후 추천";
            case RECOMMEND_ALTERNATIVE -> "다른 메뉴 우선 추천";
            case BLOCK -> "안전상 제공 차단";
        };
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // 약물 상호작용 충돌이 있거나 복용약 관련 안내가 있으면 [복용약 반영] 섹션을 붙입니다.
    private void appendMedicationSection(StringBuilder reply, RecipePersonalizationDecision decision) {
        if (!hasMedicationEvidence(decision)) {
            return;
        }
        reply.append("\n[복용약 반영]\n");
        reply.append("- 아래 내용은 확인된 일부 약의 공식 정보 기준 결과만 표시합니다.\n");
        decision.conflicts().stream()
                .filter(conflict -> conflict.type() == RecipeConflictType.MEDICATION_INTERACTION)
                .forEach(conflict -> reply.append("- 확인된 근거: ").append(conflict.reason()).append("\n"));
        reply.append("- 복용 중단이나 복용량 변경을 의미하지 않습니다. 정확한 복용 방법은 의사 또는 약사에게 확인해 주세요.\n");
    }

    private boolean hasMedicationEvidence(RecipePersonalizationDecision decision) {
        return decision.conflicts().stream().anyMatch(conflict -> conflict.type() == RecipeConflictType.MEDICATION_INTERACTION)
                || decision.userNotices().stream().anyMatch(notice -> notice.contains("복용약") || notice.contains("복약정보") || notice.contains("약 이름"));
    }
}
