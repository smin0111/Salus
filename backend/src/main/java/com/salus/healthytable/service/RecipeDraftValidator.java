package com.salus.healthytable.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * LLM이 생성한 구조화 레시피 초안(GeneratedRecipeDraft)을 규칙 기반으로 검증하는 클래스입니다.
 *
 * LLM 출력은 JSON Schema를 지켜도 내용이 틀릴 수 있어, 아래와 같은 항목을 코드로 다시 확인합니다.
 * - 필수 필드, 요청한 요리 이름과의 일치, 재료 단위/수량/중복, 조리 단계 순서
 * - 가열 단계의 불 세기/시간/완료 기준, 무가열 메뉴의 가열 지시 여부
 * - 고위험 단백질(고기, 달걀, 해산물)의 안전 익힘 기준
 * - 재료 대체/제외 요청 반영 여부, 전체 조리 시간과 단계별 시간의 일치
 * - 명시적인 채식/육류 제한 위반
 *
 * 결과 종류: 통과(ok) / 복구 가능한 실패(retryable → LLM repair 시도) / 즉시 차단(blocking)
 * 각 실패는 코드(codes, 집계용)와 사람이 읽는 이유(reasons, repair 프롬프트에도 전달)를 함께 남깁니다.
 */
@Component
public class RecipeDraftValidator {

    // 재료 별칭(달걀/계란 등)을 같은 재료로 비교하기 위한 도우미
    private final IngredientAliasNormalizer ingredientAliasNormalizer = new IngredientAliasNormalizer();
    private static final Set<String> ALLOWED_UNITS = Set.of(
            "g", "kg", "ml", "L", "개", "장", "대", "모", "컵", "큰술", "작은술", "약간");
    // "불을 쓰지 않음"으로 보는 불 세기 값
    private static final Set<String> NO_HEAT_LEVELS = Set.of("", "무가열", "해당 없음");
    // 조리 설명에 이 표현이 있으면 팬/냄비 가열 단계로 봅니다.
    private static final List<String> STOVETOP_ACTIONS = List.of(
            "끓", "볶", "굽", "삶", "데치", "튀", "졸", "찌", "익히", "팬에", "냄비에");
    // 기구 이름만 나오고 실제 가열이 아닌 취급 동작. 이 표현만 있으면 온도·시간을 요구하지 않습니다.
    private static final List<String> NON_HEATING_HANDLING = List.of(
            "꺼내", "식히", "덜어", "담아", "접시", "그릇", "플레이팅");
    // 조리 설명에 이 표현이 있으면 오븐/에어프라이어 단계로 봅니다(온도 필수).
    private static final List<String> OVEN_ACTIONS = List.of(
            "오븐", "에어프라이어", "에어 프라이어", "에프에", "베이크");
    // 덜 익히면 식중독 위험이 큰 단백질 재료
    private static final List<String> HIGH_RISK_PROTEINS = List.of(
            "돼지고기", "소고기", "쇠고기", "닭고기", "오리고기", "다짐육", "햄버거패티",
            "계란", "달걀", "생선", "연어", "고등어", "참치", "오징어", "문어", "새우", "게", "조개");
    // 속까지 안전하게 익었는지 확인하는 표현
    private static final List<String> SAFE_DONENESS_CUES = List.of(
            "중심까지", "속까지", "완전히 익", "충분히 익", "중심 온도", "중심온도",
            "핏물이 없", "분홍색이 없", "불투명", "살이 하얗", "응고", "굳", "익은 상태");
    // 제조 과정에서 이미 가열·멸균된 형태. 생재료와 달리 "중심까지 익힘" 확인이 필요 없습니다.
    // "참치캔"이 "참치"에 부분 일치해 생선회처럼 취급되는 오탐을 막습니다.
    private static final List<String> PRECOOKED_FORMS = List.of(
            "캔", "통조림", "훈제", "햄", "소시지", "어묵", "맛살", "게맛살", "액젓", "젓갈");
    // 가공품 표현과 겹쳐도 생고기인 형태. 이 표현이 있으면 가공품 예외를 적용하지 않습니다.
    // "햄버거패티"가 "햄"에 부분 일치해 익힘 확인에서 빠지던 문제를 막습니다. 베이컨은 대부분
    // 가열 전 상태로 팔리므로 가공품 예외에 넣지 않습니다.
    private static final List<String> RAW_FORM_MARKERS = List.of(
            "햄버거", "패티", "다짐육", "간고기", "간 고기", "베이컨");
    // 육류식 "중심까지 익히기" 안전 문구가 필요 없는 재료
    private static final List<String> LOW_RISK_INGREDIENTS = List.of(
            "두부", "감자", "고구마", "당근", "양파", "애호박", "버섯", "대파", "쪽파", "마늘", "김치");
    // 이미 과하게 조리된 상태를 나타내는 표현과, 그 상태를 더 악화시키는 지시 표현(복구 팁 모순 검사용)
    private static final List<String> OVERPROCESSED_STATES = List.of(
            "너무 부드러", "너무 익", "과하게 익", "물러", "퍼졌", "탔", "타면", "눌어붙",
            "말랐", "건조", "졸아들", "너무 짜", "과하게 짜");
    private static final List<String> MORE_HEAT_OR_TIME_ACTIONS = List.of(
            "더 오래", "계속 끓", "더 끓", "더 익", "불을 높", "불 세기를 높", "센불", "강불로",
            "시간을 늘", "가열 시간을 늘");
    // 사용자가 눈으로 확인할 수 없는 모호한 완료 기준(공백 제거 형태)
    private static final Set<String> VAGUE_COMPLETION_CUES = Set.of(
            "적당한상태", "알맞은상태", "잘된상태", "완성된상태", "조리가완료된상태", "다된상태");
    // 요리 종류를 나타내는 접미어. "김치" 요청에 "김치볶음밥"을 만들면 다른 요리로 바뀐 것으로 판단합니다.
    private static final List<String> DISH_TYPE_SUFFIXES = List.of(
            "볶음밥", "덮밥", "비빔밥", "파스타", "국수", "라면", "샐러드", "샌드위치",
            "찌개", "전골", "조림", "볶음", "구이", "튀김", "찜", "전", "국", "탕");

    /**
     * 초안을 검증합니다. 모든 규칙을 끝까지 실행해 실패 이유를 한꺼번에 모읍니다(repair 때 한 번에 고치도록).
     * 단, 채식/육류 제한 위반은 복구 대상이 아니라 즉시 차단(blocking)합니다.
     */
    public ValidationResult validate(RecipeGenerationRequest request, GeneratedRecipeDraft draft) {
        List<String> codes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        if (draft == null) {
            add(codes, reasons, "DRAFT_NULL", "레시피 JSON 객체가 비어 있습니다.");
            return ValidationResult.retryable(codes, reasons);
        }

        validateRequestedTitle(request, draft, codes, reasons);
        validateRequiredFields(draft, codes, reasons);
        validateNoLeakedAuthoringRules(draft, codes, reasons);
        validateIngredientAmountsAndDuplicates(draft, codes, reasons);
        validateStepOrder(draft, codes, reasons);
        validateIngredientUnits(draft, codes, reasons);
        validateHeatLevels(draft, codes, reasons);
        validateNoHeatRecipe(request, draft, codes, reasons);
        validateCookingStepDetails(draft, codes, reasons);
        validateRecoveryActions(draft, codes, reasons);
        validateSafetyNotes(draft, codes, reasons);
        validateStepIngredients(draft, codes, reasons);
        validateIngredientUseCoverage(draft, codes, reasons);
        validateHighRiskProteinSafety(draft, codes, reasons);
        validateRequestedSubstitutions(request, draft, codes, reasons);
        validateExcludedIngredients(request, draft, codes, reasons);
        validateCookingTime(draft, codes, reasons);

        if (violatesDietaryRestriction(request, draft, reasons)) {
            codes.add("DIETARY_RESTRICTION_CONFLICT");
            return ValidationResult.blocking(codes, reasons);
        }

        return codes.isEmpty()
                ? ValidationResult.ok()
                : ValidationResult.retryable(codes, reasons);
    }

    // 필수 필드와 기본 값 범위(인분 1 이상, 난이도 1~3 등)를 검사합니다. 열량은 선택 값이지만 넣었다면 1 이상이어야 합니다.
    private void validateRequiredFields(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        if (isBlank(draft.title())) {
            add(codes, reasons, "TITLE_REQUIRED", "title은 필수입니다.");
        }
        if (isBlank(draft.description())) {
            add(codes, reasons, "DESCRIPTION_REQUIRED", "description은 필수입니다.");
        }
        if (draft.servings() == null || draft.servings() <= 0) {
            add(codes, reasons, "SERVINGS_REQUIRED", "servings는 1 이상의 값이어야 합니다.");
        }
        if (draft.cookingTimeMinutes() == null || draft.cookingTimeMinutes() <= 0) {
            add(codes, reasons, "COOKING_TIME_REQUIRED", "cookingTimeMinutes는 1분 이상이어야 합니다.");
        }
        if (draft.caloriesKcal() != null && draft.caloriesKcal() <= 0) {
            add(codes, reasons, "CALORIES_INVALID", "caloriesKcal은 입력할 경우 1 이상이어야 합니다.");
        }
        if (draft.difficulty() == null || draft.difficulty() < 1 || draft.difficulty() > 3) {
            add(codes, reasons, "DIFFICULTY_INVALID", "difficulty는 1~3 사이여야 합니다.");
        }
        if (draft.ingredients() == null || draft.ingredients().isEmpty()) {
            add(codes, reasons, "INGREDIENTS_REQUIRED", "ingredients는 빈 배열이면 안 됩니다.");
        } else {
            for (int i = 0; i < draft.ingredients().size(); i++) {
                GeneratedIngredient ingredient = draft.ingredients().get(i);
                if (ingredient == null || isBlank(ingredient.name()) || isBlank(ingredient.quantity())) {
                    add(codes, reasons, "INGREDIENT_QUANTITY_REQUIRED",
                            "ingredients[" + i + "]에는 name, amount, unit이 필요합니다.");
                }
            }
        }
        if (draft.steps() == null || draft.steps().isEmpty()) {
            add(codes, reasons, "STEPS_REQUIRED", "steps는 빈 배열이면 안 됩니다.");
        } else {
            for (int i = 0; i < draft.steps().size(); i++) {
                GeneratedCookingStep step = draft.steps().get(i);
                if (step == null || step.order() == null || isBlank(step.instruction())) {
                    add(codes, reasons, "STEP_REQUIRED",
                            "steps[" + i + "]에는 order와 instruction이 필요합니다.");
                } else if (step.ingredientNames() == null) {
                    add(codes, reasons, "STEP_INGREDIENT_NAMES_REQUIRED",
                            "steps[" + i + "].ingredientNames는 필수입니다. 재료를 쓰지 않는 단계면 빈 배열을 넣으세요.");
                } else if (step.ingredientNames().stream().anyMatch(this::isBlank)) {
                    add(codes, reasons, "STEP_INGREDIENT_NAME_BLANK",
                            "steps[" + i + "].ingredientNames에는 빈 값을 넣을 수 없습니다.");
                }
            }
        }
    }

    // 프롬프트의 내부 작성 규칙 문구가 사용자용 조리 설명에 그대로 새어 나왔는지 검사합니다.
    private void validateNoLeakedAuthoringRules(
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        for (GeneratedCookingStep step : safeSteps(draft)) {
            if (step == null || isBlank(step.instruction())) {
                continue;
            }
            String instruction = step.instruction().replaceAll("\\s+", "");
            if (containsAny(instruction, "재료목록", "표시된경우", "선택한맵기단계")) {
                add(codes, reasons, "AUTHORING_RULE_LEAKED",
                        "사용자용 조리 단계에 내부 작성 규칙이 노출되었습니다. 문제 단계: " + step.order());
            }
        }
    }

    /**
     * 생성된 제목이 요청한 요리 이름을 포함하는지(또는 반대로 포함되는지) 확인합니다.
     * 요청 이름 뒤에 다른 요리 종류가 붙어 요리 자체가 바뀐 경우("김치" → "김치볶음밥")도 실패로 봅니다.
     */
    private void validateRequestedTitle(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        if (request == null || isBlank(request.requestedTitle()) || isBlank(draft.title())) {
            return;
        }
        String requested = normalizeIngredient(request.requestedTitle());
        String generated = normalizeIngredient(draft.title());
        boolean titleContainsRequestedDish = requested.contains(generated) || generated.contains(requested);
        boolean changesDishType = generated.startsWith(requested)
                && generated.length() > requested.length()
                && DISH_TYPE_SUFFIXES.stream().anyMatch(generated.substring(requested.length())::contains);
        if (!titleContainsRequestedDish || changesDishType) {
            add(codes, reasons, "TITLE_MISMATCH",
                    "요청한 음식명과 생성된 레시피 제목이 일치하지 않습니다. 요청: "
                            + request.requestedTitle() + " / 생성: " + draft.title());
        }
    }

    // 같은 재료(별칭 포함)가 두 번 선언되었는지, 수량이 0 이하이거나 숫자가 아닌지(NaN/무한대) 검사합니다.
    private void validateIngredientAmountsAndDuplicates(
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        if (draft.ingredients() == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (GeneratedIngredient ingredient : draft.ingredients()) {
            if (ingredient == null || isBlank(ingredient.name())) {
                continue;
            }
            String canonical = ingredientAliasNormalizer.canonical(ingredient.name());
            if (!canonical.isBlank() && !seen.add(canonical)) {
                add(codes, reasons, "INGREDIENT_DUPLICATED",
                        "같은 재료가 중복으로 선언되었습니다: " + ingredient.name());
            }
            if (ingredient.amount() != null
                    && (!Double.isFinite(ingredient.amount()) || ingredient.amount() <= 0)) {
                add(codes, reasons, "INGREDIENT_AMOUNT_INVALID",
                        "재료 수량은 0보다 커야 합니다: " + ingredient.name());
            }
        }
    }

    // 단위가 알려진/허용된 값인지, "약간"이 아닌 단위에 수량이 있는지 검사합니다.
    private void validateIngredientUnits(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        if (draft.ingredients() == null) {
            return;
        }
        for (int i = 0; i < draft.ingredients().size(); i++) {
            GeneratedIngredient ingredient = draft.ingredients().get(i);
            if (ingredient == null || isBlank(ingredient.name())) {
                continue;
            }
            if (ingredient.hasUnknownUnit()) {
                add(codes, reasons, "INGREDIENT_UNIT_UNKNOWN",
                        "알 수 없는 단위입니다: " + ingredient.unit());
                continue;
            }
            String normalizedUnit = ingredient.normalizedUnit();
            if (isBlank(normalizedUnit)) {
                continue;
            }
            if (!ALLOWED_UNITS.contains(normalizedUnit)) {
                add(codes, reasons, "INGREDIENT_UNIT_NOT_ALLOWED",
                        "허용되지 않은 단위입니다: " + ingredient.unit());
            }
            if (!"약간".equals(normalizedUnit) && ingredient.amount() == null) {
                add(codes, reasons, "INGREDIENT_AMOUNT_REQUIRED",
                        "약간이 아닌 단위에는 amount가 필요합니다: " + ingredient.name());
            }
        }
    }

    // 불 세기가 표준 표기로 바꿀 수 없는 값인지 검사합니다.
    private void validateHeatLevels(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        for (GeneratedCookingStep step : safeSteps(draft)) {
            if (step.hasUnknownHeatLevel()) {
                add(codes, reasons, "HEAT_LEVEL_INVALID",
                        "허용되지 않은 heatLevel입니다: " + step.heatLevel());
            }
        }
    }

    // 조리 단계 order가 1, 2, 3...처럼 빠짐없이 이어지는지 검사합니다. order가 없는 단계는 필수 필드 검사에서 처리합니다.
    private void validateStepOrder(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        if (draft.steps() == null || draft.steps().isEmpty()) {
            return;
        }
        Set<Integer> seen = new LinkedHashSet<>();
        for (GeneratedCookingStep step : draft.steps()) {
            if (step == null || step.order() == null) {
                return;
            }
            seen.add(step.order());
        }
        for (int expected = 1; expected <= draft.steps().size(); expected++) {
            if (!seen.contains(expected)) {
                add(codes, reasons, "STEP_ORDER_INVALID", "조리 단계 order는 1부터 연속되어야 합니다.");
                return;
            }
        }
    }

    // 화채/샐러드/음료처럼 불을 쓰지 않는 메뉴에 불 세기, 온도, 가열 지시가 들어갔는지 검사합니다.
    private void validateNoHeatRecipe(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        if (!isNoHeatRecipe(request, draft)) {
            return;
        }
        for (GeneratedCookingStep step : safeSteps(draft)) {
            String heatLevel = step.normalizedHeatLevel();
            if (!NO_HEAT_LEVELS.contains(heatLevel)) {
                add(codes, reasons, "NO_HEAT_HAS_HEAT_LEVEL",
                        "무가열 메뉴에는 heatLevel을 넣지 마세요. 문제 단계: " + step.order());
            }
            if (step.temperatureC() != null) {
                add(codes, reasons, "NO_HEAT_HAS_TEMPERATURE",
                        "무가열 메뉴에는 조리 온도를 넣지 마세요. 문제 단계: " + step.order());
            }
            String instruction = normalize(step.instruction() + " " + step.completionCue() + " " + step.recoveryTip());
            if (containsAny(instruction, "중불", "약불", "강불", "센불", "불에", "불로", "가열", "끓", "볶", "굽", "튀")) {
                add(codes, reasons, "NO_HEAT_HAS_HEAT_INSTRUCTION",
                        "무가열 메뉴에 불 세기나 가열 지시가 들어갔습니다. 문제 단계: " + step.order());
            }
        }
    }

    /**
     * 가열 단계의 세부 정보를 검사합니다.
     * - 온도(temperatureC)는 오븐/에어프라이어 단계에서만 허용하고, 그 단계에서는 40~300℃ 필수
     * - 팬/냄비 단계에는 불 세기 필수
     * - 모든 가열 단계에는 조리 시간과 관찰 가능한 완료 기준 필수
     */
    private void validateCookingStepDetails(
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        for (GeneratedCookingStep step : safeSteps(draft)) {
            if (step == null || isBlank(step.instruction())) {
                continue;
            }
            // 기구 언급과 실제 가열 동작을 구분합니다. "에어프라이어에서 꺼내어 식힌다"처럼 기구 이름만
            // 나오고 가열 동작이 없는 단계까지 온도·시간을 요구하면 정상 레시피가 실패합니다.
            boolean applianceMentioned = containsAny(step.instruction(), OVEN_ACTIONS.toArray(String[]::new));
            boolean heatingVerb = containsAny(step.instruction(), STOVETOP_ACTIONS.toArray(String[]::new));
            boolean nonHeatingHandling =
                    containsAny(step.instruction(), NON_HEATING_HANDLING.toArray(String[]::new)) && !heatingVerb;
            boolean ovenStep = applianceMentioned && !nonHeatingHandling;
            boolean stovetopStep = !ovenStep && heatingVerb;
            // 온도 금지 판정은 기구 언급 기준을 유지합니다. 좁히면 기존에 통과하던 단계가 새로 실패합니다.
            if (!applianceMentioned && step.temperatureC() != null) {
                add(codes, reasons, "NON_OVEN_TEMPERATURE_FORBIDDEN",
                        "temperatureC는 오븐·에어프라이어 단계에서만 사용할 수 있습니다. 문제 단계: " + step.order());
            }
            if (!ovenStep && !stovetopStep) {
                continue;
            }
            if (ovenStep && (step.temperatureC() == null || step.temperatureC() < 40 || step.temperatureC() > 300)) {
                add(codes, reasons, "COOKING_TEMPERATURE_REQUIRED",
                        "오븐·에어프라이어 단계에는 40~300℃의 조리 온도가 필요합니다. 문제 단계: " + step.order());
            }
            if (stovetopStep && (isBlank(step.normalizedHeatLevel()) || NO_HEAT_LEVELS.contains(step.normalizedHeatLevel()))) {
                add(codes, reasons, "COOKING_HEAT_REQUIRED",
                        "팬·냄비 조리 단계에는 불 세기가 필요합니다. 문제 단계: " + step.order());
            }
            if (step.minutes() == null || step.minutes() <= 0) {
                add(codes, reasons, "COOKING_MINUTES_REQUIRED",
                        "가열 단계에는 1분 이상의 조리 시간이 필요합니다. 문제 단계: " + step.order());
            }
            if (isBlank(step.completionCue())) {
                add(codes, reasons, "COMPLETION_CUE_REQUIRED",
                        "가열 단계에는 다음 단계로 넘어갈 수 있는 완료 상태가 필요합니다. 문제 단계: " + step.order());
            } else if (VAGUE_COMPLETION_CUES.contains(normalizeIngredient(step.completionCue()))) {
                add(codes, reasons, "COMPLETION_CUE_NOT_OBSERVABLE",
                        "완료 기준은 색·질감·농도·중심 익힘처럼 사용자가 관찰할 수 있어야 합니다. 문제 단계: " + step.order());
            }
        }
    }

    /**
     * 실패 복구 팁이 오히려 상황을 악화시키는지 검사합니다.
     * 예) "탔으면 더 오래 끓이세요", "양념 덩어리가 남으면 더 끓이세요", "대파가 덜 익으면 불을 높이세요"
     */
    private void validateRecoveryActions(
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        for (GeneratedCookingStep step : safeSteps(draft)) {
            if (step == null || isBlank(step.recoveryTip())) {
                continue;
            }
            String recovery = step.recoveryTip();
            if (containsAny(recovery, OVERPROCESSED_STATES.toArray(String[]::new))
                    && containsAny(recovery, MORE_HEAT_OR_TIME_ACTIONS.toArray(String[]::new))) {
                add(codes, reasons, "RECOVERY_ACTION_CONTRADICTS_FAILURE",
                        "실패 상태를 더 악화시키는 복구 지시가 있습니다. 문제 단계: " + step.order()
                                + " / 지시: " + recovery);
            }
            if (containsAny(recovery, "덩어리가 남", "덩어리로 남", "덩어리 남")
                    && containsAny(recovery, "더 오래 끓", "계속 끓", "더 끓")) {
                add(codes, reasons, "RECOVERY_ACTION_INEFFECTIVE",
                        "풀리지 않은 양념 덩어리는 추가 가열이 아니라 저어 풀거나 체에 걸러야 합니다. 문제 단계: "
                                + step.order());
            }
            String ingredients = step.ingredientNames() == null ? "" : String.join(" ", step.ingredientNames());
            if (containsAny(ingredients, "대파", "쪽파")
                    && containsAny(recovery, "익지 않", "덜 익")
                    && containsAny(recovery, MORE_HEAT_OR_TIME_ACTIONS.toArray(String[]::new))) {
                add(codes, reasons, "UNNECESSARY_DONENESS_RECOVERY",
                        "마무리 향채를 완전히 익히기 위해 불이나 시간을 늘리도록 지시하지 마세요. 문제 단계: " + step.order());
            }
        }
    }

    // 두부/채소처럼 위험이 낮은 재료에 "중심까지 완전히 익히세요" 같은 과한 육류식 안전 문구를 붙였는지 검사합니다.
    private void validateSafetyNotes(
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        if (draft.safetyNotes() == null || draft.safetyNotes().isEmpty()) {
            return;
        }
        boolean hasHighRiskProtein = declaredIngredientNames(draft).stream()
                .anyMatch(name -> containsAny(name, HIGH_RISK_PROTEINS.toArray(String[]::new)));
        for (String note : draft.safetyNotes()) {
            if (isBlank(note)) {
                continue;
            }
            boolean forceCookThrough = containsAny(note,
                    "완전히 익", "중심까지 익", "중심을 확인", "젓가락으로 중심", "속까지 익");
            boolean namesLowRiskIngredient = containsAny(note, LOW_RISK_INGREDIENTS.toArray(String[]::new));
            if (forceCookThrough && (namesLowRiskIngredient || !hasHighRiskProtein)) {
                add(codes, reasons, "EXCESSIVE_SAFETY_NOTE",
                        "일반 채소·두부에 육류식 중심 익힘 안전문구를 적용하지 마세요: " + note);
            }
        }
    }

    // 조리 단계에서 사용한다고 적은 재료가 모두 재료 목록에 선언되어 있는지 검사합니다(첫 위반 하나만 보고).
    private void validateStepIngredients(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        List<String> declared = declaredIngredientNames(draft);
        if (declared.isEmpty()) {
            return;
        }
        for (GeneratedCookingStep step : safeSteps(draft)) {
            if (step.ingredientNames() == null) {
                continue;
            }
            for (String candidate : step.ingredientNames()) {
                if (!ingredientAliasNormalizer.matchesAnyDeclared(candidate, declared)) {
                    add(codes, reasons, "STEP_INGREDIENT_NOT_DECLARED",
                            "steps[].ingredientNames에 있지만 ingredients[].name에는 없는 재료가 있습니다: " + candidate);
                    return;
                }
            }
        }
    }

    // 반대로 재료 목록의 모든 재료가 어떤 조리 단계에서든 한 번 이상 사용되는지 검사합니다.
    private void validateIngredientUseCoverage(
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        List<String> declared = declaredIngredientNames(draft);
        if (declared.isEmpty() || draft.steps() == null || draft.steps().isEmpty()) {
            return;
        }
        List<String> used = safeSteps(draft).stream()
                .filter(step -> step != null && step.ingredientNames() != null)
                .flatMap(step -> step.ingredientNames().stream())
                .filter(value -> !isBlank(value))
                .toList();
        if (used.isEmpty()) {
            add(codes, reasons, "STEP_INGREDIENT_REFERENCES_REQUIRED",
                    "각 조리 단계에는 실제 사용하는 재료명을 ingredientNames로 연결해야 합니다.");
            return;
        }
        for (String ingredient : declared) {
            if (!ingredientAliasNormalizer.matchesAnyDeclared(ingredient, used)) {
                add(codes, reasons, "DECLARED_INGREDIENT_NOT_USED",
                        "재료 목록에 있지만 어떤 조리 단계에서도 사용되지 않는 재료가 있습니다: " + ingredient);
            }
        }
    }

    /**
     * 고위험 단백질이 있으면 가열 단계가 있는지, 그리고 "중심까지 익힘" 같은 안전 확인 기준이 있는지 검사합니다.
     */
    private void validateHighRiskProteinSafety(
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        List<String> declared = declaredIngredientNames(draft);
        List<String> risky = declared.stream()
                .filter(name -> containsAny(name, HIGH_RISK_PROTEINS.toArray(String[]::new)))
                // 이미 가열된 형태는 제외합니다. 생참치는 그대로 요구하고 참치캔만 빠집니다.
                .filter(name -> containsAny(name, RAW_FORM_MARKERS.toArray(String[]::new))
                        || !containsAny(name, PRECOOKED_FORMS.toArray(String[]::new)))
                .toList();
        if (risky.isEmpty()) {
            return;
        }
        boolean hasHeatingStep = safeSteps(draft).stream()
                .filter(step -> step != null)
                .anyMatch(step -> containsAny(step.instruction(), STOVETOP_ACTIONS.toArray(String[]::new))
                        || containsAny(step.instruction(), OVEN_ACTIONS.toArray(String[]::new))
                        || (!isBlank(step.normalizedHeatLevel()) && !NO_HEAT_LEVELS.contains(step.normalizedHeatLevel()))
                        || step.temperatureC() != null);
        if (!hasHeatingStep) {
            add(codes, reasons, "PROTEIN_COOKING_STEP_REQUIRED",
                    "고위험 단백질 재료를 안전하게 익히는 조리 단계가 필요합니다: " + String.join(", ", risky));
            return;
        }
        String cues = safeSteps(draft).stream()
                .filter(step -> step != null)
                .map(step -> nullToBlank(step.instruction()) + " " + nullToBlank(step.completionCue()))
                .collect(java.util.stream.Collectors.joining(" "));
        if (!containsAny(cues, SAFE_DONENESS_CUES.toArray(String[]::new))) {
            add(codes, reasons, "PROTEIN_DONENESS_CUE_REQUIRED",
                    "고위험 단백질은 중심까지 안전하게 익었는지 확인하는 완료 기준이 필요합니다: "
                            + String.join(", ", risky));
        }
    }

    /**
     * 재료 대체 요청(A → B)이 제대로 반영되었는지 검사합니다.
     * A는 재료 목록과 조리 단계에서 사라져야 하고, B는 둘 다에 있어야 하며, adjustments에 이유와 수량 조정 설명이 있어야 합니다.
     */
    private void validateRequestedSubstitutions(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        if (request == null || request.substitutions() == null || request.substitutions().isEmpty()) {
            return;
        }
        for (RecipeGenerationRequest.IngredientSubstitution substitution : request.substitutions()) {
            String from = normalizeIngredient(substitution.from());
            String to = normalizeIngredient(substitution.to());
            if (!from.isBlank() && ingredientExists(substitution.from(), declaredIngredientNames(draft))) {
                add(codes, reasons, "FROM_REMAINED_IN_INGREDIENTS",
                        "대체 전 재료가 ingredients에 남아 있습니다: " + substitution.from());
            }
            if (!from.isBlank() && stepIngredientExists(substitution.from(), draft)) {
                add(codes, reasons, "FROM_REMAINED_IN_STEPS",
                        "대체 전 재료가 steps[].ingredientNames에 남아 있습니다: " + substitution.from());
            }
            if (!to.isBlank() && !ingredientExists(substitution.to(), declaredIngredientNames(draft))) {
                add(codes, reasons, "TO_MISSING_IN_INGREDIENTS",
                        "대체 재료가 ingredients에 반영되지 않았습니다: " + substitution.to());
            }
            if (!to.isBlank() && !stepIngredientExists(substitution.to(), draft)) {
                add(codes, reasons, "TO_MISSING_IN_STEPS",
                        "대체 재료가 steps[].ingredientNames에 반영되지 않았습니다: " + substitution.to());
            }
            validateSubstitutionAdjustment(substitution, draft, codes, reasons);
        }
    }

    // 해당 대체(A → B)에 대한 SUBSTITUTION 변경 내역을 찾아 이유와 수량 조정 설명이 있는지 확인합니다.
    private void validateSubstitutionAdjustment(
            RecipeGenerationRequest.IngredientSubstitution substitution,
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        RecipeAdjustment adjustment = safeAdjustments(draft).stream()
                .filter(candidate -> "SUBSTITUTION".equalsIgnoreCase(nullToBlank(candidate.type())))
                .filter(candidate -> ingredientAliasNormalizer.matchesAnyDeclared(substitution.from(), safeSingleton(candidate.fromIngredient()))
                        || normalizeIngredient(substitution.from()).equals(normalizeIngredient(candidate.fromIngredient())))
                .filter(candidate -> ingredientAliasNormalizer.matchesAnyDeclared(substitution.to(), safeSingleton(candidate.toIngredient()))
                        || normalizeIngredient(substitution.to()).equals(normalizeIngredient(candidate.toIngredient())))
                .findFirst()
                .orElse(null);
        if (adjustment == null || isBlank(adjustment.reason())) {
            add(codes, reasons, "ADJUSTMENT_REASON_MISSING",
                    "대체 이유와 조정 설명이 adjustments에 필요합니다: " + substitution.to());
        }
        if (adjustment == null || !quantityAdjustmentMeaningful(adjustment.quantityAdjustment())) {
            add(codes, reasons, "QUANTITY_ADJUSTMENT_MISSING",
                    "대체 수량 조정 설명이 adjustments에 필요합니다: " + substitution.to());
        }
    }

    // 수량 조정 설명이 "substitute"처럼 의미 없는 값이 아니라, 숫자나 조절 표현을 포함하는지 확인합니다.
    private boolean quantityAdjustmentMeaningful(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank() || "substitute".equals(normalized) || "substitution".equals(normalized)) {
            return false;
        }
        return containsAny(normalized, "대신", "부터", "조절", "줄", "늘", "맛", "확인", "큰술", "작은술", "g", "ml")
                || normalized.matches(".*\\d+.*");
    }

    /**
     * 제외 요청한 재료가 재료 목록, 단계별 재료, 조리 지시 문장, 변경 내역(adjustments의 대체 후 재료) 어디에도 남아 있지 않은지 검사합니다.
     */
    private void validateExcludedIngredients(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft draft,
            List<String> codes,
            List<String> reasons) {
        if (request == null || request.excludedIngredients() == null || request.excludedIngredients().isEmpty()) {
            return;
        }
        for (String excluded : request.excludedIngredients()) {
            String normalized = normalizeIngredient(excluded);
            if (normalized.isBlank()) {
                continue;
            }
            if (ingredientExists(excluded, declaredIngredientNames(draft))) {
                add(codes, reasons, "EXCLUDED_INGREDIENT_REMAINED",
                        "제외 요청한 재료가 ingredients에 남아 있습니다: " + excluded);
            }
            if (stepIngredientExists(excluded, draft)) {
                add(codes, reasons, "EXCLUDED_INGREDIENT_REMAINED",
                        "제외 요청한 재료가 steps[].ingredientNames에 남아 있습니다: " + excluded);
            }
            for (GeneratedCookingStep step : safeSteps(draft)) {
                if (instructionUsesExcludedIngredient(step.instruction(), excluded)) {
                    add(codes, reasons, "EXCLUDED_INGREDIENT_REMAINED",
                            "제외 요청한 재료가 조리 지시에서 사용되고 있습니다: " + excluded);
                    break;
                }
            }
            if (adjustmentUsesExcludedIngredient(draft, excluded)) {
                add(codes, reasons, "EXCLUDED_INGREDIENT_REMAINED",
                        "제외 요청한 재료가 adjustments의 사용 대상으로 남아 있습니다: " + excluded);
            }
        }
    }

    // 단계별 조리 시간 합계가 전체 조리 시간보다 허용 오차(10% 또는 최소 2분) 이상 크면 실패입니다.
    private void validateCookingTime(GeneratedRecipeDraft draft, List<String> codes, List<String> reasons) {
        if (draft.cookingTimeMinutes() == null || draft.cookingTimeMinutes() <= 0 || draft.steps() == null) {
            return;
        }
        int stepMinuteSum = draft.steps().stream()
                .filter(step -> step != null && step.minutes() != null && step.minutes() > 0)
                .mapToInt(GeneratedCookingStep::minutes)
                .sum();
        int toleranceMinutes = Math.max(2, Math.round(draft.cookingTimeMinutes() * 0.1f));
        if (stepMinuteSum > draft.cookingTimeMinutes() + toleranceMinutes) {
            add(codes, reasons, "TIME_CONFLICT",
                    "상단 조리 시간과 단계별 시간 합계가 허용 오차를 벗어났습니다. 상단: "
                            + draft.cookingTimeMinutes() + "분 / 단계 합계: " + stepMinuteSum
                            + "분 / 허용 오차: " + toleranceMinutes + "분");
        }
    }

    // 사용자 식단 제한에 비건/채식/육류 제외가 있는데 레시피에 육류/해산물이 있으면 true(즉시 차단)입니다.
    private boolean violatesDietaryRestriction(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft draft,
            List<String> reasons) {
        if (request == null || request.safetyConditions() == null
                || request.safetyConditions().dietaryRestrictions() == null) {
            return false;
        }
        String restrictions = normalize(String.join(" ", request.safetyConditions().dietaryRestrictions()));
        if (!(restrictions.contains("비건") || restrictions.contains("채식") || restrictions.contains("육류제외"))) {
            return false;
        }
        String recipeText = recipeText(draft);
        if (containsAny(recipeText, "돼지고기", "소고기", "쇠고기", "닭고기", "생선", "오징어", "새우")) {
            reasons.add("명시적인 채식/육류 제한 조건과 충돌하는 재료가 있습니다.");
            return true;
        }
        return false;
    }

    // 요청 이름이나 생성 제목으로 불을 쓰지 않는 메뉴인지 추정합니다.
    private boolean isNoHeatRecipe(RecipeGenerationRequest request, GeneratedRecipeDraft draft) {
        String text = normalize((request != null ? request.requestedTitle() : "") + " " + draft.title());
        return containsAny(text, "화채", "스무디", "요거트", "샐러드", "빙수", "주스", "에이드", "파르페", "음료");
    }

    // 재료 목록에서 이름이 있는 재료의 이름만 모읍니다.
    private List<String> declaredIngredientNames(GeneratedRecipeDraft draft) {
        List<String> names = new ArrayList<>();
        if (draft.ingredients() == null) {
            return names;
        }
        for (GeneratedIngredient ingredient : draft.ingredients()) {
            if (ingredient == null || isBlank(ingredient.name())) {
                continue;
            }
            names.add(ingredient.name());
        }
        return names;
    }

    private boolean ingredientExists(String ingredient, List<String> declaredIngredients) {
        return ingredientAliasNormalizer.matchesAnyDeclared(ingredient, declaredIngredients);
    }

    // 어느 조리 단계의 ingredientNames에든 해당 재료(별칭 포함)가 있으면 true입니다.
    private boolean stepIngredientExists(String ingredient, GeneratedRecipeDraft draft) {
        for (GeneratedCookingStep step : safeSteps(draft)) {
            if (step.ingredientNames() == null) {
                continue;
            }
            if (ingredientAliasNormalizer.matchesAnyDeclared(ingredient, step.ingredientNames())) {
                return true;
            }
        }
        return false;
    }

    private List<GeneratedCookingStep> safeSteps(GeneratedRecipeDraft draft) {
        return draft.steps() == null ? List.of() : draft.steps();
    }

    private List<RecipeAdjustment> safeAdjustments(GeneratedRecipeDraft draft) {
        return draft.adjustments() == null ? List.of() : draft.adjustments();
    }

    /**
     * 조리 지시 문장에서 제외 재료를 "사용"하는지 판단합니다.
     * "양파 없이", "양파는 사용하지 않고"처럼 제외를 설명하는 문장은 허용하고,
     * "양파를 넣고", "양파 썰기"처럼 실제로 사용하는 표현만 위반으로 봅니다.
     */
    private boolean instructionUsesExcludedIngredient(String instruction, String excluded) {
        String compactInstruction = normalize(instruction);
        String compactExcluded = normalize(excluded);
        if (compactInstruction.isBlank() || compactExcluded.isBlank() || !compactInstruction.contains(compactExcluded)) {
            return false;
        }
        if (containsAny(compactInstruction,
                compactExcluded + "없이",
                compactExcluded + "는사용하지",
                compactExcluded + "은사용하지",
                compactExcluded + "를사용하지",
                compactExcluded + "을사용하지",
                compactExcluded + "제외",
                compactExcluded + "빼고")) {
            return false;
        }
        return containsAny(compactInstruction,
                compactExcluded + "를넣",
                compactExcluded + "을넣",
                compactExcluded + "넣",
                compactExcluded + "볶",
                compactExcluded + "썰",
                compactExcluded + "준비",
                compactExcluded + "사용",
                compactExcluded + "익");
    }

    // 변경 내역의 "대체 후 재료"가 제외 재료라면(제외한 재료를 다시 넣는 셈) true입니다.
    private boolean adjustmentUsesExcludedIngredient(GeneratedRecipeDraft draft, String excluded) {
        return safeAdjustments(draft).stream()
                .map(RecipeAdjustment::toIngredient)
                .anyMatch(value -> ingredientAliasNormalizer.matchesAnyDeclared(excluded, safeSingleton(value))
                        || normalizeIngredient(excluded).equals(normalizeIngredient(value)));
    }

    private List<String> safeSingleton(String value) {
        return value == null ? List.of() : List.of(value);
    }

    // 재료와 조리 단계의 모든 텍스트를 하나로 합쳐 정규화합니다(키워드 포함 여부 검사용).
    private String recipeText(GeneratedRecipeDraft draft) {
        StringBuilder builder = new StringBuilder();
        if (draft.ingredients() != null) {
            draft.ingredients().forEach(ingredient -> builder.append(" ")
                    .append(ingredient == null ? "" : ingredient.name())
                    .append(" ")
                    .append(ingredient == null ? "" : ingredient.quantity()));
        }
        if (draft.steps() != null) {
            draft.steps().forEach(step -> builder.append(" ")
                    .append(step == null ? "" : step.instruction())
                    .append(" ")
                    .append(step == null || step.ingredientNames() == null ? "" : String.join(" ", step.ingredientNames()))
                    .append(" ")
                    .append(step == null ? "" : step.completionCue())
                    .append(" ")
                    .append(step == null ? "" : step.recoveryTip()));
        }
        return normalizeIngredient(builder.toString());
    }

    // 실패 코드와 이유를 함께 추가합니다.
    private void add(List<String> codes, List<String> reasons, String code, String reason) {
        codes.add(code);
        reasons.add(reason);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean containsAny(String value, String... keywords) {
        String normalized = normalize(value);
        for (String keyword : keywords) {
            if (normalized.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    // 한글/영문/숫자만 남기고 소문자로 바꿉니다(재료 이름 비교용).
    private String normalizeIngredient(String value) {
        return value == null ? "" : value.replaceAll("[^가-힣a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    // 공백만 제거하고 소문자로 바꿉니다(문장 속 키워드 검사용).
    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    /**
     * 초안 검증 결과입니다.
     * valid: 통과 여부 / retryable: LLM 복구로 고칠 수 있는 실패 / blocking: 복구 없이 즉시 차단해야 하는 실패
     */
    public record ValidationResult(
            boolean valid,
            boolean retryable,
            boolean blocking,
            List<String> codes,
            List<String> reasons
    ) {
        private static ValidationResult ok() {
            return new ValidationResult(true, false, false, List.of(), List.of());
        }

        private static ValidationResult retryable(List<String> codes, List<String> reasons) {
            return new ValidationResult(false, true, false, List.copyOf(codes), List.copyOf(reasons));
        }

        private static ValidationResult blocking(List<String> codes, List<String> reasons) {
            return new ValidationResult(false, false, true, List.copyOf(codes), List.copyOf(reasons));
        }
    }
}
