package com.salus.healthytable.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.dto.ChatDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaRecipeGenerationClient implements RecipeGenerationClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final RecipePromptFactory recipePromptFactory;

    @Value("${ollama.recipe-model:${ollama.model:gemma2}}")
    private String recipeModel;

    @Value("${ollama.recipe-temperature:0.15}")
    private double recipeTemperature;

    @Value("${ollama.recipe-top-p:0.8}")
    private double recipeTopP;

    @Value("${ollama.recipe-num-predict:1200}")
    private int recipeNumPredict;

    @Value("${ollama.recipe-timeout-seconds:${ollama.timeout-seconds:180}}")
    private long recipeTimeoutSeconds;

    @Value("${ollama.primary-url:http://localhost:11434/api/chat}")
    private String primaryUrl;

    @Value("${ollama.secondary-url:http://localhost:11435/api/chat}")
    private String secondaryUrl;

    @Override
    public Mono<GeneratedRecipeDraft> generate(RecipeGenerationRequest request) {
        return callStructuredRecipe(recipePromptFactory.buildGenerationPrompt(request));
    }

    @Override
    public Mono<GeneratedRecipeDraft> repair(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft invalidDraft,
            List<String> validationReasons) {
        return callStructuredRecipe(recipePromptFactory.buildRepairPrompt(request, invalidDraft, validationReasons));
    }

    private Mono<GeneratedRecipeDraft> callStructuredRecipe(String prompt) {
        OllamaLlmService.OllamaRequest request = new OllamaLlmService.OllamaRequest(
                recipeModel,
                List.of(
                        new OllamaLlmService.OllamaMessage("system",
                                "JSON Schema를 따르는 JSON 객체 하나만 출력하세요."),
                        new OllamaLlmService.OllamaMessage("user", prompt)),
                false,
                thinkValue(recipeModel),
                Map.of(
                        "temperature", recipeTemperature,
                        "top_p", recipeTopP,
                        "num_predict", recipeNumPredict),
                recipePromptFactory.jsonSchema());

        log.info("[OllamaRecipe] Initiating structured recipe request using model: {}...", recipeModel);
        return post(primaryUrl, request)
                .onErrorResume(primaryError -> {
                    log.warn("[OllamaRecipe] Primary instance failed: {}. Trying secondary...", primaryError.getMessage());
                    return post(secondaryUrl, request);
                })
                .onErrorMap(error -> error instanceof RecipeGenerationException
                        ? error
                        : new RecipeGenerationException("구조화 레시피 생성 호출에 실패했습니다.", error));
    }

    private Mono<GeneratedRecipeDraft> post(String url, OllamaLlmService.OllamaRequest request) {
        return webClient.post()
                .uri(url)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OllamaLlmService.OllamaResponse.class)
                .timeout(Duration.ofSeconds(recipeTimeoutSeconds))
                .map(response -> {
                    if (response == null || response.getMessage() == null
                            || response.getMessage().getContent() == null
                            || response.getMessage().getContent().isBlank()) {
                        throw new RecipeGenerationException("Ollama 구조화 응답이 비어 있습니다.");
                    }
                    return parseDraft(response.getMessage().getContent());
                });
    }

    private GeneratedRecipeDraft parseDraft(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new RecipeGenerationException("Ollama 응답이 순수 JSON 객체가 아닙니다.");
        }
        try {
            return objectMapper.readValue(trimmed, GeneratedRecipeDraft.class);
        } catch (JsonProcessingException e) {
            throw new RecipeGenerationException("Ollama JSON 레시피 응답 파싱에 실패했습니다.", e);
        }
    }

    private Boolean thinkValue(String model) {
        if (model == null) {
            return null;
        }
        return model.toLowerCase(Locale.ROOT).startsWith("qwen3") ? Boolean.FALSE : null;
    }
}
