package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link GeminiService} 단위 테스트입니다.
 */
class GeminiServiceTest {

    // 이미지 분석 모델이 연결되기 전까지 영수증 분석은 가짜 재료 대신 빈 JSON 배열("[]")을 반환해야 합니다.
    @Test
    void receiptAnalysisReturnsEmptyResultUntilVisionModelIsConnected() {
        GeminiService geminiService = new GeminiService(mock(LlmService.class));

        String response = geminiService.analyzeReceipt("base64-image").block();

        assertThat(response).isEqualTo("[]");
    }
}
