package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 생성된 레시피가 "검색 근거(searchContext)"와 얼마나 일치하는지 검증하는 클래스입니다.
 *
 * RecipeDraftValidator가 구조/규칙을 본다면, 이 클래스는 근거 기반 신뢰도를 봅니다.
 * - 형식: [재료]/[조리 순서] 섹션 존재
 * - 정책 금지 재료, 제목이 근거에서 확인되는지
 * - 재료 일치도: 생성 재료 중 근거에 있는 비율, 근거의 핵심 재료 중 생성 결과에 반영된 비율
 * - 근거에 없는 핵심 재료가 새로 들어갔는지, 조리 방식/조리 시간이 근거와 크게 다른지
 * 위 점수를 가중합한 신뢰 점수(confidenceScore)가 기준 미만이면 통과시키지 않습니다.
 */
@Slf4j
@Component
public class RecipeValidator {

    // 통과 기준값: 종합 신뢰 점수 / 생성 재료의 근거 일치율 / 근거 핵심 재료 반영률 / 조리 방식 일치율 / 근거에서 찾은 최소 재료 수
    private static final double MIN_CONFIDENCE_SCORE = 0.50;
    private static final double MIN_GENERATED_INGREDIENT_COVERAGE = 0.65;
    private static final double MIN_EVIDENCE_COVERAGE = 0.45;
    private static final double MIN_PROCESS_COVERAGE = 0.40;
    private static final int MIN_EVIDENCE_INGREDIENTS = 1;

    // 제목에 없는데 재료에 들어가면 레시피를 왜곡할 가능성이 큰 재료(예: 된장찌개에 케첩)
    private static final List<String> GENERIC_FORBIDDEN_INGREDIENTS = List.of(
            "케첩", "마요네즈", "고형카레", "카레가루", "짜장", "춘장"
    );

    // 흔한 양념/기본 재료. 재료 후보 추출 시 제외합니다.
    private static final Set<String> COMMON_SEASONINGS = Set.of(
            "물", "소금", "후추", "설탕", "간장", "식용유", "참기름", "올리브유", "깨", "통깨",
            "다진마늘", "마늘", "대파", "파", "양파", "맛술", "청주", "미림", "고춧가루"
    );

    // 아래 집합들은 재료의 역할을 구분합니다: 기본 비치 재료 / 양념 / 선택 재료 / 결과를 크게 바꾸는 재료
    // 기본 재료·양념·선택 재료는 근거에 없어도 "근거 없는 핵심 재료"로 보지 않습니다.
    private static final Set<String> PANTRY_STAPLES = Set.of(
            "물", "식용유", "참기름", "올리브유", "다진마늘", "마늘", "대파", "파"
    );

    private static final Set<String> SEASONINGS = Set.of(
            "소금", "후추", "간장", "국간장", "고춧가루", "고추장", "된장", "맛술", "청주", "미림"
    );

    private static final Set<String> OPTIONAL_INGREDIENTS = Set.of(
            "깨", "통깨", "멸치", "육수용멸치", "다시마", "육수"
    );

    private static final Set<String> RESULT_CHANGING_INGREDIENTS = Set.of(
            "생크림", "크림", "치즈", "우유", "버터", "설탕", "견과류", "과일"
    );

    // 재료 이름이 아닌 일반 단어(단위, 조리 동사, 도구 등). 재료 후보에서 제외합니다.
    private static final Set<String> GENERIC_INGREDIENT_WORDS = Set.of(
            "재료", "주재료", "부재료", "양념", "소스", "약간", "적당량", "기호", "분량", "기본", "선택",
            "레시피", "조리", "요리", "만드는법", "만들기", "준비", "손질", "완성", "접시", "그릇",
            "팬", "냄비", "볼", "불", "약불", "중불", "강불", "마지막", "정도", "동안", "후", "전",
            "넣고", "넣어", "넣은", "넣습니다", "넣어줍니다", "구워", "구워줍니다", "졸여", "졸여줍니다",
            "큰술", "작은술", "컵", "개", "쪽", "대", "장", "줌", "모", "g", "kg", "ml", "l"
    );

    // 조리 방식 그룹과 그 방식을 나타내는 한국어 어간. 근거와 생성 결과의 조리 방식이 비슷한지 비교합니다.
    private static final Map<String, List<String>> COOKING_VERB_GROUPS = Map.of(
            "boil", List.of("삶", "데치", "끓", "우려"),
            "braise", List.of("졸", "조려", "조림", "끓여"),
            "stir_fry", List.of("볶"),
            "fry", List.of("튀"),
            "grill", List.of("굽", "구워"),
            "steam", List.of("찌", "찜"),
            "mix", List.of("무치", "버무", "섞"),
            "marinate", List.of("재우", "숙성")
    );

    // "15분", "1시간" 같은 시간 표현과 [재료] 섹션 제목을 찾는 정규식
    private static final Pattern MINUTE_PATTERN = Pattern.compile("(\\d{1,3})\\s*분");
    private static final Pattern HOUR_PATTERN = Pattern.compile("(\\d{1,2})\\s*시간");
    private static final Pattern INGREDIENT_HEADER_PATTERN = Pattern.compile(
            "\\[재료(?:\\s*-\\s*\\d+인분)?\\]");

    /**
     * 근거 기반 검증 결과입니다.
     * - valid: 최종 통과 여부 / formatValid: 형식 검사 통과 / hasForbidden: 정책 금지 재료 포함
     * - confidenceScore: 0~1 종합 신뢰 점수 / matchedKeywords, totalKeywords: 근거와 일치한 생성 재료 수 / 전체 생성 재료 수
     * - dataQualityLow, dataQualityWarnings: 통과 여부와 별개인 품질 경고 (경고가 있으면 레시피 DB에 저장하지 않음)
     * - reasons: 실패 이유
     */
    public record ValidationResult(
            boolean valid,
            boolean formatValid,
            boolean hasForbidden,
            double confidenceScore,
            int matchedKeywords,
            int totalKeywords,
            boolean dataQualityLow,
            List<String> dataQualityWarnings,
            List<String> reasons
    ) {}

    // 자유 형식 텍스트 레시피(구조화 초안 없음)를 검증합니다.
    public ValidationResult validate(Recipe recipe, String searchContext, String rawResponse) {
        return validate(recipe, searchContext, rawResponse, null);
    }

    // 구조화 초안이 있는 레시피를 검증합니다. 조리 시간 품질 검사에 초안의 단계별 minutes를 사용합니다.
    public ValidationResult validateStructured(
            Recipe recipe,
            String searchContext,
            String rawResponse,
            GeneratedRecipeDraft structuredDraft) {
        return validate(recipe, searchContext, rawResponse, structuredDraft);
    }

    private ValidationResult validate(
            Recipe recipe,
            String searchContext,
            String rawResponse,
            GeneratedRecipeDraft structuredDraft) {
        List<String> reasons = new ArrayList<>();

        if (recipe == null) {
            reasons.add("레시피 객체가 생성되지 않았습니다.");
            return new ValidationResult(false, false, false, 0.0, 0, 0, false, List.of(), reasons);
        }

        boolean formatValid = validateFormat(recipe, rawResponse, reasons);
        List<String> dataQualityWarnings = new ArrayList<>();
        String title = normalize(recipe.getTitle());
        String context = normalize(searchContext);
        String ingredientText = normalize(String.join(" ", safeList(recipe.getIngredients())));
        String stepText = normalize(String.join(" ", safeList(recipe.getSteps())));

        // 1) 정책 금지 재료 검사
        boolean hasPolicyViolation = validateGenericPolicy(title, ingredientText, reasons);

        // 2) 생성 레시피와 검색 근거에서 재료/조리 방식/시간 정보를 뽑아 비교할 준비를 합니다.
        RecipeProfile generated = buildRecipeProfile(recipe, ingredientText, stepText);
        EvidenceProfile evidence = buildEvidenceProfile(context, generated.ingredients());

        boolean titleGrounded = isTitleGrounded(title, context);
        if (!titleGrounded) {
            reasons.add("요청 음식명과 생성된 레시피 제목이 검색 근거에서 확인되지 않습니다.");
        }

        if (context.isBlank()) {
            reasons.add("RAG 외부 검색 지식 컨텍스트가 주어지지 않았습니다.");
        }
        if (evidence.ingredients().size() < MIN_EVIDENCE_INGREDIENTS) {
            dataQualityWarnings.add("검색 근거 snippet에서 생성 재료와 직접 매칭되는 핵심 재료를 충분히 찾지 못했습니다.");
        }

        // 3) 생성 재료 중 근거에서 확인된 비율
        int matchedGeneratedIngredients = countMatches(generated.ingredients(), evidence.ingredients());
        int totalGeneratedIngredients = generated.ingredients().size();
        double generatedIngredientScore = totalGeneratedIngredients == 0
                ? 0.0
                : (double) matchedGeneratedIngredients / totalGeneratedIngredients;

        // 4) 근거의 핵심 재료 중 생성 결과에 반영된 비율
        int matchedEvidenceIngredients = countMatches(evidence.importantIngredients(), generated.ingredients());
        int totalEvidenceIngredients = evidence.importantIngredients().size();
        double evidenceCoverageScore = totalEvidenceIngredients == 0
                ? 0.0
                : (double) matchedEvidenceIngredients / totalEvidenceIngredients;

        // 5) 결과를 크게 바꾸는 재료(크림, 치즈 등)나 역할을 알 수 없는 재료가 근거에 없으면 "근거 없는 핵심 재료"로 실패 처리
        List<String> unsupportedIngredients = generated.ingredients().stream()
                .filter(ingredient -> ingredientRole(ingredient) == IngredientRole.CORE_INGREDIENT
                        || ingredientRole(ingredient) == IngredientRole.UNKNOWN)
                .filter(ingredient -> !evidenceContainsIngredient(context, ingredient))
                .toList();
        if (!unsupportedIngredients.isEmpty()) {
            reasons.add("검색 근거에 없는 핵심 재료가 생성 결과에 포함되었습니다: "
                    + String.join(", ", unsupportedIngredients));
        }

        double processScore = calculateProcessScore(evidence.verbGroups(), generated.verbGroups(), dataQualityWarnings);
        double timeScore = calculateTimeScore(evidence.minutes(), recipe.getCookingTime(), reasons);
        if (structuredDraft == null) {
            validateInternalRecipeQuality(recipe, ingredientText, stepText, dataQualityWarnings);
        } else {
            validateStructuredRecipeQuality(recipe, structuredDraft, dataQualityWarnings);
        }

        // 6) 종합 신뢰 점수 = 생성 재료 일치율 45% + 근거 재료 반영률 35% + 조리 방식 10% + 조리 시간 10%
        double confidenceScore = round(
                generatedIngredientScore * 0.45
                        + evidenceCoverageScore * 0.35
                        + processScore * 0.10
                        + timeScore * 0.10);

        if (generatedIngredientScore < 0.65) {
            dataQualityWarnings.add(String.format(Locale.ROOT,
                    "생성 재료의 검색 근거 일치도가 낮습니다. 점수: %.2f (%d/%d)",
                    generatedIngredientScore, matchedGeneratedIngredients, totalGeneratedIngredients));
        }
        if (evidenceCoverageScore < 0.45) {
            dataQualityWarnings.add(String.format(Locale.ROOT,
                    "검색 근거의 핵심 재료가 생성 결과에 충분히 반영되지 않았습니다. 점수: %.2f (%d/%d)",
                    evidenceCoverageScore, matchedEvidenceIngredients, totalEvidenceIngredients));
        }
        if (confidenceScore < MIN_CONFIDENCE_SCORE) {
            dataQualityWarnings.add(String.format(Locale.ROOT,
                    "종합 신뢰 점수가 기준치 미만입니다. 점수: %.2f / 기준: %.2f",
                    confidenceScore, MIN_CONFIDENCE_SCORE));
        }

        boolean contentSufficient = structuredDraft == null
                ? safeList(recipe.getIngredients()).size() >= 3 && safeList(recipe.getSteps()).size() >= 3
                : !safeList(recipe.getIngredients()).isEmpty() && !safeList(recipe.getSteps()).isEmpty();
        if (!contentSufficient) {
            reasons.add("레시피로 제공하기에는 재료 또는 조리 순서가 부족합니다.");
        }

        // 7) 모든 조건을 만족하고 실패 이유(reasons)가 하나도 없어야 최종 통과입니다.
        boolean hasForbidden = hasPolicyViolation;
        boolean evidenceSufficient = matchedGeneratedIngredients >= MIN_EVIDENCE_INGREDIENTS
                && generatedIngredientScore >= MIN_GENERATED_INGREDIENT_COVERAGE
                && evidenceCoverageScore >= MIN_EVIDENCE_COVERAGE
                && unsupportedIngredients.isEmpty();
        boolean processSufficient = evidence.verbGroups().isEmpty() || processScore >= MIN_PROCESS_COVERAGE;
        boolean valid = formatValid
                && !hasForbidden
                && !context.isBlank()
                && contentSufficient
                && titleGrounded
                && evidenceSufficient
                && processSufficient
                && confidenceScore >= MIN_CONFIDENCE_SCORE
                && reasons.isEmpty();

        log.info("[RecipeValidator] Title: {}, Valid: {}, Confidence: {} ({}/{}), EvidenceCoverage: {}/{}, Reasons: {}",
                recipe.getTitle(), valid, confidenceScore, matchedGeneratedIngredients, totalGeneratedIngredients,
                matchedEvidenceIngredients, totalEvidenceIngredients, reasons);

        return new ValidationResult(
                valid,
                formatValid,
                hasForbidden,
                confidenceScore,
                matchedGeneratedIngredients,
                totalGeneratedIngredients,
                !dataQualityWarnings.isEmpty(),
                dataQualityWarnings,
                reasons);
    }

    // 답변에 [재료]/[조리 순서] 섹션이 있고, 파싱된 재료와 조리 순서 목록이 비어 있지 않은지 확인합니다.
    private boolean validateFormat(Recipe recipe, String rawResponse, List<String> reasons) {
        boolean formatValid = true;
        boolean hasIngredientHeader = rawResponse != null
                && INGREDIENT_HEADER_PATTERN.matcher(rawResponse).find();
        if (!hasIngredientHeader || !rawResponse.contains("[조리 순서]")) {
            formatValid = false;
            reasons.add("필수 포맷 헤더([재료] 또는 [조리 순서])가 유실되었습니다.");
        }
        if (recipe.getIngredients() == null || recipe.getIngredients().isEmpty()) {
            formatValid = false;
            reasons.add("재료 리스트가 비어있습니다.");
        }
        if (recipe.getSteps() == null || recipe.getSteps().isEmpty()) {
            formatValid = false;
            reasons.add("조리 순서 리스트가 비어있습니다.");
        }
        return formatValid;
    }

    // 제목에 없는 정책 금지 재료가 재료에 들어가 있으면 위반입니다(제목에 있으면 원래 들어가는 요리로 봄).
    private boolean validateGenericPolicy(String title, String ingredientText, List<String> reasons) {
        boolean violated = false;
        for (String forbidden : GENERIC_FORBIDDEN_INGREDIENTS) {
            String normalizedForbidden = normalize(forbidden);
            if (ingredientText.contains(normalizedForbidden) && !title.contains(normalizedForbidden)) {
                violated = true;
                reasons.add("정책적 금지 재료가 감지되었습니다: " + forbidden);
            }
        }
        return violated;
    }

    // 텍스트 레시피 품질 경고: 조리 순서에만 등장하는 재료, 단계별 시간 합계가 전체 시간의 1.4배 초과
    private void validateInternalRecipeQuality(Recipe recipe, String ingredientText, String stepText, List<String> warnings) {
        Set<String> declaredIngredients = buildRecipeProfile(recipe, ingredientText, "").ingredients();
        List<String> stepOnlyIngredients = extractIngredientCandidates(stepText).stream()
                .filter(ingredient -> !COMMON_SEASONINGS.contains(ingredient))
                .filter(ingredient -> !containsIngredientMatch(declaredIngredients, ingredient))
                .limit(8)
                .toList();
        if (!stepOnlyIngredients.isEmpty()) {
            warnings.add("조리 순서에만 등장하고 재료 목록에는 없는 항목이 있습니다: "
                    + String.join(", ", stepOnlyIngredients));
        }

        if (recipe.getCookingTime() != null) {
            List<Integer> stepMinutes = extractMinutes(stepText);
            int stepMinuteSum = stepMinutes.stream().mapToInt(Integer::intValue).sum();
            if (stepMinuteSum > Math.round(recipe.getCookingTime() * 1.4)) {
                warnings.add(String.format(Locale.ROOT,
                        "상단 조리 시간과 조리 순서의 시간 합계가 맞지 않을 수 있습니다. 상단: %d분 / 순서 내 시간 합계: %d분",
                        recipe.getCookingTime(), stepMinuteSum));
            }
        }
    }

    // 구조화 레시피 품질 경고: 단계별 minutes 합계가 전체 조리 시간의 1.4배를 넘는 경우
    private void validateStructuredRecipeQuality(
            Recipe recipe,
            GeneratedRecipeDraft structuredDraft,
            List<String> warnings) {
        if (recipe.getCookingTime() == null) {
            return;
        }
        int stepMinuteSum = safeSteps(structuredDraft).stream()
                .filter(step -> step != null && step.minutes() != null && step.minutes() > 0)
                .mapToInt(GeneratedCookingStep::minutes)
                .sum();
        if (stepMinuteSum > Math.round(recipe.getCookingTime() * 1.4)) {
            warnings.add(String.format(Locale.ROOT,
                    "상단 조리 시간과 조리 순서의 시간 합계가 맞지 않을 수 있습니다. 상단: %d분 / 순서 내 시간 합계: %d분",
                    recipe.getCookingTime(), stepMinuteSum));
        }
    }

    // 검색 근거에서 생성 재료 중 확인된 재료, 그중 핵심 재료, 조리 방식, 시간 표현을 뽑습니다.
    private EvidenceProfile buildEvidenceProfile(String context, Set<String> generatedIngredients) {
        Set<String> ingredients = generatedIngredients.stream()
                .filter(ingredient -> evidenceContainsIngredient(context, ingredient))
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        return new EvidenceProfile(
                ingredients,
                selectImportantIngredients(context, ingredients, generatedIngredients),
                extractVerbGroups(context),
                extractMinutes(context));
    }

    // 생성 레시피의 재료 줄마다 대표 재료 이름을 뽑고, 조리 순서에서 조리 방식을 뽑습니다.
    private RecipeProfile buildRecipeProfile(Recipe recipe, String ingredientText, String stepText) {
        Set<String> ingredients = new LinkedHashSet<>();
        for (String ingredient : safeList(recipe.getIngredients())) {
            String keyword = extractPrimaryIngredient(ingredient);
            if (!keyword.isBlank()) {
                ingredients.add(keyword);
            }
        }
        if (ingredients.isEmpty()) {
            ingredients.addAll(extractIngredientCandidates(ingredientText));
        }
        return new RecipeProfile(ingredients, extractVerbGroups(stepText));
    }

    /**
     * 근거의 핵심 재료를 고릅니다. 기본 재료/양념/선택 재료는 빼고, 근거에 자주 등장한 순으로 최대 8개입니다.
     * 근거에서 하나도 찾지 못하면 생성 재료를 후보로 사용합니다.
     */
    private Set<String> selectImportantIngredients(String context, Set<String> evidenceIngredients, Set<String> generatedIngredients) {
        Map<String, Integer> scored = new LinkedHashMap<>();
        for (String ingredient : evidenceIngredients) {
            IngredientRole role = ingredientRole(ingredient);
            if (role == IngredientRole.PANTRY_STAPLE
                    || role == IngredientRole.SEASONING
                    || role == IngredientRole.OPTIONAL_INGREDIENT) {
                continue;
            }
            int count = countOccurrences(context, ingredient);
            scored.put(ingredient, count);
        }
        if (scored.isEmpty()) {
            for (String ingredient : generatedIngredients) {
                IngredientRole role = ingredientRole(ingredient);
                if (role == IngredientRole.PANTRY_STAPLE
                        || role == IngredientRole.SEASONING
                        || role == IngredientRole.OPTIONAL_INGREDIENT) {
                    continue;
                }
                scored.put(ingredient, evidenceContainsIngredient(context, ingredient) ? 1 : 0);
            }
        }
        return scored.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(8)
                .map(Map.Entry::getKey)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
    }

    // "양파 1개 (채 썰기)"에서 괄호와 수량/단위를 지우고 첫 번째 재료 단어("양파")를 대표 이름으로 뽑습니다.
    private String extractPrimaryIngredient(String ingredient) {
        String normalized = normalize(ingredient
                .replaceAll("\\(.*?\\)", " ")
                .replaceAll("\\[.*?\\]", " ")
                .replaceAll("\\d+(\\.\\d+)?\\s*(g|kg|ml|l|개|큰술|작은술|컵|모|대|쪽|줌|장|스푼|티스푼|t|T)", " ")
                .replaceAll("[,:;·/]", " "));
        List<String> candidates = extractIngredientCandidates(normalized).stream().toList();
        return candidates.isEmpty() ? "" : candidates.get(0);
    }

    // 텍스트를 단어로 나누고 기호/조사를 제거한 뒤 재료처럼 보이는 단어만 모읍니다.
    private Set<String> extractIngredientCandidates(String text) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String token : normalize(text).split("\\s+")) {
            String cleaned = cleanIngredientToken(token);
            if (isMeaningfulIngredientToken(cleaned)) {
                candidates.add(cleaned);
            }
        }
        return candidates;
    }

    private String cleanIngredientToken(String token) {
        if (token == null) {
            return "";
        }
        return token.replaceAll("^[\\-•*]+", "")
                .replaceAll("[()\\[\\]{}]", "")
                .replaceAll("(은|는|이|가|을|를|의|에|로|으로|와|과|도|만)$", "")
                .trim();
    }

    // 2글자 이상이고, 흔한 양념/일반 단어가 아니며, 문자를 포함하면 재료 후보로 인정합니다.
    private boolean isMeaningfulIngredientToken(String token) {
        if (token == null || token.length() < 2) {
            return false;
        }
        if (COMMON_SEASONINGS.contains(token) || GENERIC_INGREDIENT_WORDS.contains(token)) {
            return false;
        }
        return token.matches(".*[가-힣a-zA-Z].*");
    }

    // 텍스트에 등장하는 조리 방식 그룹(boil, stir_fry 등)을 찾습니다.
    private Set<String> extractVerbGroups(String text) {
        Set<String> groups = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : COOKING_VERB_GROUPS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (text.contains(normalize(keyword))) {
                    groups.add(entry.getKey());
                    break;
                }
            }
        }
        return groups;
    }

    // 텍스트의 "N시간"(분으로 환산)과 "N분" 값을 모두 모읍니다.
    private List<Integer> extractMinutes(String text) {
        List<Integer> minutes = new ArrayList<>();
        Matcher hourMatcher = HOUR_PATTERN.matcher(text);
        while (hourMatcher.find()) {
            minutes.add(Integer.parseInt(hourMatcher.group(1)) * 60);
        }
        Matcher minuteMatcher = MINUTE_PATTERN.matcher(text);
        while (minuteMatcher.find()) {
            minutes.add(Integer.parseInt(minuteMatcher.group(1)));
        }
        return minutes;
    }

    // 근거의 조리 방식 중 생성 결과에 반영된 비율. 근거에서 조리 방식을 못 찾으면 중립값 0.6을 줍니다.
    private double calculateProcessScore(Set<String> evidenceGroups, Set<String> generatedGroups, List<String> warnings) {
        if (evidenceGroups.isEmpty()) {
            return 0.6;
        }
        int matched = countMatches(generatedGroups, evidenceGroups);
        double score = (double) matched / evidenceGroups.size();
        if (score < 0.4) {
            warnings.add("검색 근거의 대표 조리 방식이 생성 조리 순서에 충분히 반영되지 않았을 수 있습니다.");
        }
        return score;
    }

    // 근거에 50분 이상 걸리는 과정이 있는데 생성 시간이 그 60% 미만이면 비현실적으로 짧다고 보고 실패 처리합니다.
    private double calculateTimeScore(List<Integer> evidenceMinutes, Integer generatedMinutes, List<String> reasons) {
        if (evidenceMinutes.isEmpty() || generatedMinutes == null) {
            return 0.6;
        }
        int maxEvidenceMinute = evidenceMinutes.stream().max(Integer::compareTo).orElse(0);
        if (maxEvidenceMinute >= 50 && generatedMinutes < Math.round(maxEvidenceMinute * 0.6)) {
            reasons.add(String.format(
                    "검색 근거 대비 조리 시간이 지나치게 짧습니다. 생성: %d분 / 근거 최대: %d분",
                    generatedMinutes, maxEvidenceMinute));
            return 0.0;
        }
        return 1.0;
    }

    private int countMatches(Set<String> left, Set<String> right) {
        int count = 0;
        for (String value : left) {
            if (containsIngredientMatch(right, value)) {
                count++;
            }
        }
        return count;
    }

    // 근거 텍스트(공백 제거)에 재료 이름이나 그 별칭이 포함되어 있는지 확인합니다.
    private boolean evidenceContainsIngredient(String context, String ingredient) {
        if (ingredient == null || ingredient.isBlank()) {
            return false;
        }
        String compactContext = compact(context);
        String compactIngredient = compact(ingredient);
        if (compactContext.contains(compactIngredient)) {
            return true;
        }
        for (String alias : ingredientAliases(compactIngredient)) {
            if (compactContext.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    // 제목이 근거 본문에 있거나, 근거의 "검색어:" 줄과 서로 포함 관계이면 근거가 있는 제목으로 봅니다.
    private boolean isTitleGrounded(String title, String context) {
        String compactTitle = compact(title);
        String compactContext = compact(context);
        if (compactTitle.isBlank() || compactContext.isBlank()) {
            return false;
        }
        if (compactContext.contains(compactTitle)) {
            return true;
        }
        Matcher matcher = Pattern.compile("(?m)^검색어\\s*:\\s*(.+)$").matcher(context);
        if (!matcher.find()) {
            return false;
        }
        String query = compact(matcher.group(1));
        return !query.isBlank() && (compactTitle.contains(query) || query.contains(compactTitle));
    }

    private boolean containsIngredientMatch(Set<String> candidates, String target) {
        for (String candidate : candidates) {
            if (sameIngredient(candidate, target)) {
                return true;
            }
        }
        return false;
    }

    // 같은 재료인지 비교합니다. 닭고기 부위(닭날개, 닭다리 등)끼리는 같은 재료로 봅니다.
    private boolean sameIngredient(String left, String right) {
        String a = compact(left);
        String b = compact(right);
        if (a.equals(b)) {
            return true;
        }
        Set<String> chickenCuts = Set.of("닭고기", "닭날개", "닭봉", "닭윙", "닭다리", "닭가슴살", "닭안심");
        return chickenCuts.contains(a) && chickenCuts.contains(b);
    }

    // 근거 검색 시 함께 찾을 별칭(영문 표기, 상위 재료명 등)
    private Set<String> ingredientAliases(String ingredient) {
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(ingredient);
        if (ingredient.startsWith("닭") && ingredient.length() > 1) {
            aliases.add("닭고기");
        }
        if (ingredient.equals("베이컨")) {
            aliases.add("bacon");
        }
        if (ingredient.equals("토마토")) {
            aliases.add("tomato");
        }
        if (ingredient.equals("파스타")) {
            aliases.add("스파게티");
            aliases.add("면");
            aliases.add("pasta");
        }
        return aliases;
    }

    // 재료 이름으로 역할을 분류합니다.
    private IngredientRole ingredientRole(String ingredient) {
        String compact = compact(ingredient);
        if (PANTRY_STAPLES.contains(compact)) {
            return IngredientRole.PANTRY_STAPLE;
        }
        if (SEASONINGS.contains(compact)) {
            return IngredientRole.SEASONING;
        }
        if (OPTIONAL_INGREDIENTS.contains(compact)) {
            return IngredientRole.OPTIONAL_INGREDIENT;
        }
        if (RESULT_CHANGING_INGREDIENTS.contains(compact)) {
            return IngredientRole.CORE_INGREDIENT;
        }
        return IngredientRole.UNKNOWN;
    }

    private String compact(String value) {
        return normalize(value).replaceAll("\\s+", "");
    }

    private int countOccurrences(String text, String keyword) {
        int count = 0;
        int index = text.indexOf(keyword);
        while (index >= 0) {
            count++;
            index = text.indexOf(keyword, index + keyword.length());
        }
        return count;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private List<GeneratedCookingStep> safeSteps(GeneratedRecipeDraft draft) {
        return draft == null || draft.steps() == null ? List.of() : draft.steps();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // 검색 근거에서 뽑은 비교용 정보
    private record EvidenceProfile(
            Set<String> ingredients,
            Set<String> importantIngredients,
            Set<String> verbGroups,
            List<Integer> minutes
    ) {}

    // 생성 레시피에서 뽑은 비교용 정보
    private record RecipeProfile(
            Set<String> ingredients,
            Set<String> verbGroups
    ) {}

    // CORE: 결과를 크게 바꾸는 재료, OPTIONAL: 선택 재료, PANTRY_STAPLE: 기본 비치 재료, SEASONING: 양념, UNKNOWN: 분류 불가
    private enum IngredientRole {
        CORE_INGREDIENT,
        OPTIONAL_INGREDIENT,
        PANTRY_STAPLE,
        SEASONING,
        UNKNOWN
    }
}
