package com.salus.healthytable.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모델명 "qwen3:8b"의 콜론이 Spring 기본값 구분자와 겹친다.
 * ${OLLAMA_MODEL:qwen3:8b}가 의도대로 해석되는지 고정한다.
 */
class OllamaModelDefaultTest {

    private StandardEnvironment environmentWith(Map<String, Object> overrides) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(input);
        }
        Map<String, Object> source = new HashMap<>();
        properties.forEach((key, value) -> source.put(String.valueOf(key), value));

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application", source));
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        }
        return environment;
    }

    @Test
    @DisplayName("환경변수가 없으면 qwen3:8b로 해석된다")
    void defaultResolvesToQwen3() throws Exception {
        StandardEnvironment environment = environmentWith(Map.of());
        assertThat(environment.resolveRequiredPlaceholders("${ollama.model}")).isEqualTo("qwen3:8b");
        assertThat(environment.resolveRequiredPlaceholders("${ollama.chat-model}")).isEqualTo("qwen3:8b");
        assertThat(environment.resolveRequiredPlaceholders("${ollama.recipe-model}")).isEqualTo("qwen3:8b");
    }

    @Test
    @DisplayName("OLLAMA_MODEL 환경변수가 기본값을 덮어쓴다")
    void environmentVariableOverridesDefault() throws Exception {
        StandardEnvironment environment = environmentWith(Map.of("OLLAMA_MODEL", "gemma4:12b"));
        assertThat(environment.resolveRequiredPlaceholders("${ollama.model}")).isEqualTo("gemma4:12b");
        assertThat(environment.resolveRequiredPlaceholders("${ollama.recipe-model}")).isEqualTo("gemma4:12b");
    }

    @Test
    @DisplayName("레시피 전용 모델을 따로 지정할 수 있다")
    void recipeModelCanBeSetIndependently() throws Exception {
        StandardEnvironment environment = environmentWith(Map.of(
                "OLLAMA_MODEL", "qwen3:8b",
                "OLLAMA_RECIPE_MODEL", "gemma4:12b"));
        assertThat(environment.resolveRequiredPlaceholders("${ollama.chat-model}")).isEqualTo("qwen3:8b");
        assertThat(environment.resolveRequiredPlaceholders("${ollama.recipe-model}")).isEqualTo("gemma4:12b");
    }
}
