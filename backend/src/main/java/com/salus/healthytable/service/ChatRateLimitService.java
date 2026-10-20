package com.salus.healthytable.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 요청 횟수 제한(Rate Limit) 서비스입니다.
 *
 * 1분 단위 고정 윈도우(fixed window) 방식으로, 사용자(또는 게스트 IP)별 요청 수를 셉니다.
 * - 로그인 사용자: 기본 분당 60회 / 게스트: 기본 분당 10회
 * 카운터는 서버 메모리에만 저장되므로, 서버를 재시작하거나 여러 대로 늘리면 인스턴스마다 따로 계산됩니다.
 */
@Service
public class ChatRateLimitService {

    // 제한 구간 길이(1분)와, 메모리 폭증을 막기 위한 최대 추적 클라이언트 수
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final boolean enabled;
    private final int guestMaxRequestsPerMinute;
    private final int authenticatedMaxRequestsPerMinute;
    private final Clock clock;
    // X-Forwarded-For를 믿을 수 있는 바로 앞 프록시(nginx 등) 주소 범위
    private final List<IpAddressMatcher> trustedProxies;
    // key("user:1" 또는 "guest:1.2.3.4") → 현재 윈도우의 요청 수
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Autowired
    public ChatRateLimitService(
            @Value("${app.chat-rate-limit.enabled:true}") boolean enabled,
            @Value("${app.chat-rate-limit.guest-max-requests-per-minute:10}") int guestMaxRequestsPerMinute,
            @Value("${app.chat-rate-limit.authenticated-max-requests-per-minute:60}") int authenticatedMaxRequestsPerMinute,
            @Value("${app.client-ip.trusted-proxies:127.0.0.1/32,::1/128}") String trustedProxies,
            Clock clock) {
        this.enabled = enabled;
        // 설정값이 0 이하로 잘못 들어와도 최소 1회는 허용합니다.
        this.guestMaxRequestsPerMinute = Math.max(1, guestMaxRequestsPerMinute);
        this.authenticatedMaxRequestsPerMinute = Math.max(1, authenticatedMaxRequestsPerMinute);
        this.clock = clock;
        this.trustedProxies = parseTrustedProxies(trustedProxies);
    }

    // 테스트용: 믿을 프록시는 같은 컴퓨터(루프백)만입니다.
    public ChatRateLimitService(boolean enabled, int guestMaxRequestsPerMinute, int authenticatedMaxRequestsPerMinute, Clock clock) {
        this(enabled, guestMaxRequestsPerMinute, authenticatedMaxRequestsPerMinute, "127.0.0.1/32,::1/128", clock);
    }

    private static List<IpAddressMatcher> parseTrustedProxies(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(cidr -> !cidr.isBlank())
                .map(IpAddressMatcher::new)
                .toList();
    }

    /**
     * 요청을 허용할지 검사합니다. 한도를 넘으면 429 Too Many Requests 예외를 던집니다.
     */
    public void checkAllowed(Optional<Long> authenticatedUserId, HttpServletRequest request) {
        if (!enabled) {
            return;
        }

        String key = resolveClientKey(authenticatedUserId, request);
        int limit = authenticatedUserId.isPresent()
                ? authenticatedMaxRequestsPerMinute
                : guestMaxRequestsPerMinute;
        long now = clock.millis();

        // 여러 요청 스레드가 동시에 카운터를 바꾸므로, 확인과 증가를 한 덩어리로 묶어 경쟁 조건을 막습니다.
        synchronized (counters) {
            // 추적 대상이 너무 많아지면 이미 만료된 카운터부터 정리합니다.
            if (counters.size() > MAX_TRACKED_CLIENTS) {
                pruneExpiredCounters(now);
            }

            WindowCounter counter = counters.compute(key, (ignored, current) -> {
                // 처음 요청했거나 1분이 지났으면 새 윈도우를 시작하고, 아니면 요청 수를 1 늘립니다.
                if (current == null || now - current.windowStartedAtMillis >= WINDOW_MILLIS) {
                    return new WindowCounter(now, 1);
                }
                current.requestCount++;
                return current;
            });

            if (counter.requestCount > limit) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "AI 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
            }
        }
    }

    // 로그인 사용자는 사용자 ID 기준, 게스트는 IP 기준으로 제한합니다.
    public String resolveClientKey(Optional<Long> authenticatedUserId, HttpServletRequest request) {
        if (authenticatedUserId.isPresent()) {
            return "user:" + authenticatedUserId.get();
        }
        return "guest:" + resolveClientIp(request);
    }

    /**
     * 게스트 IP를 구합니다. 바로 앞 연결이 믿을 수 있는 프록시일 때만 X-Forwarded-For를 보고, 그 안에서도
     * 오른쪽(프록시가 붙인 쪽)부터 읽어 믿을 수 있는 프록시가 아닌 첫 주소를 씁니다.
     * 예전에는 첫 번째 값을 그대로 써서, 사용자가 헤더에 아무 IP나 적어 보내면 게스트 요청 제한을 피할 수 있었습니다
     * (2026-10-17 리뷰: 헤더만 바꾼 3회 요청이 분당 1회 제한을 모두 통과). 왼쪽 값은 사용자가 마음대로 적을 수 있습니다.
     */
    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = ipOrNull(request.getRemoteAddr());
        if (remote == null) {
            return "unknown";
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (!isTrustedProxy(remote) || forwardedFor == null || forwardedFor.isBlank()) {
            return remote;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = ipOrNull(hops[i]);
            if (hop == null) {
                // 주소가 아닌 값이 섞였으면 그 왼쪽은 믿지 않습니다.
                return remote;
            }
            if (!isTrustedProxy(hop)) {
                return hop;
            }
        }
        return remote;
    }

    private boolean isTrustedProxy(String ip) {
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(ip)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // IP 형식이 아니면 믿지 않습니다.
            }
        }
        return false;
    }

    // IP 주소처럼 생긴 값만 받습니다(숫자·16진수·점·콜론, 64자 이하).
    private static String ipOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return !trimmed.isEmpty() && trimmed.length() <= 64 && trimmed.matches("[0-9A-Fa-f:.]+") ? trimmed : null;
    }

    // 1분이 지난(만료된) 카운터를 맵에서 제거합니다.
    private void pruneExpiredCounters(long now) {
        Iterator<Map.Entry<String, WindowCounter>> iterator = counters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, WindowCounter> entry = iterator.next();
            if (now - entry.getValue().windowStartedAtMillis >= WINDOW_MILLIS) {
                iterator.remove();
            }
        }
    }

    // 윈도우 시작 시각과 그 안에서의 요청 수
    private static class WindowCounter {
        private final long windowStartedAtMillis;
        private int requestCount;

        private WindowCounter(long windowStartedAtMillis, int requestCount) {
            this.windowStartedAtMillis = windowStartedAtMillis;
            this.requestCount = requestCount;
        }
    }
}
