package com.salus.healthytable.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 월간 식단 총평의 LLM 호출을 아낍니다. 달력 화면을 열 때마다 요청이 오는데, 로컬 LLM은 채팅과 같은 자원을 씁니다.
 * - 같은 회원·같은 달·같은 기록(지문)이면 저장해 둔 총평을 그대로 줍니다(24시간). 실패 안내는 저장하지 않습니다.
 * - 새로 만드는 횟수는 회원마다 10분에 3번까지입니다(넘으면 429).
 * - 서버 전체에서 동시에 하나만 만듭니다(이미 만드는 중이면 503). 채팅 응답이 밀리지 않게 하려는 것입니다.
 * 서버 메모리에만 두므로 재시작하면 비워지고, 서버를 여러 대로 늘리면 대마다 따로 셉니다(지금 베타는 한 대).
 */
@Component
public class MonthlyAnalysisGuard {

    static final Duration CACHE_TTL = Duration.ofHours(24);
    static final Duration USER_WINDOW = Duration.ofMinutes(10);
    static final int USER_GENERATIONS_PER_WINDOW = 3;
    private static final int MAX_ENTRIES = 10_000;

    private record Entry(String fingerprint, String text, Instant createdAt) {
    }

    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<Long, Deque<Instant>> generations = new ConcurrentHashMap<>();
    private final Semaphore inFlight = new Semaphore(1);
    // 지운(탈퇴한) 회원 id. 회원 id는 다시 쓰이지 않으므로, 여기 있는 회원의 총평은 언제 만들어졌든 다시 저장하지 않습니다.
    // 탈퇴 직전에 식단을 읽어 둔 요청이 탈퇴 뒤에 끝나도 사본이 남지 않습니다(Codex 검토 3회차). 재시작하면 비워지지만 캐시도 함께 비워집니다.
    private final java.util.Set<Long> forgotten = ConcurrentHashMap.newKeySet();
    private final Object forgetLock = new Object();

    public MonthlyAnalysisGuard(Clock clock) {
        this.clock = clock;
    }

    public String getOrGenerate(Long userId, String month, String fingerprint, Supplier<String> generator) {
        Instant now = clock.instant();
        String key = userId + ":" + month;
        Entry cached = cache.get(key);
        if (cached != null && cached.fingerprint().equals(fingerprint) && cached.createdAt().plus(CACHE_TTL).isAfter(now)) {
            return cached.text();
        }
        // 서버 실행권을 먼저 받습니다. 다른 회원이 만드는 중이라 거절된 요청은 이 회원의 횟수를 쓰지 않습니다.
        if (!inFlight.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "다른 총평을 만드는 중이에요. 잠시 후 다시 시도해 주세요.");
        }
        try {
            if (!tryCountGeneration(userId, now)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "월간 총평을 너무 자주 새로 만들었어요. 잠시 후 다시 시도해 주세요.");
            }
            String text = generator.get();
            // 탈퇴한 회원이면 저장하지 않습니다. 확인과 저장을 forgetUser와 같은 잠금 안에서 해, 확인 직후 탈퇴가 끼어들지 못하게 합니다.
            if (!LlmService.isFailureReply(text)) {
                synchronized (forgetLock) {
                    if (!forgotten.contains(userId)) {
                        if (cache.size() >= MAX_ENTRIES) {
                            cache.clear();
                        }
                        cache.put(key, new Entry(fingerprint, text, now));
                    }
                }
            }
            return text;
        } finally {
            inFlight.release();
        }
    }

    /** 탈퇴 등으로 회원 데이터가 지워질 때 그 회원의 총평 사본도 지웁니다. */
    public void forgetUser(Long userId) {
        synchronized (forgetLock) {
            forgotten.add(userId);
            String prefix = userId + ":";
            cache.keySet().removeIf(key -> key.startsWith(prefix));
        }
        generations.remove(userId);
    }

    private boolean tryCountGeneration(Long userId, Instant now) {
        if (generations.size() >= MAX_ENTRIES && !generations.containsKey(userId)) {
            generations.clear();
        }
        Deque<Instant> times = generations.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && !times.peekFirst().plus(USER_WINDOW).isAfter(now)) {
                times.pollFirst();
            }
            if (times.size() >= USER_GENERATIONS_PER_WINDOW) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
