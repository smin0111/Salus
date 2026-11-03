package com.salus.healthytable.service.ingredient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 재료가 '무엇인지' 묻는 질문인지 문장 구조로 판별하고, 묻는 재료 이름을 뽑습니다.
 *
 * 질문은 "[재료][조사] [정체를 묻는 서술어]" 모양만 받습니다. 재료 이름 목록은 코드에 두지 않고 자료(IngredientCorpus)를 씁니다.
 * - "무청이 뭐야", "우엉은 무슨 과 식물이야", "광어 정식 이름이 뭐야", "청포묵은 뭘로 만들어", "두릅은 어디서 나는 거야"
 * - "시래기랑 우거지 차이가 뭐야", "곰취랑 참취는 같은 거야"(두 재료)
 * 이 모양이 아니면 받지 않습니다(빈 값). 예: "무 잎 말린 걸 뭐라고 해"(설명으로 이름 찾기), "부추를 경상도에서 뭐라고 해",
 * "시금치 어디에 보관해"(손질·보관), "땅콩 먹어도 돼"(안전 판단은 이 기능이 하지 않음).
 */
public final class IngredientQuestionParser {

    /**
     * @param asksName 다른 이름·정식 이름·영어 이름처럼 '이름'을 묻는지. 답할 때 이름을 적은 문장을 먼저 고릅니다.
     */
    public record Question(List<String> subjects, boolean comparison, boolean asksName) {
    }

    // 주어 자리 조사. 짧은 것부터 시도해 "고추냉이랑"이 "고추냉"이 되지 않게 합니다(이름 사전에 있으면 그 이름을 먼저 씀).
    private static final List<String> SUBJECT_PARTICLES = List.of("이", "가", "은", "는", "란", "이란");
    private static final List<String> PAIR_PARTICLES = List.of("랑", "와", "과", "이랑", "하고");

    // 정체를 묻는 서술어. 한국어 의문사(뭐·무엇·무슨·어떤·어느·어디)와 이름·원료를 묻는 명사로 시작합니다.
    // "연근은 연꽃의 어느 부분이야"처럼 앞에 "~의" 한 낱말이 올 수 있습니다.
    private static final Pattern IDENTITY_PREDICATE = Pattern.compile(
            "^(?:\\S+의\\s+)?(?:"
                    + "(?:뭐|뭔|뭡|무엇|뭣)\\S*"
                    + "|(?:무슨|어떤|어느)(?:\\s+\\S+){1,4}"
                    + "|(?:정식|다른|원래|영어|표준어|학명|한자)\\S*(?:\\s+\\S+){0,2}"
                    + "|(?:원료|재료|주재료)\\S*(?:\\s+\\S+)?"
                    + "|(?:뭘로|뭐로|무엇으로)\\s+\\S+"
                    + "|어디\\S*\\s+(?:나|자라|사)\\S*(?:\\s+\\S+)?"
                    + ")$");
    // 두 재료를 견주는 서술어("차이가 뭐야", "뭐가 달라", "같은 거야", "다른 거야")
    private static final Pattern COMPARISON_PREDICATE = Pattern.compile(
            "^(?:차이\\S*(?:\\s+\\S+)?|(?:뭐가|어떻게|어디가|뭐)\\s+(?:달라|다르|다른|차이)\\S*|(?:같은|다른)\\s*(?:거|건|것)\\S*|같아\\S*|달라\\S*|다르\\S*)$");
    // 건강·안전을 묻는 말이 섞이면 받지 않습니다. 이 기능은 효능이나 '먹어도 되는지'를 판단하지 않습니다.
    private static final Pattern HEALTH_OR_SAFETY = Pattern.compile(
            "먹어도|먹으면|먹어야|괜찮|알레르|알러지|효능|효과|영양|칼로리|열량|몸에|건강|질환|당뇨|혈압|임신|임산부");
    // 대화 앞뒤를 가리키는 말이나 Salus 자신을 묻는 말은 재료 이름이 아닙니다.
    private static final Pattern NON_INGREDIENT_SUBJECT = Pattern.compile("^(?:이거|그거|저거|이건|그건|저건|이게|그게|너|넌|니|당신|살루스|salus)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASKS_NAME = Pattern.compile("^(?:정식|다른\\s*이름|원래\\s*이름|영어|표준어|학명|한자|이름)");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\s?!.~…]+$");

    private IngredientQuestionParser() {
    }

    /**
     * @param isKnownName 자료가 아는 이름인지. 조사를 뗄 때 "매생이는"을 "매생"으로 잘라 먹지 않게 씁니다.
     */
    public static Optional<Question> parse(String message, Predicate<String> isKnownName) {
        if (message == null) {
            return Optional.empty();
        }
        String text = TRAILING_PUNCTUATION.matcher(message.strip()).replaceAll("");
        if (text.isEmpty() || text.contains("\n") || HEALTH_OR_SAFETY.matcher(text).find()) {
            return Optional.empty();
        }
        List<String> words = Arrays.asList(text.split("\\s+"));
        Split first = split(words.get(0), isKnownName);
        if (first.name().isEmpty() || NON_INGREDIENT_SUBJECT.matcher(first.name()).matches()) {
            return Optional.empty();
        }

        if (PAIR_PARTICLES.contains(first.particle()) && words.size() >= 3) {
            Split second = split(words.get(1), isKnownName);
            if (second.name().isEmpty() || !(second.particle().isEmpty() || SUBJECT_PARTICLES.contains(second.particle()))
                    || NON_INGREDIENT_SUBJECT.matcher(second.name()).matches()) {
                return Optional.empty();
            }
            String predicate = String.join(" ", words.subList(2, words.size()));
            if (COMPARISON_PREDICATE.matcher(predicate).matches() || IDENTITY_PREDICATE.matcher(predicate).matches()) {
                return Optional.of(new Question(List.of(first.name(), second.name()), true, false));
            }
            return Optional.empty();
        }

        if (!(first.particle().isEmpty() || SUBJECT_PARTICLES.contains(first.particle()))) {
            return Optional.empty();
        }
        String predicate = String.join(" ", words.subList(1, words.size()));
        // "무청이란?"처럼 '~란'으로 끝나면 서술어가 없어도 정의를 묻는 질문입니다.
        boolean definitionParticle = first.particle().equals("란") || first.particle().equals("이란");
        if ((predicate.isEmpty() && definitionParticle) || IDENTITY_PREDICATE.matcher(predicate).matches()) {
            return Optional.of(new Question(List.of(first.name()), false, ASKS_NAME.matcher(predicate).find()));
        }
        return Optional.empty();
    }

    record Split(String name, String particle) {
    }

    /**
     * 낱말에서 조사를 뗍니다. 자료가 아는 이름이 되는 경우를 먼저 고르고("고추냉이랑"→"고추냉이"),
     * 모르는 이름이면 가장 짧은 조사를 뗍니다("퀴노아는"→"퀴노아").
     */
    static Split split(String word, Predicate<String> isKnownName) {
        List<Split> candidates = new ArrayList<>();
        candidates.add(new Split(word, ""));
        List<String> particles = new ArrayList<>(SUBJECT_PARTICLES);
        particles.addAll(PAIR_PARTICLES);
        particles.sort((a, b) -> Integer.compare(a.length(), b.length()));
        for (String particle : particles) {
            if (word.endsWith(particle) && word.length() > particle.length()) {
                candidates.add(new Split(word.substring(0, word.length() - particle.length()), particle));
            }
        }
        for (Split candidate : candidates) {
            if (isKnownName.test(candidate.name()) || IngredientNames.withoutKindSuffix(candidate.name())
                    .filter(isKnownName).isPresent()) {
                return candidate;
            }
        }
        return candidates.size() > 1 ? candidates.get(1) : candidates.get(0);
    }
}
