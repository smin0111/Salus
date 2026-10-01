package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChatRateLimitService} 테스트입니다. 조작 가능한 가짜 Clock으로 1분 윈도우를 검증합니다.
 */
class ChatRateLimitServiceTest {

    private final MutableClock clock = new MutableClock();

    // 게스트는 IP 기준으로 분당 요청 수가 제한되고, 초과하면 429 예외가 나야 합니다.
    @Test
    void guestRequestsAreLimitedByClientIpPerMinute() {
        ChatRateLimitService service = new ChatRateLimitService(true, 2, 60, clock);
        MockHttpServletRequest request = requestFrom("203.0.113.10");

        service.checkAllowed(Optional.empty(), request);
        service.checkAllowed(Optional.empty(), request);

        assertThatThrownBy(() -> service.checkAllowed(Optional.empty(), request))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(ex.getReason()).isEqualTo("AI 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
                });
    }

    // 1분이 지나면 게스트 제한이 초기화되어야 합니다.
    @Test
    void guestLimitResetsAfterOneMinuteWindow() {
        ChatRateLimitService service = new ChatRateLimitService(true, 1, 60, clock);
        MockHttpServletRequest request = requestFrom("203.0.113.11");

        service.checkAllowed(Optional.empty(), request);
        clock.advanceMillis(60_000L);

        service.checkAllowed(Optional.empty(), request);
    }

    // 로그인 사용자는 같은 IP를 공유해도 사용자 ID 기준으로 따로 제한되어야 합니다.
    @Test
    void authenticatedRequestsAreLimitedByUserIdNotSharedIp() {
        ChatRateLimitService service = new ChatRateLimitService(true, 1, 1, clock);
        MockHttpServletRequest request = requestFrom("203.0.113.12");

        service.checkAllowed(Optional.of(1L), request);
        service.checkAllowed(Optional.of(2L), request);

        assertThatThrownBy(() -> service.checkAllowed(Optional.of(1L), request))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    // 제한 기능이 꺼져 있으면 반복 요청을 모두 허용해야 합니다.
    @Test
    void disabledLimiterAllowsRepeatedRequests() {
        ChatRateLimitService service = new ChatRateLimitService(false, 1, 1, clock);
        MockHttpServletRequest request = requestFrom("203.0.113.13");

        service.checkAllowed(Optional.empty(), request);
        service.checkAllowed(Optional.empty(), request);
        service.checkAllowed(Optional.of(1L), request);
        service.checkAllowed(Optional.of(1L), request);
    }

    private MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-04T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }
    }
}
