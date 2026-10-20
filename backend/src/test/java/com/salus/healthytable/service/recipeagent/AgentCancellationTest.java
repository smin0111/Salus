package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.service.ChatCancellation;
import com.salus.healthytable.service.ChatProgress;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사용자가 멈춘 요청은 Recipe Agent가 다음 단계로 넘어가거나 Ollama를 부르지 않아야 합니다.
 * 2026-10-15 참치마요덮밥: 페이지 확인 중에 멈췄는데 추출 LLM이 25초 돌아 다음 질문이 기다렸습니다.
 */
class AgentCancellationTest {

    // 멈춘 요청이면 단계를 바꾸는 순간 멈추고, 멈추지 않은 요청은 그대로 진행합니다.
    @Test
    void stageReportStopsCancelledRequest() throws Exception {
        String id = "agent-cancel-stage";
        ChatCancellation.register(id);
        ChatProgress.start(id, "답변을 준비하고 있어요");

        ChatProgress.bind(id, () -> {
            ChatProgress.report("웹에서 레시피를 검색하고 있어요");
            return null;
        });
        assertThat(ChatCancellation.cancel(id)).isTrue();

        assertThatThrownBy(() -> ChatProgress.bind(id, () -> {
            ChatProgress.report("블로그 글에서 레시피를 옮겨 적고 있어요");
            return null;
        })).isInstanceOf(ChatCancellation.ChatCancelledException.class);
    }

    // 요청이 끝나 신호를 지운 뒤에도(구독이 이미 끊긴 뒤) 남은 작업 스레드는 멈춘 사실을 알아야 합니다.
    @Test
    void cancelledFlagSurvivesRequestCleanup() {
        String id = "agent-cancel-cleanup";
        ChatCancellation.register(id);
        ChatCancellation.cancel(id);
        ChatCancellation.clear(id);

        assertThat(ChatCancellation.isCancelled(id)).isTrue();
        assertThat(ChatCancellation.isCancelled("agent-never-cancelled")).isFalse();
    }

    // 멈춘 요청에서는 추출·요약 LLM(Ollama)을 부르지 않습니다.
    @Test
    void textLlmIsNotCalledForCancelledRequest() throws Exception {
        String id = "agent-cancel-llm";
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.error(new IllegalStateException("must not be called"));
                })
                .build();
        OllamaRecipeTextLlmAdapter adapter = new OllamaRecipeTextLlmAdapter(
                webClient, new ObjectMapper(), "http://localhost:11434/api/chat", "gemma3:4b", 30, 4096);
        ChatCancellation.register(id);
        ChatCancellation.cancel(id);

        assertThatThrownBy(() -> ChatProgress.bind(id, () -> adapter.complete("prompt", Map.of())))
                .isInstanceOf(ChatCancellation.ChatCancelledException.class);
        assertThat(calls).hasValue(0);
    }

    // 멈추지 않은 요청은 진행 ID가 없어도 평소처럼 동작합니다.
    @Test
    void requestsWithoutProgressIdAreNeverTreatedAsCancelled() {
        ChatCancellation.throwIfCurrentCancelled();
        ChatCancellation.throwIfCancelled(null);
    }
}
