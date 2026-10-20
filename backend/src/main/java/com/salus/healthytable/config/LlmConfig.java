package com.salus.healthytable.config;

import com.salus.healthytable.service.LlmService;
import com.salus.healthytable.service.OllamaLlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 애플리케이션 전체에서 사용할 기본 LLM(대규모 언어 모델) 구현체를 정하는 설정 클래스입니다.
 *
 * 서비스 코드는 {@link LlmService} 인터페이스에만 의존하고, 실제 구현은 여기서 골라 줍니다.
 * 현재는 로컬에서 실행되는 Ollama({@link OllamaLlmService})를 기본값으로 사용합니다.
 */
@Slf4j
@Configuration
public class LlmConfig {

    private final OllamaLlmService ollamaLlmService;

    public LlmConfig(OllamaLlmService ollamaLlmService) {
        this.ollamaLlmService = ollamaLlmService;
    }

    // @Primary: 같은 타입(LlmService)의 Bean이 여러 개 있어도 주입할 때 이 Bean을 우선 선택하게 합니다.
    @Bean
    @Primary
    public LlmService llmService() {
        log.info(">>> [LLM 서비스 로더] 로컬 Ollama 엔진을 기본 활성화합니다. (보안 극대화/오프라인)");
        return ollamaLlmService;
    }
}
