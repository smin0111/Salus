package com.salus.healthytable.eval;

import com.salus.healthytable.service.GeneratedRecipeDraft;

/** 한 번의 모델 호출과 한 케이스 실행에서 관측한 값들. 해석 없이 측정치만 담는다. */
public final class EvalRun {

    private EvalRun() {
    }

    /**
     * HTTP 호출 하나에서 측정한 값.
     *
     * @param rawOutput   모델 응답 본문 원문({@code message.content}). 가공하지 않는다
     * @param thinking    모델이 별도 필드로 돌려준 추론 블록. 없으면 빈 문자열
     * @param rawEnvelope Ollama 응답 전체 원문(JSON). 토큰 수·done_reason의 출처
     */
    public record Telemetry(
            String failureCode,
            String failureMessage,
            long latencyMs,
            boolean timeout,
            String rawOutput,
            String thinking,
            String rawEnvelope,
            Integer promptTokens,
            Integer completionTokens,
            String doneReason) {

        // 관측값 없이 지연 시간만 있는 telemetry를 만듭니다.
        public static Telemetry empty(long latencyMs) {
            return new Telemetry(null, null, latencyMs, false, "", "", "", null, null, null);
        }
    }

    /** 구조화 레시피 호출 1회. 실패하면 {@code draft}가 null이고 telemetry에 실패 코드가 담긴다. */
    public record RecipeCall(GeneratedRecipeDraft draft, Telemetry telemetry) {

        public boolean succeeded() {
            return draft != null;
        }
    }

    /**
     * 케이스 하나의 레시피 실행 전체. 프로덕션과 동일하게 repair는 최대 1회다.
     *
     * @param repair 재호출하지 않았으면 null
     */
    public record RecipeRun(
            RecipeCall first,
            RecipeCall repair,
            boolean firstDraftValid,
            boolean repairPassed) {

        // repair를 했다면 repair 호출, 아니면 최초 호출이 최종 결과입니다.
        public RecipeCall finalCall() {
            return repair == null ? first : repair;
        }

        public boolean repairAttempted() {
            return repair != null;
        }

        public long totalLatencyMs() {
            return first.telemetry().latencyMs()
                    + (repair == null ? 0 : repair.telemetry().latencyMs());
        }

        // 최종 호출이 시간 초과로 끝났는지 여부
        public boolean timedOut() {
            return first.telemetry().timeout()
                    || (repair != null && repair.telemetry().timeout());
        }
    }

    /** 채팅 호출 1회. */
    public record ChatCall(String reply, Telemetry telemetry) {
    }
}
