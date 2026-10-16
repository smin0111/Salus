package com.salus.healthytable.service;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자가 답을 기다리다 "멈춤"을 누른 채팅 요청을 끊습니다.
 *
 * 요청 ID(X-Request-Id, 진행 ID와 같은 값)마다 중단 신호를 하나 둡니다. 신호가 오면 채팅 처리 Mono의 구독이 끊기고,
 * 그 아래의 Ollama 호출(WebClient)도 연결을 닫아 생성을 멈춥니다. Ollama는 한 번에 한 요청만 처리하므로
 * 화면에서만 멈추면 버려진 답을 계속 만들고 다음 질문이 그 뒤에서 기다립니다.
 *
 * <p>Recipe Agent는 한 스레드에서 블로킹으로 도는 코드라 구독을 끊어도 웹 페이지를 읽는 중이면 멈추지 않고 다음 단계
 * (LLM 추출)로 넘어갔습니다(2026-10-15 참치마요덮밥: 멈춘 뒤 25초 동안 추출이 돌아 다음 질문이 기다림).
 * 그래서 멈춘 요청 ID를 따로 기억해 두고, 단계가 바뀔 때·페이지를 읽기 전·LLM을 부르기 전에 {@link #throwIfCancelled}로 확인합니다.
 */
public final class ChatCancellation {

    private static final long RETENTION_MILLIS = 10 * 60 * 1000L;
    private static final Map<String, Sinks.One<Boolean>> SIGNALS = new ConcurrentHashMap<>();
    // 멈춘 요청 ID와 멈춘 시각. 요청이 끝나도(구독이 끊겨도) 아직 돌고 있는 작업 스레드가 확인할 수 있게 10분 동안 둡니다.
    private static final Map<String, Long> CANCELLED = new ConcurrentHashMap<>();

    private ChatCancellation() {
    }

    /** 멈춘 요청의 작업이 다음 단계로 넘어가지 않게 던지는 예외입니다. 오류가 아니라 정상적인 중단입니다. */
    public static class ChatCancelledException extends RuntimeException {
        public ChatCancelledException() {
            super("chat request cancelled by user", null, false, false);
        }
    }

    /** 요청 처리를 시작할 때 중단 신호를 만들고, 신호가 오면 값을 내는 Mono를 돌려줍니다(takeUntilOther용). */
    public static Mono<Boolean> register(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Mono.never();
        }
        return SIGNALS.computeIfAbsent(requestId, id -> Sinks.one()).asMono();
    }

    /** 진행 중인 요청을 멈춥니다. 이미 끝났거나 없는 요청이면 false입니다. */
    public static boolean cancel(String requestId) {
        if (requestId == null) {
            return false;
        }
        Sinks.One<Boolean> signal = SIGNALS.remove(requestId);
        if (signal == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        CANCELLED.values().removeIf(cancelledAt -> cancelledAt < now - RETENTION_MILLIS);
        CANCELLED.put(requestId, now);
        return signal.tryEmitValue(Boolean.TRUE).isSuccess();
    }

    /** 요청이 끝나면 신호를 지웁니다. 멈춘 기록은 남은 작업 스레드가 확인할 수 있게 지우지 않습니다. */
    public static void clear(String requestId) {
        if (requestId != null) {
            SIGNALS.remove(requestId);
        }
    }

    public static boolean isCancelled(String requestId) {
        return requestId != null && CANCELLED.containsKey(requestId);
    }

    /** 이 요청이 멈췄으면 작업을 그만두게 예외를 던집니다. */
    public static void throwIfCancelled(String requestId) {
        if (isCancelled(requestId)) {
            throw new ChatCancelledException();
        }
    }

    /** 현재 스레드에 묶인 요청(Recipe Agent 작업)이 멈췄으면 예외를 던집니다. */
    public static void throwIfCurrentCancelled() {
        throwIfCancelled(ChatProgress.currentId());
    }

    static boolean isRegistered(String requestId) {
        return requestId != null && SIGNALS.containsKey(requestId);
    }
}
