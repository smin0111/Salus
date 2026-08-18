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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaRecipeGenerationClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "message", Map.of("role", "assistant", "content", content),
                    "done", true));
            return ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
