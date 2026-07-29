package com.salus.healthytable.service;

import reactor.core.publisher.Mono;

import java.util.List;

public interface RecipeGenerationClient {

    Mono<GeneratedRecipeDraft> generate(RecipeGenerationRequest request);

    Mono<GeneratedRecipeDraft> repair(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft invalidDraft,
            List<String> validationReasons);
}
