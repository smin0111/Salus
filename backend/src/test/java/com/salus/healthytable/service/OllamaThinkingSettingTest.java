package com.salus.healthytable.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * qwen3는 thinking이 기본 활성이라 num_predict 예산을 추론에 먼저 소모한다.
 * 채팅 기본값 700 토큰에서는 추론만 하다 한도에 걸려 답변이 비어서 나갔다.
 * 채팅과 레시피 경로가 같은 판단을 쓰는지 고정한다.
 *
 * thinkingSettingFor 반환값: false = 요청에 think=false를 넣어 추론 모드를 끔, null = think 옵션을 보내지 않고 모델 기본값 사용
 */
class OllamaThinkingSettingTest {

    // 태그(:8b, :14b)나 대소문자와 상관없이 qwen3 계열이면 false여야 합니다.
    @Test
    @DisplayName("qwen3 계열은 thinking을 끈다")
    void qwen3DisablesThinking() {
        assertThat(OllamaLlmService.thinkingSettingFor("qwen3:8b")).isFalse();
        assertThat(OllamaLlmService.thinkingSettingFor("qwen3:14b")).isFalse();
        assertThat(OllamaLlmService.thinkingSettingFor("QWEN3:8B")).isFalse();
    }

    // qwen3가 아닌 모델이나 모델 이름이 없으면(null) 옵션을 건드리지 않아야 합니다.
    @Test
    @DisplayName("그 외 모델은 기본값을 유지한다")
    void otherModelsKeepDefault() {
        assertThat(OllamaLlmService.thinkingSettingFor("gemma2")).isNull();
        assertThat(OllamaLlmService.thinkingSettingFor("gemma4:12b")).isNull();
        assertThat(OllamaLlmService.thinkingSettingFor("llama3")).isNull();
        assertThat(OllamaLlmService.thinkingSettingFor(null)).isNull();
    }
}
