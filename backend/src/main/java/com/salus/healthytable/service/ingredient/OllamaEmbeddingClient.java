package com.salus.healthytable.service.ingredient;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ollama /api/embed로 문장 벡터를 받습니다. 실패하면 빈 값을 돌려주고, 호출하는 쪽은 문자열 규칙만으로 답합니다.
 * 벡터는 답을 고르는 근거가 아니라 '질문과 가장 비슷한 문장'을 고르는 데만 씁니다.
 */
@Slf4j
@Component
public class OllamaEmbeddingClient {

    private final WebClient webClient;
    private final String embedUrl;
    private final String model;

    public OllamaEmbeddingClient(
            WebClient webClient,
            @Value("${ollama.primary-url:http://localhost:11434/api/chat}") String chatUrl,
            @Value("${salus.ingredient-info.embed-url:}") String embedUrl,
            @Value("${salus.ingredient-info.embedding-model:bge-m3}") String model) {
        this.webClient = webClient;
        this.embedUrl = embedUrl == null || embedUrl.isBlank() ? deriveEmbedUrl(chatUrl) : embedUrl;
        this.model = model;
    }

    /** 채팅 주소(…/api/chat)와 같은 Ollama의 /api/embed를 씁니다. */
    static String deriveEmbedUrl(String chatUrl) {
        return chatUrl.replaceFirst("/api/chat/?$", "") + "/api/embed";
    }

    public Optional<List<float[]>> embed(List<String> texts, Duration timeout) {
        if (texts.isEmpty()) {
            return Optional.of(List.of());
        }
        try {
            JsonNode response = webClient.post()
                    .uri(embedUrl)
                    .bodyValue(Map.of("model", model, "input", texts))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();
            JsonNode embeddings = response == null ? null : response.path("embeddings");
            if (embeddings == null || !embeddings.isArray() || embeddings.size() != texts.size()) {
                log.warn("[IngredientInfo] embedding response has unexpected shape");
                return Optional.empty();
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (JsonNode embedding : embeddings) {
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
            return Optional.of(vectors);
        } catch (RuntimeException error) {
            log.warn("[IngredientInfo] embedding unavailable. category={}", error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}
