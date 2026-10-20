package com.salus.healthytable.service;

/**
 * 레시피 생성 중 실패를 나타내는 예외입니다.
 * failureCode에 실패 종류를 담아 감사 기록과 로그에서 원인을 분류할 수 있게 합니다.
 */
public class RecipeGenerationException extends RuntimeException {

    // 실패 코드. 지정하지 않으면 "GENERATION_CALL_FAILED"(LLM 호출 실패)를 기본값으로 사용합니다.
    private final String failureCode;

    public RecipeGenerationException(String message) {
        this("GENERATION_CALL_FAILED", message, null);
    }

    public RecipeGenerationException(String message, Throwable cause) {
        this("GENERATION_CALL_FAILED", message, cause);
    }

    public RecipeGenerationException(String failureCode, String message) {
        this(failureCode, message, null);
    }

    public RecipeGenerationException(String failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode == null || failureCode.isBlank()
                ? "GENERATION_CALL_FAILED"
                : failureCode;
    }

    public String getFailureCode() {
        return failureCode;
    }
}
