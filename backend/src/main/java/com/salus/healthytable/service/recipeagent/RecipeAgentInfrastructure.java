package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.service.RecipeNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/*
 * Recipe Agent 파이프라인의 앞부분 구성 요소(요청 분석, 내부 DB 출처 검색, 근거 추출, 후보 레시피 구성)를 모아 둔 파일입니다.
 */

/**
 * 약물-음식 상호작용 확인의 기본 구현입니다. 실제 조회 없이 항상 "알 수 없음(UNKNOWN)"을 반환합니다.
 * 확인하지 못한 상호작용을 "없음"으로 표시하지 않기 위한 안전한 기본값입니다.
 */
@Service
class UnknownMedicationFoodInteractionAdapter implements MedicationFoodInteractionPort {

    @Override
    public MedicationInteractionResult check(List<String> medications, List<String> recipeIngredients) {
        return MedicationInteractionResult.unknown(medications);
    }
}

/**
 * 사용자 메시지를 분석해 레시피 검색 계획(RecipeResearchPlan)을 세우는 클래스입니다.
 * 요리 이름, 크리에이터 이름("백종원의 김치찌개" 등), 요청 모드, 검색어 목록을 만듭니다.
 */
@Service
@RequiredArgsConstructor
class DefaultRecipeRequestPlanner {

    private final RecipeNormalizer recipeNormalizer;

    // 사용자 맥락 객체 없이 "맥락이 있는지"만 알 때 쓰는 편의 메서드입니다.
    RecipeResearchPlan plan(String message, boolean useFridgeIngredients, boolean hasUserContext) {
        return plan(message, useFridgeIngredients, hasUserContext ? UserRecipeContext.empty(null) : null);
    }

    // 제외 구절("돼지고기는 빼줘", "양파 빼고", "돼지고기 없는")입니다. 제외 재료는 extractExplicitExclusions가 따로 뽑습니다.
    private static final java.util.regex.Pattern EXCLUSION_CLAUSE = java.util.regex.Pattern.compile(
            "[,·]?\\s*[가-힣a-zA-Z0-9]+(?:은|는|을|를|이|가)?\\s*(?:빼\\s*주세요|빼\\s*줘|빼고|빼|제외(?:해\\s*줘|하고|해\\s*주세요)?|말고|없는|안\\s*들어간)");

    /**
     * 요리 이름에서 제외 구절을 떼어 냅니다. "김치찌개 레시피 알려줘, 돼지고기는 빼줘"의 요리 이름이
     * "김치찌개 , 돼지고기는 빼줘"로 잡혀 검색과 출처 확인이 모두 실패하던 문제를 막습니다(2026-10-09).
     */
    private static final java.util.regex.Pattern ANOTHER_WORDS = java.util.regex.Pattern.compile(
            "(?:또\\s*)?다른|버전(?:으로|을|로)?|(?:이거|그거|저거|이걸|그걸|이건|그건)\\s*(?:말고|대신)");
    // 레시피로 자주 검색하는 방송·사이트 이름입니다(사람 이름 아님). 요리 이름이 아니라 검색어 앞에만 붙입니다
    // (2026-10-14 "편스토랑 마늘간장치킨"이 "편스토"로 잘리고 레시피 모음 글이 골라짐).
    private static final String KNOWN_SHOW_OR_CREATOR =
            "(?<![가-힣])(?:편스토랑|신상출시|수미네\\s*반찬|수미네|만개의레시피|1분요리|딩고쿠킹|집밥\\s*백선생|백파더|"
                    + "냉장고를\\s*부탁해|냉부해|알토란|생생정보)(?:의)?(?![가-힣])";

    // 요리 이름에 든 방송·사이트 이름을 검색어 앞말로 모읍니다. 없으면 빈 문자열입니다.
    static String knownShowOrCreatorPrefix(String dishName) {
        if (dishName == null || dishName.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(KNOWN_SHOW_OR_CREATOR).matcher(dishName);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group().replaceFirst("의$", ""));
        }
        String rest = dishName.replaceAll(KNOWN_SHOW_OR_CREATOR, " ").replaceAll("\\s+", " ").trim();
        // 이름만 있고 요리 이름이 없으면 떼지 않습니다("백종원 레시피 알려줘").
        return names.isEmpty() || rest.length() < 2 ? "" : String.join(" ", names);
    }

    // "나 배 알러지 있어", "땅콩 알레르기 있어요"처럼 알레르기를 말한 구절입니다.
    private static final java.util.regex.Pattern ALLERGY_CLAUSE = java.util.regex.Pattern.compile(
            "[,·]?\\s*(?:(?:나|저|제가|내가|나는|저는)\\s+)?[가-힣a-zA-Z0-9]+(?:에|은|는|이|가)?\\s*(?:알레르기|알러지|알르레기)"
                    + "(?:\\s*(?:가|는|이)?\\s*(?:있어요|있어서|있어|있음|있다고|있습니다|있는데))?");
    // "(다른) 건 다 괜찮아", "나머지는 상관없어"처럼 허용을 덧붙인 말입니다.
    private static final java.util.regex.Pattern OK_FILLER = java.util.regex.Pattern.compile(
            "(?:(?:다른|나머지)\\s*)?(?:건|것은|거는|나머지는|재료는)?\\s*(?:다|전부|모두)?\\s*(?:괜찮아요|괜찮아|괜찮음|상관없어요|상관없어|상관없음|좋아요|좋아)\\s*$");
    // "짬뽕탕 레시피로 찾아줘봐"의 "로 찾아줘봐"처럼 요리 이름 뒤에 남는 요청 표현과 홀로 남은 조사입니다.
    private static final java.util.regex.Pattern REQUEST_TAIL = java.util.regex.Pattern.compile(
            "\\s*(?:찾아\\s*(?:줘|주세요|줄래|봐)(?:\\s*봐)?|보여\\s*(?:줘|주세요)(?:\\s*봐)?|알려\\s*줘\\s*봐"
                    // "알려달라고", "해달라니까"처럼 다시 조르는 말(2026-10-14 "삼겹살 배추찜 알려달라고"로 검색해 실패)
                    + "|(?:알려|가르쳐|찾아|보여)?\\s*(?:달라|해\\s*달라)(?:고|니까|구|잖아)?)\\s*$");
    // "레시피도", "레시피를"에서 "레시피"를 지우고 홀로 남은 조사와 "그냥·좀" 같은 군더더기입니다
    // (2026-10-12 "김치전 도", "그냥 김치전", "를 좀 더 자세히"를 요리 이름으로 찾다 실패).
    private static final java.util.regex.Pattern LONE_PARTICLE = java.util.regex.Pattern.compile(
            "(?:^|\\s)(?:으로|로|도|를|을|은|는|이|가|그냥|좀|한번|혹시|다시|그럼|그러면|나|저"
                    // 맞장구·지시어(2026-10-14 "그래 그럼 이거 말고 다른 레시피로"의 "그래"를 요리 이름으로 찾아 그래놀라를 답함)
                    + "|그래|그래요|응|네|넵|예|좋아|좋아요|오케이|이거|그거|저거|이걸|그걸|이것|그것)(?=\\s|$)");
    // 요리 두 개를 함께 물을 때의 연결 표현("참치김밥 레시피랑 유부초밥" → "참치김밥 랑 유부초밥", "떡볶이랑 순대").
    // "새우와 브로콜리 볶음"처럼 한 요리 이름 안의 "와/과"는 붙어 있으므로 떨어져 있을 때만 나눕니다.
    private static final java.util.regex.Pattern DISH_CONNECTOR = java.util.regex.Pattern.compile(
            "\\s+(?:이랑|랑|하고|그리고|및|와|과)\\s+|(?<=[가-힣])(?<!스토|사|자|노|파)(?:랑|하고)\\s+|\\s*,\\s*");

    /**
     * 요리 이름 하나에 여러 요리가 들어 있으면 나눕니다. 첫 요리만 찾고 나머지는 이어서 물어보도록 안내합니다
     * (2026-10-12 "참치김밥 레시피랑 유부초밥 레시피"를 "참치김밥 랑 유부초밥" 한 요리로 찾다가 실패).
     */
    static List<String> splitDishes(String dishName) {
        if (dishName == null || dishName.isBlank()) {
            return List.of();
        }
        // 붙여 쓴 "이랑"은 앞 글자에 받침이 있을 때만 연결어입니다("김밥이랑" → 김밥). "떡볶이랑"은 "떡볶이"+"랑"입니다.
        String text = dishName.trim();
        java.util.regex.Matcher attachedIrang = java.util.regex.Pattern.compile("([가-힣]+)이랑\\s+").matcher(text);
        StringBuilder normalized = new StringBuilder();
        while (attachedIrang.find()) {
            String word = attachedIrang.group(1);
            boolean dishEndingWithI = (word + "이").matches(".*(?:볶이|구이|오이|무침이|튀김이)$") || !hasBatchim(word);
            attachedIrang.appendReplacement(normalized, java.util.regex.Matcher.quoteReplacement(
                    dishEndingWithI ? word + "이 , " : word + " , "));
        }
        attachedIrang.appendTail(normalized);
        return java.util.Arrays.stream(DISH_CONNECTOR.split(normalized.toString()))
                .map(String::trim)
                .filter(part -> part.length() >= 2)
                .toList();
    }

    private static boolean hasBatchim(String word) {
        char last = word.charAt(word.length() - 1);
        return last >= 0xAC00 && last <= 0xD7A3 && (last - 0xAC00) % 28 != 0;
    }

    // 이번 요청에서 첫 요리 말고 함께 물은 요리들입니다.
    List<String> otherRequestedDishes(String message) {
        List<String> dishes = splitDishes(withoutExclusionClause(recipeNormalizer.normalize(message == null ? "" : message.trim())));
        return dishes.size() <= 1 ? List.of() : dishes.subList(1, dishes.size());
    }

    static String withoutExclusionClause(String dishName) {
        if (dishName == null || dishName.isBlank()) {
            return dishName == null ? "" : dishName;
        }
        // "다른 두쫀쿠", "다른 버전으로"의 "다른·버전"도 요리 이름이 아닙니다. 지우고 남는 말이 없으면 빈 이름입니다
        // (직전 요리의 다른 레시피 요청).
        String withoutAnother = ANOTHER_WORDS.matcher(dishName).replaceAll(" ").replaceAll("\\s+", " ").trim();
        withoutAnother = LONE_PARTICLE.matcher(REQUEST_TAIL.matcher(withoutAnother).replaceAll("")).replaceAll(" ")
                .replaceAll("\\s+", " ").trim();
        if (withoutAnother.isBlank()) {
            return "";
        }
        // "나 배 알러지 있어"처럼 알레르기를 말한 구절도 요리 이름이 아닙니다(2026-10-14 "육회 배 알러지 있어"로 검색해 실패).
        withoutAnother = ALLERGY_CLAUSE.matcher(withoutAnother).replaceAll(" ").replaceAll("\\s+", " ").trim();
        // "다른 건 다 괜찮아", "나머지는 상관없어"도 요리 이름이 아닙니다(2026-10-14 "깍두기 건 다 괜찮아"로 검색해 실패).
        withoutAnother = OK_FILLER.matcher(withoutAnother).replaceAll(" ").replaceAll("\\s+", " ").trim();
        String cleaned = EXCLUSION_CLAUSE.matcher(withoutAnother).replaceAll(" ")
                .replaceAll("[,·]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isBlank() ? withoutAnother : cleaned;
    }

    // 검색 계획을 만듭니다. 최대 검색 시도는 3회, 최대 출처 수는 5개로 고정합니다.
    RecipeResearchPlan plan(String message, boolean useFridgeIngredients, UserRecipeContext context) {
        String normalizedMessage = message == null ? "" : message.trim();
        String dishName = withoutExclusionClause(recipeNormalizer.normalize(normalizedMessage));
        List<String> dishes = splitDishes(dishName);
        if (dishes.size() > 1) {
            dishName = dishes.get(0);
        }
        // 방송·사이트 이름("편스토랑 마늘간장치킨")은 작성자가 아니므로 요리 이름에서 떼고 검색어에만 붙입니다.
        // 사람 이름(백종원·류수영 등)은 떼지 않습니다. 출처 제목에 그 이름이 없으면 작성자가 같아야 한다는 확인을 지키기 위해서입니다.
        String showPrefix = knownShowOrCreatorPrefix(dishName);
        if (!showPrefix.isBlank()) {
            dishName = dishName.replaceAll(KNOWN_SHOW_OR_CREATOR, " ").replaceAll("\\s+", " ").trim();
        }
        CreatorDish creatorDish = showPrefix.isBlank()
                ? extractCreatorDish(normalizedMessage, dishName)
                : new CreatorDish("", dishName);
        if (!creatorDish.dishName().isBlank()) {
            dishName = creatorDish.dishName();
        }
        RecipeRequestMode mode = resolveMode(normalizedMessage);
        if (!creatorDish.creatorName().isBlank()) {
            mode = RecipeRequestMode.CREATOR_SPECIFIC;
        }
        boolean latest = normalizedMessage.contains("최신") || normalizedMessage.toLowerCase().contains("latest");
        List<String> searchQueries = searchQueries(dishName,
                creatorDish.creatorName().isBlank() ? showPrefix : creatorDish.creatorName(),
                normalizedMessage, useFridgeIngredients, context);
        return new RecipeResearchPlan(
                dishName,
                creatorDish.creatorName(),
                mode,
                latest,
                useFridgeIngredients,
                context != null,
                searchQueries,
                3,
                5);
    }

    // "양파 빼고", "새우 말고" 같은 표현 바로 앞 단어를 제외 재료로 추출합니다(2글자 이상만).
    List<String> extractExplicitExclusions(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        List<String> exclusions = new ArrayList<>();
        for (String marker : List.of("빼 주세요", "빼줘", "빼고", "빼", "제외", "말고", "없는", "안 들어간", "안들어간")) {
            int index = message.indexOf(marker);
            if (index <= 0) {
                continue;
            }
            String before = message.substring(0, index).trim();
            String candidate = before.replaceAll(".*\\s", "")
                    .replaceFirst("(은|는|을|를)$", "")
                    .trim();
            // "명란이 안 들어간"의 조사 '이/가'도 뗍니다. 떼고 한 글자만 남으면 재료 이름의 일부로 보고 두며("오이"),
            // 뗀 이름은 원래 이름의 앞부분이라 재료 비교("포함")에서 원래 재료를 그대로 잡습니다("명란" → "명란젓").
            // 2026-10-12: "명란이"로 읽어 "저염 명란젓"을 빼지도 막지도 못하고 그대로 답함.
            if (candidate.matches(".{2,}[이가]")) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            // 한 글자 재료("배는 빼줘", "무 빼고")도 받습니다. 두 글자 이상만 받아 "배"를 버리고 배가 든 육회를 그대로 답했습니다
            // (2026-10-14). 재료가 아닌 한 글자 말은 거릅니다. 한 글자 비교는 낱말 단위로 해서 "배추"를 지우지 않습니다.
            if (candidate.length() >= 2 || (candidate.matches("[가-힣]") && !NOT_INGREDIENT_SYLLABLES.contains(candidate))) {
                exclusions.add(candidate);
            }
        }
        // "배 알러지 있어"처럼 대화에서 말한 재료도 이번 레시피에서 뺍니다. 알레르기 등록은 건강 프로필에서 하므로
        // 여기서는 알레르기로 판정하지 않고 빼는 재료로만 씁니다(사전에 없는 한 글자는 알레르기로 받지 않는 정책 유지).
        java.util.regex.Matcher mention = ALLERGY_MENTION.matcher(message);
        while (mention.find()) {
            String term = mention.group(1);
            if (term.length() >= 2 || !NOT_INGREDIENT_SYLLABLES.contains(term)) {
                exclusions.add(term);
            }
        }
        return AgentText.distinct(exclusions);
    }

    // 대화에서 알레르기를 말한 재료입니다: "배 알러지 있어", "나 땅콩 알레르기", "새우에 알레르기".
    private static final java.util.regex.Pattern ALLERGY_MENTION = java.util.regex.Pattern.compile(
            "(?:^|\\s)([가-힣]{1,10}?)(?:에|은|는|이|가)?\\s*(?:알레르기|알러지|알르레기)(?!\\s*(?:는|은|가|도)?\\s*(?:없|아니|아냐))");
    // 재료가 아닌 한 글자 말입니다("나 배 알러지"의 "나", "좀 빼줘"의 "좀").
    private static final java.util.Set<String> NOT_INGREDIENT_SYLLABLES = java.util.Set.of(
            "나", "저", "난", "전", "내", "제", "좀", "다", "더", "그", "이", "꼭", "또", "걍", "왜", "뭐", "것", "거", "건");

    /** 대화에서 알레르기를 말한 재료가 있는지 봅니다. 안내 문구를 붙일 때 씁니다. */
    boolean mentionsAllergy(String message) {
        return message != null && ALLERGY_MENTION.matcher(message).find();
    }

    // 메시지 키워드로 요청 모드를 정합니다(자세히 → DETAIL, 대신/대체 → SUBSTITUTE, 빼고/제외 → EXCLUDE, 추천 → RECOMMEND).
    private RecipeRequestMode resolveMode(String message) {
        String normalized = message.replaceAll("\\s+", "");
        if (normalized.contains("자세")) {
            return RecipeRequestMode.DETAIL;
        }
        if (normalized.contains("대신") || normalized.contains("대체")) {
            return RecipeRequestMode.SUBSTITUTE;
        }
        if (normalized.contains("빼고") || normalized.contains("제외") || normalized.contains("없는")) {
            return RecipeRequestMode.EXCLUDE;
        }
        if (normalized.contains("추천")) {
            return RecipeRequestMode.RECOMMEND;
        }
        return RecipeRequestMode.CREATE;
    }

    // "요리 레시피", "요리 만드는 법" 검색어를 만들고, 냉장고 재료 활용 시 재료 이름을 붙인 검색어도 추가합니다.
    private List<String> searchQueries(
            String dishName,
            String creatorName,
            String originalMessage,
            boolean useFridgeIngredients,
            UserRecipeContext context) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String prefix = creatorName == null || creatorName.isBlank() ? "" : creatorName.trim() + " ";
        if (dishName != null && !dishName.isBlank()) {
            queries.add(prefix + dishName + " 레시피");
            queries.add(prefix + dishName + " 만드는 법");
        } else if (originalMessage != null && !originalMessage.isBlank()) {
            queries.add(originalMessage);
        }
        if (useFridgeIngredients && context != null) {
            fridgeSearchTerms(dishName, context).forEach(term -> {
                if (dishName != null && !dishName.isBlank()) {
                    queries.add(prefix + dishName + " " + term + " 레시피");
                } else {
                    queries.add(term + " 레시피");
                }
            });
        }
        return List.copyOf(queries);
    }

    // 요리 이름에 들어 있거나 3일 이내에 유통기한이 끝나는 냉장고 재료를 최대 2개 고릅니다.
    private List<String> fridgeSearchTerms(String dishName, UserRecipeContext context) {
        if (context == null || context.fridgeIngredients().isEmpty()) {
            return List.of();
        }
        String normalizedDish = RecipeCandidate.normalize(dishName);
        return context.fridgeIngredients().stream()
                .filter(fridge -> fridge.name() != null && !fridge.name().isBlank())
                .filter(fridge -> {
                    String normalizedName = RecipeCandidate.normalize(fridge.name());
                    return normalizedDish.contains(normalizedName)
                            || (fridge.expirationDate() != null && !fridge.expirationDate().isAfter(java.time.LocalDate.now().plusDays(3)));
                })
                .map(FridgeIngredientContext::name)
                .distinct()
                .limit(2)
                .toList();
    }

    /**
     * "크리에이터 요리" 형태의 요청에서 크리에이터 이름과 요리 이름을 분리합니다.
     * 두 단어이고 첫 단어에 명확한 크리에이터 신호("OO의 ", @, -, 영문)가 있을 때만 분리합니다.
     */
    private CreatorDish extractCreatorDish(String message, String normalizedDish) {
        if (message == null || message.isBlank()) {
            return new CreatorDish("", normalizedDish == null ? "" : normalizedDish);
        }
        String cleaned = withoutExclusionClause(recipeNormalizer.normalize(message))
                .replaceAll("\\s+", " ")
                .trim();
        List<String> dishes = splitDishes(cleaned);
        if (dishes.size() > 1) {
            cleaned = dishes.get(0);
        }
        String[] tokens = cleaned.split("\\s+");
        if (tokens.length != 2 || isGenericRequestToken(tokens[0]) || !hasExplicitCreatorSignal(message, tokens[0])) {
            return new CreatorDish("", cleaned);
        }
        String creator = tokens[0].trim();
        String dish = cleaned.substring(creator.length()).trim();
        if (creator.length() < 2 || dish.length() < 2) {
            return new CreatorDish("", cleaned);
        }
        return new CreatorDish(creator, dish);
    }

    private boolean hasExplicitCreatorSignal(String message, String firstToken) {
        String token = firstToken == null ? "" : firstToken.trim();
        if (token.isBlank()) {
            return false;
        }
        return message.contains(token + "의 ")
                || token.startsWith("@")
                || token.contains("-")
                || token.matches(".*[A-Za-z].*");
    }

    private boolean isGenericRequestToken(String token) {
        String normalized = RecipeCandidate.normalize(token);
        return List.of("일반", "요즘", "최신", "유튜브", "냉장고", "오늘", "집밥").contains(normalized);
    }

    private record CreatorDish(String creatorName, String dishName) {
    }
}

/**
 * 내부 DB(승인 레시피)에서 출처를 찾는 검색 어댑터입니다. 내부 DB 출처는 신뢰도 0.95로 표시합니다.
 */
@Service
@RequiredArgsConstructor
class InternalRecipeSourceDiscoveryAdapter implements RecipeSourceDiscoveryPort {

    private final RecipeRepository recipeRepository;

    @Override
    public List<RecipeSourceDocument> search(RecipeResearchPlan plan, UserRecipeContext context) {
        if (plan == null || plan.dishName() == null || plan.dishName().isBlank()) {
            return List.of();
        }
        return recipeRepository.findByTitleContaining(plan.dishName()).stream()
                .limit(plan.maxSources())
                .map(this::toDocument)
                .toList();
    }

    // DB 레시피를 CandidateBuilder가 읽을 수 있는 "title:/ingredients:/steps:" 텍스트 형식의 출처 문서로 바꿉니다.
    private RecipeSourceDocument toDocument(Recipe recipe) {
        String content = """
                title: %s
                description: %s
                ingredients:
                %s
                steps:
                %s
                """.formatted(
                nullToBlank(recipe.getTitle()),
                nullToBlank(recipe.getDescription()),
                String.join("\n", safeList(recipe.getIngredients())),
                String.join("\n", safeList(recipe.getSteps())));
        return new RecipeSourceDocument(
                "recipe:" + recipe.getId(),
                RecipeSourceType.INTERNAL_DB,
                recipe.getTitle(),
                "",
                "",
                content,
                recipe.getCreatedAt() == null ? LocalDateTime.now() : recipe.getCreatedAt(),
                0.95);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}

/**
 * 검색된 출처 중 본문이 있는 문서만 최대 5개 남깁니다.
 */
@Component
class RecipeEvidenceExtractor {

    List<RecipeSourceDocument> extract(List<RecipeSourceDocument> documents) {
        return documents == null ? List.of() : documents.stream()
                .filter(document -> document != null && document.content() != null && !document.content().isBlank())
                .limit(5)
                .toList();
    }
}

/**
 * 첫 번째(가장 우선인) 출처 문서의 본문을 파싱해 후보 레시피를 만듭니다.
 */
@Component
class RecipeCandidateBuilder {

    RecipeCandidate build(RecipeResearchPlan plan, List<RecipeSourceDocument> sources) {
        if (sources == null || sources.isEmpty()) {
            return RecipeCandidate.empty(plan == null ? "" : plan.dishName());
        }
        RecipeSourceDocument source = sources.get(0);
        ParsedRecipe parsed = parse(source.content());
        return new RecipeCandidate(
                blankToFallback(parsed.title(), source.title()),
                parsed.description(),
                parsed.ingredients(),
                parsed.steps(),
                null,
                null,
                null,
                parsed.coreIngredients().isEmpty() ? parsed.ingredients().stream().limit(4).toList() : parsed.coreIngredients(),
                parsed.optionalIngredients(),
                parsed.healthRiskTags());
    }

    /**
     * 출처 본문을 한 줄씩 읽어 제목/설명/재료/조리 단계/핵심·선택 재료/위험 태그를 추출합니다.
     * "ingredients" 또는 "[재료]" 줄 이후는 재료, "steps" 또는 "[조리 순서]" 줄 이후는 조리 단계로 봅니다.
     */
    private ParsedRecipe parse(String content) {
        List<String> ingredients = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        List<String> core = new ArrayList<>();
        List<String> optional = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        String title = "";
        String description = "";
        String section = "";
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("title:")) {
                title = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                continue;
            }
            if (lower.startsWith("description:")) {
                description = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                continue;
            }
            if (lower.startsWith("ingredients") || trimmed.equals("[재료]")) {
                section = "ingredients";
                continue;
            }
            if (lower.startsWith("steps") || trimmed.equals("[조리 순서]")) {
                section = "steps";
                continue;
            }
            if (lower.startsWith("core:")) {
                core.addAll(splitCsv(trimmed.substring(trimmed.indexOf(':') + 1)));
                continue;
            }
            if (lower.startsWith("optional:")) {
                optional.addAll(splitCsv(trimmed.substring(trimmed.indexOf(':') + 1)));
                continue;
            }
            if (lower.startsWith("risk:")) {
                risks.addAll(splitCsv(trimmed.substring(trimmed.indexOf(':') + 1)));
                continue;
            }
            String value = trimmed.replaceFirst("^[-*]\\s*", "").replaceFirst("^\\d+[.)]\\s*", "").trim();
            if ("ingredients".equals(section)) {
                ingredients.add(value);
            } else if ("steps".equals(section)) {
                if (isInstructionStep(value)) {
                    steps.add(value);
                }
            }
        }
        return new ParsedRecipe(title, description, AgentText.distinct(ingredients), AgentText.distinct(steps),
                AgentText.distinct(core), AgentText.distinct(optional), AgentText.distinct(risks));
    }

    private boolean isInstructionStep(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String normalized = line.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[팁]") || normalized.startsWith("팁:") || normalized.startsWith("tip:")
                || normalized.startsWith("[tip]") || normalized.startsWith("*참고") || normalized.startsWith("※참고")
                || normalized.startsWith("[참고]") || normalized.startsWith("*주의") || normalized.startsWith("[주의]")) {
            return false;
        }
        if (normalized.contains("대체가능") || normalized.contains("추천합") || normalized.contains("추천해") || normalized.contains("추천드")) {
            return hasCookingAction(normalized);
        }
        return true;
    }

    private boolean hasCookingAction(String text) {
        return text.contains("볶") || text.contains("녹이") || text.contains("굽") || text.contains("끓")
                || text.contains("섞") || text.contains("넣") || text.contains("반죽") || text.contains("성형")
                || text.contains("썰") || text.contains("갈") || text.contains("붓") || text.contains("식히")
                || text.contains("데치") || text.contains("튀기") || text.contains("바르") || text.contains("뿌리");
    }

    // 쉼표로 구분된 값을 공백 정리 후 목록으로 만듭니다.
    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(",")).stream().map(String::trim).filter(token -> !token.isBlank()).toList();
    }

    private String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ParsedRecipe(
            String title,
            String description,
            List<String> ingredients,
            List<String> steps,
            List<String> coreIngredients,
            List<String> optionalIngredients,
            List<String> healthRiskTags
    ) {
    }
}
