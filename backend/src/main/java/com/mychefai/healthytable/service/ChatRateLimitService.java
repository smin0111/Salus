package com.mychefai.healthytable.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatRateLimitService {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final boolean enabled;
    private final int guestMaxRequestsPerMinute;
    private final int authenticatedMaxRequestsPerMinute;
    private final Clock clock;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public ChatRateLimitService(
            @Value("${app.chat-rate-limit.enabled:true}") boolean enabled,
            @Value("${app.chat-rate-limit.guest-max-requests-per-minute:10}") int guestMaxRequestsPerMinute,
            @Value("${app.chat-rate-limit.authenticated-max-requests-per-minute:60}") int authenticatedMaxRequestsPerMinute,
            Clock clock) {
        this.enabled = enabled;
        this.guestMaxRequestsPerMinute = Math.max(1, guestMaxRequestsPerMinute);
        this.authenticatedMaxRequestsPerMinute = Math.max(1, authenticatedMaxRequestsPerMinute);
        this.clock = clock;
    }

    public void checkAllowed(Optional<Long> authenticatedUserId, HttpServletRequest request) {
        if (!enabled) {
            return;
        }

        String key = resolveClientKey(authenticatedUserId, request);
        int limit = authenticatedUserId.isPresent()
                ? authenticatedMaxRequestsPerMinute
                : guestMaxRequestsPerMinute;
        long now = clock.millis();

        synchronized (counters) {
            if (counters.size() > MAX_TRACKED_CLIENTS) {
                pruneExpiredCounters(now);
            }

            WindowCounter counter = counters.compute(key, (ignored, current) -> {
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

    private String resolveClientKey(Optional<Long> authenticatedUserId, HttpServletRequest request) {
        if (authenticatedUserId.isPresent()) {
            return "user:" + authenticatedUserId.get();
        }
        return "guest:" + resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String firstIp = forwardedFor.split(",")[0].trim();
            if (!firstIp.isBlank() && firstIp.length() <= 64) {
                return firstIp;
            }
        }

        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "unknown";
        }
        return remoteAddr.length() <= 64 ? remoteAddr : "unknown";
    }

    private void pruneExpiredCounters(long now) {
        Iterator<Map.Entry<String, WindowCounter>> iterator = counters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, WindowCounter> entry = iterator.next();
            if (now - entry.getValue().windowStartedAtMillis >= WINDOW_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static class WindowCounter {
        private final long windowStartedAtMillis;
        private int requestCount;

        private WindowCounter(long windowStartedAtMillis, int requestCount) {
            this.windowStartedAtMillis = windowStartedAtMillis;
            this.requestCount = requestCount;
        }
    }
}
