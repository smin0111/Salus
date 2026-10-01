package com.salus.healthytable.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.service.OllamaLlmService;
import com.salus.healthytable.service.OllamaRecipeGenerationClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 비교할 모델 이름을 프로덕션 클라이언트에 갈아 끼운다.
 *
 * <p>프로덕션 코드는 건드리지 않는다. {@code @Value}로 주입된 모델 이름 필드만 테스트 측에서
 * 바꿔 끼우고, temperature·top_p·num_predict·num_ctx·timeout은 프로퍼티 값 그대로 둔다.
 * 그래야 모델만 다르고 나머지 조건은 동일한 비교가 된다.
 *
 * <p>thinking 차단 여부는 클라이언트가 호출 시점에 모델 이름으로 다시 계산하므로
 * ({@code OllamaLlmService#thinkingSettingFor}) 모델을 바꾸면 그 판단도 함께 따라온다.
 */
public class EvalModelBinder {

    private static final String RECIPE_MODEL_FIELD = "recipeModel";
    private static final String CHAT_MODEL_FIELD = "ollamaModel";

    // 레시피 클라이언트와 채팅 서비스의 모델 이름 필드를 리플렉션으로 바꿉니다.
    public void bind(OllamaRecipeGenerationClient recipeClient, OllamaLlmService chatService, String model) {
        ReflectionTestUtils.setField(recipeClient, RECIPE_MODEL_FIELD, model);
        ReflectionTestUtils.setField(chatService, CHAT_MODEL_FIELD, model);
    }

    /** Judge 전용 인스턴스처럼 채팅 경로만 따로 묶을 때 쓴다. */
    public void bindChat(OllamaLlmService chatService, String model) {
        ReflectionTestUtils.setField(chatService, CHAT_MODEL_FIELD, model);
    }

    // 레시피 클라이언트에 현재 설정된 모델 이름을 읽습니다.
    public String currentRecipeModel(OllamaRecipeGenerationClient recipeClient) {
        return String.valueOf(ReflectionTestUtils.getField(recipeClient, RECIPE_MODEL_FIELD));
    }

    /** {@code /api/tags} 원문에서 설치된 모델 이름을 뽑는다. 파싱 실패 시 빈 집합. */
    public static Set<String> installedModels(String tagsJson, ObjectMapper objectMapper) {
        Set<String> names = new LinkedHashSet<>();
        if (tagsJson == null || tagsJson.isBlank()) {
            return names;
        }
        try {
            JsonNode models = objectMapper.readTree(tagsJson).path("models");
            for (JsonNode model : models) {
                String name = model.path("name").asText("");
                if (!name.isBlank()) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception e) {
            return names;
        }
        return names;
    }

    /** Ollama는 태그를 생략하면 {@code :latest}로 해석한다. 같은 규칙으로 설치 여부를 본다. */
    public static boolean isInstalled(Set<String> installedModels, String model) {
        if (installedModels.isEmpty() || model == null || model.isBlank()) {
            return false;
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        return installedModels.contains(normalized)
                || (!normalized.contains(":") && installedModels.contains(normalized + ":latest"));
    }

    /** 파일 경로에 쓸 수 있는 모델 이름. {@code qwen3:8b} → {@code qwen3_8b} */
    public static String slug(String model) {
        return model == null ? "unknown" : model.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
