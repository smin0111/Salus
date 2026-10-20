package com.salus.healthytable.service.adminauth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 관리자 로그인 API의 접속 주소(IP)별 시도 횟수 제한입니다. 15분 고정 윈도우에 30회까지 허용합니다.
 *
 * 계정 잠금(5회 실패 시 15분)은 한 계정을 노리는 공격을, 이 제한은 한 곳에서 여러 계정을 두드리는 공격을 막습니다.
 * 카운터는 서버 메모리에만 있어 여러 인스턴스로 늘리면 인스턴스마다 따로 셉니다(ChatRateLimitService와 같은 방식).
 * 프록시 뒤에서는 프록시 주소로 합쳐 세므로, 실제 IP 기준 제한은 신뢰할 수 있는 프록시 설정이 있어야 합니다.
 */
@Component
public class AdminLoginRateLimiter {

    static final int MAX_ATTEMPTS = 30;
    static final long WINDOW_MILLIS = 15 * 60 * 1000L;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final Clock clock;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    public AdminLoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 시도 1회를 기록하고, 한도를 넘었으면 429 예외를 던집니다.
     */
    public void recordAttempt(String clientKey) {
        long now = clock.millis();
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.entrySet().removeIf(entry -> now - entry.getValue()[0] >= WINDOW_MILLIS);
        }
        // [윈도우 시작 시각, 시도 횟수]
        long[] window = windows.compute(clientKey == null ? "unknown" : clientKey, (key, current) -> {
            if (current == null || now - current[0] >= WINDOW_MILLIS) {
                return new long[] {now, 1};
            }
            current[1]++;
            return current;
        });
        if (window[1] > MAX_ATTEMPTS) {
            throw AdminAuthException.locked();
        }
    }
}
