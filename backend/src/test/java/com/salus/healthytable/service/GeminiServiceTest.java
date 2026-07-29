package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GeminiServiceTest {

    @Test
    void receiptAnalysisReturnsEmptyResultUntilVisionModelIsConnected() {
        GeminiService geminiService = new GeminiService(mock(LlmService.class));

        String response = geminiService.analyzeReceipt("base64-image").block();

        assertThat(response).isEqualTo("[]");
    }
}
