package com.salus.healthytable.exception;

/** 레시피 검색 후 구조화 생성·검증 파이프라인이 허용된 전체 시간 예산을 초과한 경우입니다. */
public class RecipeGenerationTimeoutException extends RuntimeException {

    // 시간 초과가 발생한 파이프라인 단계 이름 (로그/응답 분석용)
    private final String stage;

    public RecipeGenerationTimeoutException(String stage) {
        super("레시피 생성 시간 예산을 초과했습니다: " + stage);
        this.stage = stage;
    }

    public String getStage() {
        return stage;
    }
}
