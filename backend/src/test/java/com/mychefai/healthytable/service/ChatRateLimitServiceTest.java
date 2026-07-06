package com.mychefai.healthytable.service;

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

class ChatRateLimitServiceTest {

    private final MutableClock clock = new MutableClock();

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

    @Test
    void guestLimitResetsAfterOneMinuteWindow() {
        ChatRateLimitService service = new ChatRateLimitService(true, 1, 60, clock);
        MockHttpServletRequest request = requestFrom("203.0.113.11");

        service.checkAllowed(Optional.empty(), request);
        clock.advanceMillis(60_000L);

        service.checkAllowed(Optional.empty(), request);
    }

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
