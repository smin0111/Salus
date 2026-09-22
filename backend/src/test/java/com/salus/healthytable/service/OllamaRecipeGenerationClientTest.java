package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OllamaRecipeGenerationClient} 테스트입니다. 가짜 HTTP 응답으로 JSON 파싱과 실패 코드를 확인합니다.
 */
class OllamaRecipeGenerationClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 정상 JSON 응답은 레시피 초안으로 변환되어야 합니다.
    @Test
    void normalJsonRecipeDeserializesToDraft() throws Exception {
        OllamaRecipeGenerationClient client = clientWithExchange(request -> Mono.just(okResponse("""
                {
                  "title": "토마토 샐러드",
                  "description": "가볍게 먹기 좋은 샐러드입니다.",
                  "servings": 1,
                  "cookingTimeMinutes": 10,
                  "caloriesKcal": 120,
                  "difficulty": 1,
                  "ingredients": [{"name": "토마토", "quantity": "1개"}],
                  "steps": [{"order": 1, "instruction": "토마토를 한입 크기로 자릅니다", "heatLevel": null, "minutes": null, "completionCue": "먹기 좋은 크기", "recoveryTip": "물기가 많으면 살짝 닦으세요"}],
                  "safetyNotes": []
                }
                """)));

        GeneratedRecipeDraft draft = client.generate(minimalRequest()).block();

        assertThat(draft).isNotNull();
        assertThat(draft.title()).isEqualTo("토마토 샐러드");
        assertThat(draft.ingredients()).hasSize(1);
        assertThat(draft.steps().get(0).heatLevel()).isNull();
    }

    // 마크다운 코드 블록으로 감싼 JSON은 순수 JSON이 아니므로 실패로 처리해야 합니다.
    @Test
    void markdownWrappedJsonFailsAsInvalidStructuredOutput() {
        OllamaRecipeGenerationClient client = clientWithExchange(request -> Mono.just(okResponse("""
                ```json
                {"title":"토마토 샐러드","difficulty":1,"ingredients":[],"steps":[]}
                ```
                """)));

        assertThatThrownBy(() -> client.generate(minimalRequest()).block())
                .isInstanceOf(RecipeGenerationException.class)
                .hasMessageContaining("순수 JSON");
    }

    // Ollama 응답의 thinking(추론 과정)은 content와 분리해서 읽어야 합니다.
    @Test
    void ollamaMessageDeserializesThinkingSeparatelyFromContent() throws Exception {
        OllamaLlmService.OllamaResponse response = objectMapper.readValue("""
                {
                  "message": {
                    "role": "assistant",
                    "content": "{\\"status\\":\\"ok\\"}",
                    "thinking": "private reasoning"
                  },
                  "done": true
                }
                """, OllamaLlmService.OllamaResponse.class);

        assertThat(response.getMessage().getContent()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(response.getMessage().getThinking()).isEqualTo("private reasoning");
    }

    // 2차 주소 설정이 없으면 가짜 대체 경로를 호출하지 않고 1번만 요청해야 합니다.
    @Test
    void missingSecondaryConfigurationDoesNotCallFakeFallbackPort() {
        AtomicInteger calls = new AtomicInteger();
        OllamaRecipeGenerationClient client = clientWithExchange(request -> {
            calls.incrementAndGet();
            return Mono.error(new IllegalStateException("primary unavailable"));
        });
        ReflectionTestUtils.setField(client, "secondaryUrl", "");

        assertThatThrownBy(() -> client.generate(minimalRequest()).block())
                .isInstanceOf(RecipeGenerationException.class)
                .hasMessageContaining("구조화 레시피 생성 호출에 실패");
        assertThat(calls).hasValue(1);
    }

    // 출력 토큰 한도로 잘린 응답은 OUTPUT_TOKEN_LIMIT 실패 코드로 보고해야 합니다.
    @Test
    void outputTokenLimitIsReportedWithAStableFailureCode() {
        OllamaRecipeGenerationClient client = clientWithExchange(request -> Mono.just(okResponse(
                "{\"title\":\"고등어무조림\"",
                "length",
                800)));

        assertThatThrownBy(() -> client.generate(minimalRequest()).block())
                .isInstanceOfSatisfying(RecipeGenerationException.class, error ->
                        assertThat(error.getFailureCode()).isEqualTo("OUTPUT_TOKEN_LIMIT"));
    }

    // 가짜 ExchangeFunction을 쓰는 WebClient로 클라이언트를 만듭니다.
    private OllamaRecipeGenerationClient clientWithExchange(ExchangeFunction exchangeFunction) {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(exchangeFunction)
                .build();
        RecipePromptFactory promptFactory = new RecipePromptFactory(objectMapper);
        OllamaRecipeGenerationClient client = new OllamaRecipeGenerationClient(webClient, objectMapper, promptFactory);
        ReflectionTestUtils.setField(client, "recipeModel", "test-model");
        ReflectionTestUtils.setField(client, "recipeTemperature", 0.15);
        ReflectionTestUtils.setField(client, "recipeTopP", 0.8);
        ReflectionTestUtils.setField(client, "recipeNumPredict", 1200);
        ReflectionTestUtils.setField(client, "recipeNumCtx", 8192);
        ReflectionTestUtils.setField(client, "recipeTimeoutSeconds", 5L);
        ReflectionTestUtils.setField(client, "primaryUrl", "http://primary.example/api/chat");
        ReflectionTestUtils.setField(client, "secondaryUrl", "http://secondary.example/api/chat");
        return client;
    }

    private RecipeGenerationRequest minimalRequest() {
        return new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE,
                "토마토 샐러드 레시피 알려줘",
                "토마토 샐러드",
                List.of(),
                "토마토 샐러드 재료: 토마토",
                "test",
                List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "",
                List.of(),
                List.of(),
                List.of());
    }

    private ClientResponse okResponse(String content) {
        return okResponse(content, "stop", null);
    }

    // 지정한 content/doneReason을 가진 Ollama 응답을 만듭니다.
    private ClientResponse okResponse(String content, String doneReason, Integer evalCount) {
        try {
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("message", Map.of("role", "assistant", "content", content));
            response.put("done", true);
            response.put("done_reason", doneReason);
            if (evalCount != null) {
                response.put("eval_count", evalCount);
            }
            String body = objectMapper.writeValueAsString(response);
            return ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
