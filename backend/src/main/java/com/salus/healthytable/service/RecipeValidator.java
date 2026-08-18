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

@Slf4j
@Component
public class RecipeValidator {

    private static final double MIN_CONFIDENCE_SCORE = 0.50;
    private static final double MIN_GENERATED_INGREDIENT_COVERAGE = 0.65;
    private static final double MIN_EVIDENCE_COVERAGE = 0.45;
    private static final double MIN_PROCESS_COVERAGE = 0.40;
    private static final int MIN_EVIDENCE_INGREDIENTS = 1;

    private static final List<String> GENERIC_FORBIDDEN_INGREDIENTS = List.of(
            "케첩", "마요네즈", "고형카레", "카레가루", "짜장", "춘장"
    );

    private static final Set<String> COMMON_SEASONINGS = Set.of(
            "물", "소금", "후추", "설탕", "간장", "식용유", "참기름", "올리브유", "깨", "통깨",
            "다진마늘", "마늘", "대파", "파", "양파", "맛술", "청주", "미림", "고춧가루"
    );

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

    private static final Set<String> GENERIC_INGREDIENT_WORDS = Set.of(
            "재료", "주재료", "부재료", "양념", "소스", "약간", "적당량", "기호", "분량", "기본", "선택",
            "레시피", "조리", "요리", "만드는법", "만들기", "준비", "손질", "완성", "접시", "그릇",
            "팬", "냄비", "볼", "불", "약불", "중불", "강불", "마지막", "정도", "동안", "후", "전",
            "넣고", "넣어", "넣은", "넣습니다", "넣어줍니다", "구워", "구워줍니다", "졸여", "졸여줍니다",
            "큰술", "작은술", "컵", "개", "쪽", "대", "장", "줌", "모", "g", "kg", "ml", "l"
    );

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

    private static final Pattern MINUTE_PATTERN = Pattern.compile("(\\d{1,3})\\s*분");
    private static final Pattern HOUR_PATTERN = Pattern.compile("(\\d{1,2})\\s*시간");

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

    public ValidationResult validate(Recipe recipe, String searchContext, String rawResponse) {
        return validate(recipe, searchContext, rawResponse, null);
    }

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

        boolean hasPolicyViolation = validateGenericPolicy(title, ingredientText, reasons);

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

        int matchedGeneratedIngredients = countMatches(generated.ingredients(), evidence.ingredients());
        int totalGeneratedIngredients = generated.ingredients().size();
        double generatedIngredientScore = totalGeneratedIngredients == 0
                ? 0.0
                : (double) matchedGeneratedIngredients / totalGeneratedIngredients;

        int matchedEvidenceIngredients = countMatches(evidence.importantIngredients(), generated.ingredients());
        int totalEvidenceIngredients = evidence.importantIngredients().size();
        double evidenceCoverageScore = totalEvidenceIngredients == 0
                ? 0.0
                : (double) matchedEvidenceIngredients / totalEvidenceIngredients;

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

    private boolean validateFormat(Recipe recipe, String rawResponse, List<String> reasons) {
        boolean formatValid = true;
        if (rawResponse == null || !rawResponse.contains("[재료]") || !rawResponse.contains("[조리 순서]")) {
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

    private String extractPrimaryIngredient(String ingredient) {
        String normalized = normalize(ingredient
                .replaceAll("\\(.*?\\)", " ")
                .replaceAll("\\[.*?\\]", " ")
                .replaceAll("\\d+(\\.\\d+)?\\s*(g|kg|ml|l|개|큰술|작은술|컵|모|대|쪽|줌|장|스푼|티스푼|t|T)", " ")
                .replaceAll("[,:;·/]", " "));
        List<String> candidates = extractIngredientCandidates(normalized).stream().toList();
        return candidates.isEmpty() ? "" : candidates.get(0);
    }

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

    private boolean isMeaningfulIngredientToken(String token) {
        if (token == null || token.length() < 2) {
            return false;
        }
        if (COMMON_SEASONINGS.contains(token) || GENERIC_INGREDIENT_WORDS.contains(token)) {
            return false;
        }
        return token.matches(".*[가-힣a-zA-Z].*");
    }

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

    private boolean sameIngredient(String left, String right) {
        String a = compact(left);
        String b = compact(right);
        if (a.equals(b)) {
            return true;
        }
        Set<String> chickenCuts = Set.of("닭고기", "닭날개", "닭봉", "닭윙", "닭다리", "닭가슴살", "닭안심");
        return chickenCuts.contains(a) && chickenCuts.contains(b);
    }

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

    private record EvidenceProfile(
            Set<String> ingredients,
            Set<String> importantIngredients,
            Set<String> verbGroups,
            List<Integer> minutes
    ) {}

    private record RecipeProfile(
            Set<String> ingredients,
            Set<String> verbGroups
    ) {}

    private enum IngredientRole {
        CORE_INGREDIENT,
        OPTIONAL_INGREDIENT,
        PANTRY_STAPLE,
        SEASONING,
        UNKNOWN
    }
}
