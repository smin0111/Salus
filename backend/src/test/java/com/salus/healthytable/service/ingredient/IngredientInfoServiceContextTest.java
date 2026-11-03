package com.salus.healthytable.service.ingredient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring이 재료 정보 빈을 실제로 만들 수 있는지 확인합니다(단위 테스트는 생성자를 직접 불러 이 문제를 못 잡음). */
class IngredientInfoServiceContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withBean(WebClient.class, WebClient::create)
            .withBean(OllamaEmbeddingClient.class)
            .withBean(IngredientInfoService.class);

    @Test
    void createsBeanWhenDisabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IngredientInfoService.class).answer("무청이 뭐야?")).isEmpty();
        });
    }

    @Test
    void createsBeanAndLoadsCorpusWhenEnabled() {
        runner.withPropertyValues("salus.ingredient-info.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IngredientInfoService.class).answer("무청이 뭐야?"))
                    .hasValueSatisfying(answer -> assertThat(answer.reply()).startsWith("무청은"));
        });
    }
}
