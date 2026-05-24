package com.mychefai.healthytable.config;

import com.mychefai.healthytable.service.GeminiLlmService;
import com.mychefai.healthytable.service.LlmService;
import com.mychefai.healthytable.service.OllamaLlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Slf4j
@Configuration
public class LlmConfig {

    @Value("${llm.provider:gemini}")
    private String llmProvider;

    private final GeminiLlmService geminiLlmService;
    private final OllamaLlmService ollamaLlmService;

    public LlmConfig(GeminiLlmService geminiLlmService, OllamaLlmService ollamaLlmService) {
        this.geminiLlmService = geminiLlmService;
        this.ollamaLlmService = ollamaLlmService;
    }

    @Bean
    @Primary
    public LlmService llmService() {
        log.info(">>> [LLM 서비스 로더] 현재 선택된 LLM 엔진: [{}]", llmProvider.toUpperCase());

        if ("ollama".equalsIgnoreCase(llmProvider)) {
            log.info(">>> 맥북 로컬 Ollama (Llama 3) 엔진을 기본 활성화합니다. (보안 극대화/오프라인)");
            return ollamaLlmService;
        }

        log.info(">>> 구글 Gemini 2.0 Flash API 엔진을 기본 활성화합니다. (클라우드/고성능)");
        return geminiLlmService;
    }
}
