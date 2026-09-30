package com.salus.healthytable.service;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 구조화 레시피를 생성하는 LLM 클라이언트 인터페이스입니다(구현: OllamaRecipeGenerationClient).
 */
public interface RecipeGenerationClient {

    // 요청 조건에 맞는 레시피 초안을 새로 생성합니다.
    Mono<GeneratedRecipeDraft> generate(RecipeGenerationRequest request);

    /**
     * 모델을 지정해 초안을 생성합니다.
     *
     * <p>같은 모델로 복구를 시도하면 같은 실패가 반복됩니다. 측정에서 24개 (모델, 케이스) 조합이
     * 전부 3/3 또는 0/3으로 갈렸고, 두 모델의 실패 지점은 거의 겹치지 않았습니다
     * (qwen3 7/12, gemma3:4b 5/12, 합집합 10/12). 그래서 마지막 시도는 다른 모델로 넘깁니다.
     *
     * <p>기본 구현은 모델을 무시하고 {@link #generate}를 호출하므로 기존 구현체는 영향이 없습니다.
     */
    default Mono<GeneratedRecipeDraft> generateWith(RecipeGenerationRequest request, String model) {
        return generate(request);
    }

    // 검증에 실패한 초안과 실패 이유를 LLM에 다시 보내 고친 초안을 받습니다.
    Mono<GeneratedRecipeDraft> repair(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft invalidDraft,
            List<String> validationReasons);
}
