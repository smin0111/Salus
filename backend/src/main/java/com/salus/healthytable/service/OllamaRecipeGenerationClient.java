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

/**
 * Ollama로 "구조화 레시피(JSON)"를 생성하는 클라이언트입니다.
 *
 * 일반 채팅과 달리 Ollama의 format 옵션에 JSON Schema를 넘겨, 정해진 필드 구조의 JSON만 출력하게 합니다.
 * 응답은 GeneratedRecipeDraft로 파싱되며, 형식이 조금이라도 어긋나면 실패 코드와 함께 예외를 던집니다.
 * (LLM 출력은 신뢰할 수 없으므로, 파싱에 실패한 응답을 억지로 고쳐 쓰지 않습니다.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaRecipeGenerationClient implements RecipeGenerationClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final RecipePromptFactory recipePromptFactory;

    // 레시피 전용 설정이 없으면 일반 모델 설정을 사용합니다(${A:${B:기본값}} 형태의 중첩 기본값).
    @Value("${ollama.recipe-model:${ollama.model:gemma2}}")
    private String recipeModel;

    // temperature/top_p가 낮을수록 답변이 덜 무작위적입니다. 레시피는 일관성이 중요해 0에 가깝게 둡니다.
    @Value("${ollama.recipe-temperature:0.0}")
    private double recipeTemperature;

    @Value("${ollama.recipe-top-p:0.3}")
    private double recipeTopP;

    // num_predict: 최대 출력 토큰 수, num_ctx: 입력+출력을 합친 컨텍스트 창 크기
    @Value("${ollama.recipe-num-predict:1200}")
    private int recipeNumPredict;

    @Value("${ollama.recipe-num-ctx:8192}")
    private int recipeNumCtx;

    @Value("${ollama.recipe-timeout-seconds:${ollama.timeout-seconds:180}}")
    private long recipeTimeoutSeconds;

    @Value("${ollama.primary-url:http://localhost:11434/api/chat}")
    private String primaryUrl;

    @Value("${ollama.secondary-url:}")
    private String secondaryUrl;

    // 요청 조건으로 생성 프롬프트를 만들어 레시피 초안을 생성합니다.
    @Override
    public Mono<GeneratedRecipeDraft> generate(RecipeGenerationRequest request) {
        return callStructuredRecipe(recipePromptFactory.buildGenerationPrompt(request));
    }

    // 검증 실패 초안과 실패 이유로 복구(repair) 프롬프트를 만들어 다시 생성합니다.
    @Override
    public Mono<GeneratedRecipeDraft> repair(
            RecipeGenerationRequest request,
            GeneratedRecipeDraft invalidDraft,
            List<String> validationReasons) {
        return callStructuredRecipe(recipePromptFactory.buildRepairPrompt(request, invalidDraft, validationReasons));
    }

    /**
     * 프롬프트를 Ollama에 보내 JSON 레시피 초안을 받습니다.
     * 1차 인스턴스가 실패하면 2차 인스턴스로 재시도하고, 그래도 실패하면 RecipeGenerationException으로 감싸 던집니다.
     */
    private Mono<GeneratedRecipeDraft> callStructuredRecipe(String prompt) {
        OllamaLlmService.OllamaRequest request = new OllamaLlmService.OllamaRequest(
                recipeModel,
                List.of(
                        new OllamaLlmService.OllamaMessage("system",
                                "JSON Schema를 따르는 JSON 객체 하나만 출력하세요."),
                        new OllamaLlmService.OllamaMessage("user", prompt)),
                false,
                // qwen3 thinking 끄기 여부는 OllamaLlmService.thinkingSettingFor 한 곳에서만 결정합니다.
                thinkValue(recipeModel),
                Map.of(
                        "temperature", recipeTemperature,
                        "top_p", recipeTopP,
                        "num_predict", recipeNumPredict,
                        "num_ctx", recipeNumCtx),
                recipePromptFactory.jsonSchema());

        log.info("[OllamaRecipe] Initiating structured recipe request. model={}, promptChars={}, numCtx={}, numPredict={}",
                recipeModel, prompt.length(), recipeNumCtx, recipeNumPredict);
        Mono<GeneratedRecipeDraft> response = post(primaryUrl, request);
        if (secondaryUrl != null && !secondaryUrl.isBlank() && !secondaryUrl.equals(primaryUrl)) {
            response = response.onErrorResume(primaryError -> {
                log.warn("[OllamaRecipe] Primary instance failed. Trying configured secondary instance. category={}",
                        primaryError.getClass().getSimpleName());
                return post(secondaryUrl, request);
            });
        }
        return response
                .onErrorMap(error -> error instanceof RecipeGenerationException
                        ? error
                        : new RecipeGenerationException("구조화 레시피 생성 호출에 실패했습니다.", error));
    }

    // 실제 HTTP 호출: 응답이 비었거나 토큰 한도로 잘렸으면 실패로 처리하고, 정상이면 JSON을 파싱합니다.
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
                    log.info("[OllamaRecipe] Structured response received. promptTokens={}, completionTokens={}, doneReason={}",
                            response.getPromptEvalCount(), response.getEvalCount(), response.getDoneReason());
                    // 출력이 토큰 한도에서 잘리면 JSON이 불완전하므로 파싱을 시도하지 않고 실패로 기록합니다.
                    if ("length".equalsIgnoreCase(response.getDoneReason())) {
                        throw new RecipeGenerationException(
                                "OUTPUT_TOKEN_LIMIT",
                                "Ollama 구조화 응답이 출력 토큰 한도에서 종료되었습니다.");
                    }
                    return parseDraft(response.getMessage().getContent());
                });
    }

    // 응답 전체가 JSON 객체({ ... })인지 확인한 뒤 GeneratedRecipeDraft로 변환합니다. 앞뒤에 다른 글이 섞이면 실패입니다.
    private GeneratedRecipeDraft parseDraft(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new RecipeGenerationException(
                    "OUTPUT_NOT_JSON",
                    "Ollama 응답이 순수 JSON 객체가 아닙니다.");
        }
        try {
            return objectMapper.readValue(trimmed, GeneratedRecipeDraft.class);
        } catch (JsonProcessingException e) {
            throw new RecipeGenerationException(
                    "OUTPUT_JSON_PARSE_FAILED",
                    "Ollama JSON 레시피 응답 파싱에 실패했습니다.",
                    e);
        }
    }

    // thinking 설정 결정을 OllamaLlmService에 위임합니다(결정 지점을 한 곳으로 유지).
    private Boolean thinkValue(String model) {
        return OllamaLlmService.thinkingSettingFor(model);
    }
}
