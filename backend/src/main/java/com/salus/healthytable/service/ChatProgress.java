package com.salus.healthytable.service;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채팅 답변이 지금 어느 단계에 있는지 기록합니다. 앱은 기다리는 동안 이 값을 물어 "웹 검색 중", "레시피 검증 중"처럼
 * 실제 진행 단계와 경과 시간을 보여 줍니다(시간만 보고 문구를 바꾸면 저장된 답이 바로 나와도 "웹 검색 중"이 뜹니다).
 *
 * <p>진행 ID는 요청 ID(X-Request-Id)입니다. 리액티브 체인에서는 Reactor Context({@link #CONTEXT_KEY})로,
 * 한 스레드에서 끝까지 도는 Recipe Agent에서는 {@link #bind}로 묶은 스레드 값으로 전달합니다.
 * 단계 문구만 담고 사용자 정보는 담지 않습니다. 서버 한 대의 메모리에만 두며 10분이 지나면 지웁니다.
 */
@Slf4j
public final class ChatProgress {

    public static final String CONTEXT_KEY = "chatProgressId";

    private static final long RETENTION_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_ENTRIES = 2000;
    private static final Map<String, Snapshot> PROGRESS = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static Clock clock = Clock.systemUTC();

    private ChatProgress() {
    }

    public record Snapshot(String stage, long startedAtMillis, long updatedAtMillis, boolean done) {
    }

    public static void start(String progressId, String stage) {
        if (progressId == null || progressId.isBlank()) {
            return;
        }
        evictExpired();
        long now = clock.millis();
        PROGRESS.put(progressId, new Snapshot(stage, now, now, false));
    }

    // 지정한 진행 ID의 단계를 바꿉니다. 시작하지 않았거나 끝난 진행은 바꾸지 않습니다.
    public static void report(String progressId, String stage) {
        if (progressId == null || progressId.isBlank() || stage == null) {
            return;
        }
        PROGRESS.computeIfPresent(progressId, (id, current) -> {
            if (current.done()) {
                return current;
            }
            long now = clock.millis();
            // 어느 단계가 오래 걸리는지 보기 위해 이전 단계가 끝난 시점(요청 시작 기준)을 남깁니다.
            log.info("[ChatProgress] id={} done='{}' at={}ms next='{}'",
                    id, current.stage(), now - current.startedAtMillis(), stage);
            return new Snapshot(stage, current.startedAtMillis(), now, false);
        });
    }

    // 현재 스레드에 묶인 진행 ID의 단계를 바꿉니다(Recipe Agent 내부 단계용).
    public static void report(String stage) {
        report(CURRENT.get(), stage);
    }

    public static void finish(String progressId) {
        if (progressId == null || progressId.isBlank()) {
            return;
        }
        PROGRESS.computeIfPresent(progressId, (id, current) -> {
            long now = clock.millis();
            log.info("[ChatProgress] id={} done='{}' at={}ms finished", id, current.stage(), now - current.startedAtMillis());
            return new Snapshot(current.stage(), current.startedAtMillis(), now, true);
        });
    }

    // 리액티브 단계가 구독될 때 Reactor Context의 진행 ID로 단계를 기록합니다.
    public static <T> Mono<T> stage(String stage, Mono<T> next) {
        return Mono.deferContextual(context -> {
            report(context.getOrDefault(CONTEXT_KEY, null), stage);
            return next;
        });
    }

    public static Optional<Snapshot> get(String progressId) {
        return progressId == null ? Optional.empty() : Optional.ofNullable(PROGRESS.get(progressId));
    }

    // 작업이 끝날 때까지 현재 스레드에 진행 ID를 묶습니다. 스레드 풀 재사용에 대비해 끝나면 반드시 풉니다.
    public static <T> T bind(String progressId, Callable<T> work) throws Exception {
        String previous = CURRENT.get();
        CURRENT.set(progressId);
        try {
            return work.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    private static void evictExpired() {
        long cutoff = clock.millis() - RETENTION_MILLIS;
        PROGRESS.entrySet().removeIf(entry -> entry.getValue().updatedAtMillis() < cutoff);
        if (PROGRESS.size() > MAX_ENTRIES) {
            PROGRESS.clear();
        }
    }

    // 테스트에서 시간을 고정할 때만 씁니다.
    static void useClock(Clock testClock) {
        clock = testClock;
    }

    static void clear() {
        PROGRESS.clear();
    }
}
