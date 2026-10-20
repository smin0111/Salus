package com.salus.healthytable.eval;

import com.salus.healthytable.service.GeneratedCookingStep;
import com.salus.healthytable.service.GeneratedIngredient;
import com.salus.healthytable.service.GeneratedRecipeDraft;
import com.salus.healthytable.service.RecipeDraftValidator;
import com.salus.healthytable.service.allergen.AllergenMatcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 채점기. 판정 로직을 새로 만들지 않고 프로덕션 판정기를 그대로 호출한다.
 *
 * <p>알레르겐은 {@link AllergenMatcher}, 레시피 규칙은 {@link RecipeDraftValidator}가 단일
 * 진입점이다. 하네스가 자체 판정 규칙을 갖게 되면 평가 점수와 실제 서비스 동작이 갈라진다.
 */
public class EvalGrader {

    private final AllergenMatcher allergenMatcher;
    private final boolean pinFailureCodes;

    /**
     * @param pinFailureCodes replay 모드에서만 true. live 실행은 모델 표본마다 코드가 달라져
     *                        고정값 비교가 의미 없으므로 회귀 차원을 끈다.
     */
    public EvalGrader(AllergenMatcher allergenMatcher, boolean pinFailureCodes) {
        this.allergenMatcher = allergenMatcher;
        this.pinFailureCodes = pinFailureCodes;
    }

    /** 어떤 실행의 어떤 모델·회차인지. */
    public record RunContext(String runId, String model, int iteration) {
    }

    /**
     * 비교용 점수 가중치. 결정적으로만 계산하고 주관적 판단은 넣지 않는다.
     *
     * <p>알레르겐 차원은 점수에 넣지 않는다. 점수로 환산하면 총점이 높다는 이유로
     * 안전 실패가 묻힌다. 알레르겐은 점수와 무관한 hard fail로만 다룬다.
     */
    static final Map<String, Integer> SCORE_WEIGHTS = new LinkedHashMap<>(Map.of(
            EvalResult.TRANSPORT, 20,
            EvalResult.SCHEMA, 20,
            EvalResult.VALIDATOR, 30,
            EvalResult.CONSTRAINT, 15,
            EvalResult.NOISE, 15));

    // 검증기 실패 코드를 스키마 문제와 요청 제약 위반으로 나누는 분류표입니다.
    private static final Set<String> SCHEMA_CODES = Set.of(
            "DRAFT_NULL", "TITLE_REQUIRED", "DESCRIPTION_REQUIRED", "SERVINGS_REQUIRED",
            "COOKING_TIME_REQUIRED", "CALORIES_INVALID", "DIFFICULTY_INVALID",
            "INGREDIENTS_REQUIRED", "INGREDIENT_QUANTITY_REQUIRED", "INGREDIENT_AMOUNT_REQUIRED",
            "INGREDIENT_AMOUNT_INVALID", "INGREDIENT_UNIT_NOT_ALLOWED", "INGREDIENT_UNIT_UNKNOWN",
            "STEPS_REQUIRED", "STEP_REQUIRED", "STEP_ORDER_INVALID",
            "STEP_INGREDIENT_NAMES_REQUIRED", "STEP_INGREDIENT_NAME_BLANK", "HEAT_LEVEL_INVALID");

    private static final Set<String> CONSTRAINT_CODES = Set.of(
            "EXCLUDED_INGREDIENT_REMAINED", "FROM_REMAINED_IN_INGREDIENTS", "FROM_REMAINED_IN_STEPS",
            "TO_MISSING_IN_INGREDIENTS", "TO_MISSING_IN_STEPS", "TITLE_MISMATCH",
            "DIETARY_RESTRICTION_CONFLICT", "ADJUSTMENT_REASON_MISSING", "QUANTITY_ADJUSTMENT_MISSING");

    /**
     * qwen3 thinking 누출 흔적. num_predict 예산을 추론에 먼저 써서 답변이 비는 사고가
     * 실제로 났었기 때문에(커밋 be415ea) 상시 회귀 감시 대상이다.
     */
    private static final Pattern THINKING_LEAK = Pattern.compile(
            "(?is)<think>|</think>|<\\|?thinking\\|?>|^\\s*(?:okay|알겠습니다),?\\s+(?:let me|the user|사용자가)\\b");
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("```");
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]");
    // LLM 장애 시 채팅 경로가 돌려주는 안내 문구(정상 답변이 아님을 판별)
    private static final List<String> CHAT_FALLBACK_SENTINELS = List.of(
            "현재 로컬 AI 엔진이 응답하지 않습니다",
            "서브 AI로부터 답변을 생성하지 못했습니다");

    /**
     * 레시피 생성 실행 결과를 채점합니다.
     * 호출 성공 → 스키마 → 알레르겐 안전 → 검증기 → 요청 제약 → 출력 오염 → 회귀 순으로 차원별 판정을 모읍니다.
     */
    public EvalResult gradeRecipe(
            RunContext context,
            EvalCase evalCase,
            EvalRun.RecipeRun run,
            RecipeDraftValidator.ValidationResult validation) {

        EvalRun.RecipeCall finalCall = run.finalCall();
        EvalRun.Telemetry telemetry = finalCall.telemetry();
        List<EvalResult.Dimension> dimensions = new ArrayList<>();
        List<String> codes = validation == null ? List.of() : List.copyOf(validation.codes());
        List<String> reasons = validation == null ? List.of() : List.copyOf(validation.reasons());

        dimensions.add(EvalResult.Dimension.of(
                EvalResult.TRANSPORT,
                finalCall.succeeded(),
                nullToBlank(telemetry.failureCode()) + " / " + nullToBlank(telemetry.failureMessage())));

        List<String> conflicts = List.of();
        if (!finalCall.succeeded()) {
            // 호출 자체가 실패하면 이후 차원은 채점 대상이 아니다.
            dimensions.add(EvalResult.Dimension.notApplicable(EvalResult.SCHEMA));
            dimensions.add(EvalResult.Dimension.notApplicable(EvalResult.ALLERGEN_SAFETY));
            dimensions.add(EvalResult.Dimension.notApplicable(EvalResult.VALIDATOR));
            dimensions.add(EvalResult.Dimension.notApplicable(EvalResult.CONSTRAINT));
            dimensions.add(EvalResult.Dimension.notApplicable(EvalResult.NOISE));
            dimensions.add(gradeRegression(evalCase, Set.of(nullToBlank(telemetry.failureCode()))));
        } else {
            GeneratedRecipeDraft draft = finalCall.draft();
            conflicts = allergenMatcher.findConflicts(evalCase.allergiesOrEmpty(), allergenTexts(draft));
            dimensions.add(gradeCodeGroup(EvalResult.SCHEMA, codes, SCHEMA_CODES));
            dimensions.add(gradeAllergen(evalCase, conflicts));
            dimensions.add(EvalResult.Dimension.of(
                    EvalResult.VALIDATOR,
                    validation != null && validation.valid(),
                    "검증기 실패 코드 " + codes));
            dimensions.add(gradeConstraint(evalCase, draft, codes));
            dimensions.add(gradeRecipeNoise(draft));
            dimensions.add(gradeRegression(evalCase, new LinkedHashSet<>(codes)));
        }
        applyExpectedFailures(evalCase, dimensions);

        return assemble(
                context, evalCase, dimensions,
                telemetry, run.totalLatencyMs(), run.timedOut(),
                run.repairAttempted() ? run.first().telemetry().rawOutput() : null,
                validation != null && validation.valid(), codes, reasons, conflicts,
                run.firstDraftValid(), run.repairAttempted(), run.repairPassed());
    }

    // 채팅 응답을 채점합니다(호출 성공, 최소 길이, 금지/필수 표현, 출력 오염).
    public EvalResult gradeChat(RunContext context, EvalCase evalCase, EvalRun.ChatCall call) {
        String reply = call.reply() == null ? "" : call.reply();
        EvalRun.Telemetry telemetry = call.telemetry();
        List<EvalResult.Dimension> dimensions = new ArrayList<>();

        String fallback = CHAT_FALLBACK_SENTINELS.stream().filter(reply::contains).findFirst().orElse(null);
        dimensions.add(EvalResult.Dimension.of(
                EvalResult.TRANSPORT,
                fallback == null && telemetry.failureCode() == null,
                fallback != null
                        ? "엔진 폴백 문구 반환: " + fallback
                        : nullToBlank(telemetry.failureCode()) + " / " + nullToBlank(telemetry.failureMessage())));

        int minChars = evalCase.expectOrEmpty().minReplyCharsOrDefault();
        // 빈 응답은 thinking 예산 소진의 대표 증상이라 별도 차원으로 세운다.
        dimensions.add(EvalResult.Dimension.of(
                EvalResult.SCHEMA,
                reply.strip().length() >= minChars,
                "응답 길이 " + reply.strip().length() + "자 < 요구 " + minChars + "자"));

        List<String> conflicts = allergenMatcher.findConflicts(evalCase.allergiesOrEmpty(), List.of(reply));
        dimensions.add(gradeAllergen(evalCase, conflicts));
        dimensions.add(EvalResult.Dimension.notApplicable(EvalResult.VALIDATOR));
        dimensions.add(gradeTerms(evalCase, reply));
        dimensions.add(gradeTextNoise(reply));
        dimensions.add(EvalResult.Dimension.notApplicable(EvalResult.REGRESSION));
        applyExpectedFailures(evalCase, dimensions);

        return assemble(
                context, evalCase, dimensions,
                telemetry, telemetry.latencyMs(), telemetry.timeout(), null,
                false, List.of(), List.of(), conflicts, false, false, false);
    }

    // 차원별 판정, 점수, 최종 판정, 관측값을 모아 EvalResult를 만듭니다.
    private EvalResult assemble(
            RunContext context,
            EvalCase evalCase,
            List<EvalResult.Dimension> dimensions,
            EvalRun.Telemetry telemetry,
            long latencyMs,
            boolean timeout,
            String firstDraftRawOutput,
            boolean validatorPassed,
            List<String> codes,
            List<String> reasons,
            List<String> conflicts,
            boolean firstDraftValid,
            boolean repairAttempted,
            boolean repairPassed) {

        return new EvalResult(
                context.runId(),
                evalCase.id(),
                evalCase.suiteOrDefault(),
                context.model(),
                context.iteration(),
                evalCase.inputText(),
                evalCase.expectedConditions(),
                evalCase.ragCondition(),
                telemetry.rawOutput(),
                firstDraftRawOutput,
                telemetry.rawEnvelope(),
                telemetry.promptTokens(),
                telemetry.completionTokens(),
                telemetry.doneReason(),
                latencyMs,
                timeout,
                telemetry.failureCode(),
                validatorPassed,
                codes,
                reasons,
                conflicts,
                firstDraftValid,
                repairAttempted,
                repairPassed,
                List.copyOf(dimensions),
                score(dimensions),
                verdict(dimensions),
                null, null, null, null);
    }

    /**
     * 비교용 총점(0~100). 해당되는 차원의 가중치 합 대비 통과한 가중치 비율이다.
     * 규칙은 {@link #SCORE_WEIGHTS} 하나뿐이고 모델·케이스에 따라 달라지지 않는다.
     */
    static double score(List<EvalResult.Dimension> dimensions) {
        int total = 0;
        int earned = 0;
        for (EvalResult.Dimension dimension : dimensions) {
            Integer weight = SCORE_WEIGHTS.get(dimension.name());
            if (weight == null || !dimension.applicable()) {
                continue;
            }
            total += weight;
            if (dimension.passed()) {
                earned += weight;
            }
        }
        if (total == 0) {
            return 0.0;
        }
        return Math.round(earned * 1000.0 / total) / 10.0;
    }

    /** 안전 차원 실패는 총점과 무관하게 HARD_FAIL로 표시한다. */
    static String verdict(List<EvalResult.Dimension> dimensions) {
        for (EvalResult.Dimension dimension : dimensions) {
            if (EvalResult.ALLERGEN_SAFETY.equals(dimension.name())
                    && dimension.applicable() && !dimension.passed()) {
                return EvalResult.VERDICT_HARD_FAIL;
            }
        }
        boolean anyFailed = dimensions.stream()
                .filter(EvalResult.Dimension::applicable)
                .anyMatch(dimension -> !dimension.passed());
        return anyFailed ? EvalResult.VERDICT_FAIL : EvalResult.VERDICT_PASS;
    }

    /**
     * 일부러 깨뜨린 고정 응답을 넣은 케이스의 판정을 뒤집는다.
     *
     * <p>비 JSON 출력이나 thinking 누출 고정 응답은 <b>채점기가 잡아내야</b> 정상이다.
     * 뒤집지 않으면 회귀 감시용 케이스가 늘 빨간불로 남아 통과율 게이트를 못 쓴다.
     */
    private void applyExpectedFailures(EvalCase evalCase, List<EvalResult.Dimension> dimensions) {
        Set<String> expected = evalCase.expectOrEmpty().expectedFailedDimensionSet();
        if (expected.isEmpty()) {
            return;
        }
        for (int i = 0; i < dimensions.size(); i++) {
            EvalResult.Dimension dimension = dimensions.get(i);
            if (!expected.contains(dimension.name())) {
                continue;
            }
            if (!dimension.applicable()) {
                dimensions.set(i, EvalResult.Dimension.fail(
                        dimension.name(), "실패를 기대한 차원이 채점 대상이 아닙니다. 케이스 정의를 확인하세요."));
                continue;
            }
            dimensions.set(i, dimension.passed()
                    ? EvalResult.Dimension.fail(dimension.name(), "실패를 기대했으나 통과했습니다.")
                    : EvalResult.Dimension.pass(dimension.name()));
        }
    }

    // 선언된 알레르기와 충돌하는 재료가 하나라도 있으면 안전 차원 실패입니다.
    private EvalResult.Dimension gradeAllergen(EvalCase evalCase, List<String> conflicts) {
        if (evalCase.allergiesOrEmpty().isEmpty()) {
            return EvalResult.Dimension.notApplicable(EvalResult.ALLERGEN_SAFETY);
        }
        return EvalResult.Dimension.of(
                EvalResult.ALLERGEN_SAFETY, conflicts.isEmpty(), "알레르겐 충돌 " + conflicts);
    }

    // 검증기 실패 코드 중 해당 그룹에 속한 코드가 있으면 그 차원을 실패로 판정합니다.
    private EvalResult.Dimension gradeCodeGroup(String dimension, List<String> codes, Set<String> group) {
        List<String> hits = codes.stream().filter(group::contains).toList();
        return EvalResult.Dimension.of(dimension, hits.isEmpty(), String.valueOf(hits));
    }

    // 제외/대체 등 요청 제약 위반 코드와 금지·필수 표현 검사를 합쳐 제약 차원을 판정합니다.
    private EvalResult.Dimension gradeConstraint(
            EvalCase evalCase, GeneratedRecipeDraft draft, List<String> codes) {
        List<String> problems = new ArrayList<>(codes.stream().filter(CONSTRAINT_CODES::contains).toList());
        // 금지어는 요리에 실제로 들어갔는지로 판정합니다. 제목·설명·안전문구까지 보면
        // "돼지고기를 넣지 않는 김치찌개"처럼 회피를 설명한 문장이 위반으로 잡힙니다.
        // 프로덕션 판정도 자유 서술이 아니라 재료·조리 단계를 봅니다.
        String ingredientText = ingredientText(draft).toLowerCase(Locale.ROOT);
        for (String forbidden : evalCase.expectOrEmpty().forbiddenTermsOrEmpty()) {
            if (ingredientText.contains(forbidden.toLowerCase(Locale.ROOT))) {
                problems.add("금지어 포함: " + forbidden);
            }
        }
        // 필수어는 대체 재료가 실제로 쓰였는지를 보므로 같은 범위를 씁니다.
        for (String required : evalCase.expectOrEmpty().requiredTermsOrEmpty()) {
            if (!ingredientText.contains(required.toLowerCase(Locale.ROOT))) {
                problems.add("필수어 누락: " + required);
            }
        }
        return EvalResult.Dimension.of(EvalResult.CONSTRAINT, problems.isEmpty(), String.valueOf(problems));
    }

    // 레시피 전문에 금지 표현이 있거나 필수 표현이 없으면 실패입니다.
    private EvalResult.Dimension gradeTerms(EvalCase evalCase, String text) {
        List<String> problems = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String forbidden : evalCase.expectOrEmpty().forbiddenTermsOrEmpty()) {
            if (lower.contains(forbidden.toLowerCase(Locale.ROOT))) {
                problems.add("금지어 포함: " + forbidden);
            }
        }
        for (String required : evalCase.expectOrEmpty().requiredTermsOrEmpty()) {
            if (!lower.contains(required.toLowerCase(Locale.ROOT))) {
                problems.add("필수어 누락: " + required);
            }
        }
        return EvalResult.Dimension.of(EvalResult.CONSTRAINT, problems.isEmpty(), String.valueOf(problems));
    }

    // 구조화 레시피 텍스트에 thinking 누출, 마크다운 펜스, 이모지 같은 오염이 있는지 검사합니다.
    private EvalResult.Dimension gradeRecipeNoise(GeneratedRecipeDraft draft) {
        return gradeTextNoise(fullText(draft));
    }

    private EvalResult.Dimension gradeTextNoise(String text) {
        List<String> problems = new ArrayList<>();
        if (text.isBlank()) {
            problems.add("빈 출력");
        }
        if (THINKING_LEAK.matcher(text).find()) {
            problems.add("thinking 흔적 누출");
        }
        if (MARKDOWN_FENCE.matcher(text).find()) {
            problems.add("마크다운 코드펜스 포함");
        }
        if (EMOJI.matcher(text).find()) {
            problems.add("이모지 포함");
        }
        return EvalResult.Dimension.of(EvalResult.NOISE, problems.isEmpty(), String.valueOf(problems));
    }

    // replay 고정값이 있으면 실제 검증기 실패 코드 집합이 기대값과 정확히 같아야 합니다.
    private EvalResult.Dimension gradeRegression(EvalCase evalCase, Set<String> actualCodes) {
        if (!pinFailureCodes || !evalCase.expectOrEmpty().pinsFailureCodes()) {
            return EvalResult.Dimension.notApplicable(EvalResult.REGRESSION);
        }
        Set<String> expected = evalCase.expectOrEmpty().expectedFailureCodeSet();
        Set<String> actual = new LinkedHashSet<>(actualCodes);
        actual.remove("");
        return EvalResult.Dimension.of(
                EvalResult.REGRESSION,
                expected.equals(actual),
                "기대 " + expected + " != 실제 " + actual);
    }

    /**
     * 알레르겐 검사 대상 텍스트.
     *
     * <p>제목·재료·조리 단계만 본다. 프로덕션
     * {@code ChatSafetyContextService#findAllergyConflicts}와 같은 범위여야 평가 점수가
     * 실제 차단 동작과 일치한다. safetyNotes는 "우유 알레르기 주의"처럼 알레르겐 이름을
     * 정당하게 포함하므로 제외한다.
     */
    private List<String> allergenTexts(GeneratedRecipeDraft draft) {
        List<String> texts = new ArrayList<>();
        if (draft.title() != null) {
            texts.add(draft.title());
        }
        if (draft.ingredients() != null) {
            for (GeneratedIngredient ingredient : draft.ingredients()) {
                if (ingredient == null) {
                    continue;
                }
                texts.add(nullToBlank(ingredient.name()) + " " + nullToBlank(ingredient.preparation()));
            }
        }
        if (draft.steps() != null) {
            for (GeneratedCookingStep step : draft.steps()) {
                if (step == null) {
                    continue;
                }
                texts.add(nullToBlank(step.instruction()) + " " + nullToBlank(step.completionCue()));
                if (step.ingredientNames() != null) {
                    texts.addAll(step.ingredientNames());
                }
            }
        }
        return texts;
    }

    // 초안의 모든 사용자 노출 텍스트를 하나로 합칩니다(금지/필수 표현 검사용).
    /**
     * 요리에 실제로 들어간 재료만 모읍니다. 재료 목록과 각 단계가 선언한 사용 재료만 보며,
     * 제목·설명·안전문구·조리 서술은 제외합니다. 회피를 설명한 문장을 위반으로 세지 않기 위해서입니다.
     */
    private String ingredientText(GeneratedRecipeDraft draft) {
        StringBuilder builder = new StringBuilder();
        if (draft.ingredients() != null) {
            draft.ingredients().stream().filter(java.util.Objects::nonNull).forEach(ingredient ->
                    builder.append(nullToBlank(ingredient.name())).append(' ')
                            .append(nullToBlank(ingredient.preparation())).append('\n'));
        }
        if (draft.steps() != null) {
            draft.steps().stream().filter(java.util.Objects::nonNull)
                    .filter(step -> step.ingredientNames() != null)
                    .forEach(step -> step.ingredientNames().stream()
                            .filter(java.util.Objects::nonNull)
                            .forEach(name -> builder.append(name).append('\n')));
        }
        return builder.toString();
    }

    private String fullText(GeneratedRecipeDraft draft) {
        StringBuilder builder = new StringBuilder();
        builder.append(nullToBlank(draft.title())).append('\n');
        builder.append(nullToBlank(draft.description())).append('\n');
        if (draft.ingredients() != null) {
            draft.ingredients().stream().filter(java.util.Objects::nonNull).forEach(ingredient ->
                    builder.append(nullToBlank(ingredient.name())).append(' ')
                            .append(nullToBlank(ingredient.quantity())).append(' ')
                            .append(nullToBlank(ingredient.preparation())).append('\n'));
        }
        if (draft.steps() != null) {
            draft.steps().stream().filter(java.util.Objects::nonNull).forEach(step ->
                    builder.append(nullToBlank(step.instruction())).append(' ')
                            .append(nullToBlank(step.completionCue())).append(' ')
                            .append(nullToBlank(step.recoveryTip())).append('\n'));
        }
        if (draft.safetyNotes() != null) {
            draft.safetyNotes().forEach(note -> builder.append(nullToBlank(note)).append('\n'));
        }
        return builder.toString();
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
