package com.mychefai.healthytable.config;

import com.mychefai.healthytable.service.LlmService;
import com.mychefai.healthytable.service.OllamaLlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Slf4j
@Configuration
public class LlmConfig {

    private final OllamaLlmService ollamaLlmService;

    public LlmConfig(OllamaLlmService ollamaLlmService) {
        this.ollamaLlmService = ollamaLlmService;
    }

    @Bean
    @Primary
    public LlmService llmService() {
        log.info(">>> [LLM 서비스 로더] 로컬 Ollama 엔진을 기본 활성화합니다. (보안 극대화/오프라인)");
        return ollamaLlmService;
    }
}
