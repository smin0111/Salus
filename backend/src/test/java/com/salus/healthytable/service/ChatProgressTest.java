package com.salus.healthytable.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채팅 진행 단계 기록({@link ChatProgress})이 리액티브 경로와 작업 스레드 경로 모두에서 같은 진행 ID로 남는지 확인합니다.
 */
class ChatProgressTest {

    @AfterEach
    void tearDown() {
        ChatProgress.clear();
    }

    // 시작 → 단계 변경 → 종료 순서로 기록되고, 끝난 진행은 더 바뀌지 않습니다.
    @Test
    void recordsStagesUntilFinished() {
        ChatProgress.start("req-1", "답변을 준비하고 있어요");
        ChatProgress.report("req-1", "웹에서 레시피를 검색하고 있어요");

        assertThat(ChatProgress.get("req-1")).get()
                .extracting(ChatProgress.Snapshot::stage).isEqualTo("웹에서 레시피를 검색하고 있어요");

        ChatProgress.finish("req-1");
        ChatProgress.report("req-1", "늦게 도착한 단계");

        assertThat(ChatProgress.get("req-1")).get().satisfies(snapshot -> {
            assertThat(snapshot.done()).isTrue();
            assertThat(snapshot.stage()).isEqualTo("웹에서 레시피를 검색하고 있어요");
        });
    }

    // 리액티브 체인에서는 Reactor Context의 진행 ID로 단계가 기록됩니다(스레드가 바뀌어도).
    @Test
    void reactiveStageUsesContextIdAcrossThreads() {
        ChatProgress.start("req-2", "답변을 준비하고 있어요");

        ChatProgress.stage("레시피를 작성하고 있어요", Mono.just("ok").publishOn(Schedulers.parallel()))
                .contextWrite(context -> context.put(ChatProgress.CONTEXT_KEY, "req-2"))
                .block();

        assertThat(ChatProgress.get("req-2")).get()
                .extracting(ChatProgress.Snapshot::stage).isEqualTo("레시피를 작성하고 있어요");
    }

    // 작업 스레드에 묶은 진행 ID로 내부 단계가 기록되고, 작업이 끝나면 스레드에서 풀립니다.
    @Test
    void boundThreadReportsAndUnbindsAfterWork() throws Exception {
        ChatProgress.start("req-3", "답변을 준비하고 있어요");

        ChatProgress.bind("req-3", () -> {
            ChatProgress.report("조리 순서를 보기 쉽게 정리하고 있어요");
            return null;
        });
        ChatProgress.report("묶이지 않은 스레드의 단계");

        assertThat(ChatProgress.get("req-3")).get()
                .extracting(ChatProgress.Snapshot::stage).isEqualTo("조리 순서를 보기 쉽게 정리하고 있어요");
    }

    // 진행 ID가 없거나 시작하지 않은 진행은 무시합니다.
    @Test
    void ignoresMissingOrUnknownIds() {
        ChatProgress.report(null, "단계");
        ChatProgress.report("unknown", "단계");

        assertThat(ChatProgress.get("unknown")).isEmpty();
        assertThat(ChatProgress.get(null)).isEmpty();
    }
}
