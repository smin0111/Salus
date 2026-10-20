package com.salus.healthytable.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 직전 레시피에 대한 재료 변경 후속 요청("양파 빼줘", "깻잎 말고 상추 넣어줘", "치즈 넣어줘")과
 * 요리 바꾸기 요청("로제떡볶이로 바꿔줘")을 규칙으로 읽습니다. LLM을 부르지 않습니다.
 *
 * <p>2026-10-09 실전 평가에서 이런 후속 요청이 일반 대화로 분류되어 LLM이 검증 없는 레시피를 새로 썼고
 * (70~100초), 안전장치가 그 답을 막아 카드 없이 끝났습니다. 여기서 읽은 변경은 직전 레시피에 그대로 적용하고,
 * 알레르기 판정은 적용 결과에 대해 다시 합니다.
 */
public final class IngredientEdits {

    public record Substitution(String from, String to) {
    }

    public record Edit(List<String> removals, List<Substitution> substitutions, List<String> additions) {
        public boolean isEmpty() {
            return removals.isEmpty() && substitutions.isEmpty() && additions.isEmpty();
        }
    }

    private static final String WORD = "([가-힣A-Za-z]{1,12}?)";
    // 새 요리를 묻는 표현이 있으면 후속 변경이 아니라 새 요청입니다("양파 없는 김치찌개 레시피 알려줘").
    // "알려줘"는 넣지 않습니다("양파 빼고 다시 알려줘"는 후속 변경).
    private static final Pattern NEW_REQUEST = Pattern.compile("레시피|만드는\\s*(?:법|방법)|조리\\s*법");
    private static final Pattern DETAIL = Pattern.compile("자세(?:히|하게)|상세(?:히|하게)|설명해|더\\s*알려|풀어서");
    // 자세히 요청에서 요리 이름이 아닌 말들입니다. 이것들을 지우고 남는 말이 없으면 직전 레시피에 대한 요청입니다.
    private static final Pattern DETAIL_FILLER = Pattern.compile(
            "레시피|조리\\s*법|만드는\\s*(?:법|방법)|순서|방법|자세(?:히|하게)|상세(?:히|하게)|설명(?:해|해서)?|풀어서|쉽게"
                    + "|알려|가르쳐|보여|주세요|줘|줄래|해|좀|더|조금|나|저|제가|내가|초보(?:라서|여서|니까|자)?|처음(?:이라서|이라)?"
                    + "|요리|잘\\s*못해(?:서)?|몰라(?:서)?|ㅠ+|ㅜ+|ㅎ+");
    // 낱말 끝에 붙은 조사만 지웁니다("가지"의 "가"처럼 낱말 안의 글자는 건드리지 않음).
    private static final Pattern TRAILING_PARTICLE = Pattern.compile("(?:를|을|이|가|는|은|도|요)(?=\\s|$)");
    private static final Pattern SUBSTITUTE_INSTEAD_OF = Pattern.compile(
            WORD + "(?:은|는|을|를)?\\s*(?:말고|대신에?)\\s*" + WORD + "(?:을|를|으로|로)?\\s*(?:넣어|써|사용|해|줘|주세요|$)");
    private static final Pattern SUBSTITUTE_CHANGE = Pattern.compile(
            WORD + "(?:을|를)\\s*" + WORD + "(?:으로|로)\\s*(?:바꿔|변경|교체)");
    private static final Pattern REMOVE = Pattern.compile(
            WORD + "(?:은|는|을|를|도)?\\s*(?:빼\\s*(?:줘|주세요|줄래|고)|제외|없이)");
    private static final Pattern ADD = Pattern.compile(
            WORD + "(?:을|를|도)?\\s*(?:넣어\\s*(?:줘|주세요|줄래)|추가(?:해\\s*(?:줘|주세요))?|올려\\s*(?:줘|주세요))");
    private static final Pattern DISH_SWITCH = Pattern.compile(
            "^(?:그럼|그러면)?\\s*([가-힣A-Za-z ]{2,20}?)\\s*(?:으로|로)\\s*(?:바꿔|변경)");
    private static final Set<String> NOT_INGREDIENTS = Set.of(
            "그럼", "그러면", "좀", "더", "이거", "그거", "저거", "이건", "그건", "다", "전부", "모두", "맛", "요리", "레시피", "재료");

    private IngredientEdits() {
    }

    /**
     * 재료 변경 요청을 읽습니다. 새 요리를 묻는 표현이 있거나 읽을 변경이 없으면 빈 결과입니다.
     */
    public static Optional<Edit> parse(String message) {
        if (message == null || message.isBlank() || NEW_REQUEST.matcher(message).find()) {
            return Optional.empty();
        }
        String text = message.replaceAll("[,，.?!~]", " ").replaceAll("\\s+", " ").trim();
        List<Substitution> substitutions = new ArrayList<>();
        Set<String> substituted = new LinkedHashSet<>();
        for (Pattern pattern : List.of(SUBSTITUTE_INSTEAD_OF, SUBSTITUTE_CHANGE)) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String from = matcher.group(1);
                String to = matcher.group(2);
                if (isIngredient(from) && isIngredient(to) && !from.equals(to) && substituted.add(from)) {
                    substitutions.add(new Substitution(from, to));
                    substituted.add(to);
                }
            }
        }
        List<String> removals = collect(REMOVE, text, substituted);
        List<String> additions = collect(ADD, text, substituted);
        additions.removeAll(removals);
        Edit edit = new Edit(List.copyOf(removals), List.copyOf(substitutions), List.copyOf(additions));
        return edit.isEmpty() ? Optional.empty() : Optional.of(edit);
    }

    /**
     * 직전 레시피를 이어서 다루는 요청인지 봅니다(재료 변경이나 "더 자세히"). 새 요리 요청이면 false입니다.
     */
    public static boolean isFollowUpOnPreviousRecipe(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (isDetailRequest(message)) {
            return true;
        }
        return !NEW_REQUEST.matcher(message).find() && parse(message).isPresent();
    }

    /**
     * 직전 레시피를 더 자세히 설명해 달라는 요청인지 봅니다. "레시피를 좀 더 자세히"처럼 "레시피"가 들어가도
     * 요리 이름으로 보이는 말이 남지 않으면 자세히 요청입니다(2026-10-12 "를 좀 더 자세히"를 요리 이름으로 찾음).
     */
    public static boolean isDetailRequest(String message) {
        if (message == null || !DETAIL.matcher(message).find()) {
            return false;
        }
        String rest = DETAIL_FILLER.matcher(message).replaceAll(" ").replaceAll("[^가-힣a-zA-Z]+", " ").trim();
        rest = TRAILING_PARTICLE.matcher(rest).replaceAll("").replaceAll("\\s+", " ").trim();
        return rest.isEmpty() || java.util.Arrays.stream(rest.split(" ")).allMatch(word -> word.length() < 2);
    }

    /**
     * "로제떡볶이로 바꿔줘"에서 바꿀 요리 이름 후보를 읽습니다. 재료 바꾸기("간장을 굴소스로 바꿔줘")는 제외합니다.
     * 요리 이름인지는 호출하는 쪽이 승인 카탈로그 등으로 확인합니다.
     */
    public static Optional<String> dishSwitchTarget(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = DISH_SWITCH.matcher(message.trim());
        if (!matcher.find()) {
            return Optional.empty();
        }
        String target = matcher.group(1).trim();
        if (target.matches(".*(?:을|를)\\s.*") || NOT_INGREDIENTS.contains(target)) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    private static List<String> collect(Pattern pattern, String text, Set<String> exclude) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String word = matcher.group(1);
            if (isIngredient(word) && !exclude.contains(word) && !found.contains(word)) {
                found.add(word);
            }
        }
        return found;
    }

    private static boolean isIngredient(String word) {
        return word != null && word.length() >= 1 && !NOT_INGREDIENTS.contains(word);
    }
}
