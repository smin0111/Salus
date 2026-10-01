package com.salus.healthytable.eval;

import java.util.List;

/**
 * 케이스 1회 실행의 기록. 모델 축이 생겼으므로 (model, caseId, iteration)이 식별자다.
 *
 * <p>필드는 리포트용 요약이 아니라 원본 관측값이다. rawOutput은 모델 응답 원문 그대로이고,
 * 실패는 프로덕션 검증기가 돌려준 코드와 사유를 그대로 담는다.
 */
public record EvalResult(
        String runId,
        String caseId,
        String suite,
        String model,
        int iteration,
        String input,
        List<String> expectedConditions,
        String ragCondition,
        String rawOutput,
        String rawOutputFirstDraft,
        String rawEnvelope,
        Integer promptTokens,
        Integer completionTokens,
        String doneReason,
        long latencyMs,
        boolean timeout,
        String failureCode,
        boolean validatorPassed,
        List<String> validatorCodes,
        List<String> validatorReasons,
        List<String> allergenConflicts,
        boolean firstDraftValid,
        boolean repairAttempted,
        boolean repairPassed,
        List<Dimension> dimensions,
        double score,
        String verdict,
        String judgeModel,
        String judgeVerdict,
        String judgeRationale,
        Boolean judgeAgreement) {

    /** 채점 차원. 케이스에 해당 없으면 {@code applicable=false}로 두고 집계에서 제외한다. */
    public record Dimension(String name, boolean applicable, boolean passed, String detail) {

        public static Dimension pass(String name) {
            return new Dimension(name, true, true, "");
        }

        public static Dimension fail(String name, String detail) {
            return new Dimension(name, true, false, detail);
        }

        public static Dimension of(String name, boolean passed, String detail) {
            return new Dimension(name, true, passed, passed ? "" : detail);
        }

        public static Dimension notApplicable(String name) {
            return new Dimension(name, false, true, "");
        }
    }

    /** 전달(호출) 성공 여부. 이게 깨지면 나머지 차원은 채점 의미가 없다. */
    public static final String TRANSPORT = "transport";
    /** 필수 필드·타입이 스키마를 만족하는지. */
    public static final String SCHEMA = "schema";
    /** 선언된 알레르기와 충돌하는 재료가 남아 있는지. 안전 게이트. */
    public static final String ALLERGEN_SAFETY = "allergen_safety";
    /** 레시피 검증기 v2.0 전체 통과 여부. */
    public static final String VALIDATOR = "validator";
    /** 제외·대체·금지어 같은 요청 제약 준수 여부. */
    public static final String CONSTRAINT = "constraint";
    /** thinking 누출, 빈 응답, 마크다운 펜스 같은 출력 오염. */
    public static final String NOISE = "noise";
    /** replay 고정값(expectedFailureCodes) 일치 여부. */
    public static final String REGRESSION = "regression";

    public static final List<String> DIMENSION_ORDER = List.of(
            TRANSPORT, SCHEMA, ALLERGEN_SAFETY, VALIDATOR, CONSTRAINT, NOISE, REGRESSION);

    public static final String VERDICT_PASS = "PASS";
    public static final String VERDICT_FAIL = "FAIL";
    /** 안전 차원 실패. 총점과 무관하게 실패로 표시한다. */
    public static final String VERDICT_HARD_FAIL = "HARD_FAIL";

    /**
     * Judge 교차평가 결과를 덧붙인다.
     *
     * <p>production 판정({@link #verdict})은 그대로 두고 별도 필드에만 기록한다.
     * Judge가 프로덕션 검증기 판정을 덮어쓰면 안 된다.
     */
    public EvalResult withJudge(String judgeModel, String judgeVerdict, String judgeRationale) {
        Boolean agreement = judgeVerdict == null
                ? null
                : judgeVerdict.equals(VERDICT_PASS.equals(verdict) ? VERDICT_PASS : VERDICT_FAIL);
        return new EvalResult(
                runId, caseId, suite, model, iteration, input, expectedConditions, ragCondition,
                rawOutput, rawOutputFirstDraft, rawEnvelope, promptTokens, completionTokens, doneReason,
                latencyMs, timeout, failureCode, validatorPassed, validatorCodes, validatorReasons,
                allergenConflicts, firstDraftValid, repairAttempted, repairPassed, dimensions,
                score, verdict, judgeModel, judgeVerdict, judgeRationale, agreement);
    }

    // 판정이 PASS인지 여부
    public boolean isPass() {
        return VERDICT_PASS.equals(verdict);
    }

    public boolean isHardFail() {
        return VERDICT_HARD_FAIL.equals(verdict);
    }

    // 해당되면서 실패한 채점 차원 이름 목록
    public List<String> failedDimensionNames() {
        return dimensions.stream()
                .filter(Dimension::applicable)
                .filter(dimension -> !dimension.passed())
                .map(Dimension::name)
                .toList();
    }

    public List<String> failureDetails() {
        return dimensions.stream()
                .filter(Dimension::applicable)
                .filter(dimension -> !dimension.passed())
                .map(dimension -> dimension.name() + ": " + dimension.detail())
                .toList();
    }

    public boolean dimensionApplicable(String name) {
        return dimensions.stream().anyMatch(d -> d.name().equals(name) && d.applicable());
    }

    public boolean dimensionFailed(String name) {
        return dimensions.stream().anyMatch(d -> d.name().equals(name) && d.applicable() && !d.passed());
    }

    /**
     * 리포트의 "문제점"에 그대로 쓰는 목록. 채점 차원 실패 + 검증기 코드 + 알레르겐 충돌을 합친다.
     * 관측된 것만 넣고 해석은 붙이지 않는다.
     */
    public List<String> problemTypes() {
        List<String> problems = new java.util.ArrayList<>();
        if (timeout) {
            problems.add("TIMEOUT");
        }
        if (failureCode != null) {
            problems.add(failureCode);
        }
        problems.addAll(validatorCodes);
        if (!allergenConflicts.isEmpty()) {
            problems.add("ALLERGEN_CONFLICT(" + String.join(",", allergenConflicts) + ")");
        }
        for (Dimension dimension : dimensions) {
            if (dimension.applicable() && !dimension.passed()
                    && !VALIDATOR.equals(dimension.name())
                    && !TRANSPORT.equals(dimension.name())) {
                problems.add(dimension.name().toUpperCase() + "_FAILED");
            }
        }
        return problems.stream().distinct().toList();
    }
}
