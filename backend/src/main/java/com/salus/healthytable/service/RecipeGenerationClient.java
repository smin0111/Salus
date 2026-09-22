package com.salus.healthytable.service;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 구조화 레시피를 생성하는 LLM 클라이언트 인터페이스입니다(구현: OllamaRecipeGenerationClient).
 */
public interface RecipeGenerationClient {

    // 요청 조건에 맞는 레시피 초안을 새로 생성합니다.
    Mono<GeneratedRecipeDraft> generate(RecipeGenerationRequest request);

    // 검증에 실패한 초안과 실패 이유를 LLM에 다시 보내 고친 초안을 받습니다.
    Mono<GeneratedRecipeDraft> repair(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft invalidDraft,
            List<String> validationReasons);
}
