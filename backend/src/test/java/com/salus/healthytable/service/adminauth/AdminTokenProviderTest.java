package com.salus.healthytable.service.adminauth;

import com.salus.healthytable.security.AdminTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AdminTokenProvider} 테스트입니다.
 */
class AdminTokenProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");

    private AdminTokenProvider provider(Clock clock, String adminSecret, String userSecret) {
        AdminTokenProvider provider = new AdminTokenProvider(clock);
        ReflectionTestUtils.setField(provider, "adminSecret", adminSecret);
        ReflectionTestUtils.setField(provider, "userSecret", userSecret);
        return provider;
    }

    // challenge 토큰과 access 토큰은 서로의 자리에 쓸 수 없어야 합니다.
    @Test
    void tokenTypesAreNotInterchangeable() {
        AdminTokenProvider provider = provider(Clock.fixed(NOW, ZoneId.of("UTC")),
                "admin-test-secret-must-be-longer-than-32-bytes", "user-test-secret-must-be-longer-than-32-bytes");

        String challenge = provider.createChallenge(1L, "TOTP");
        String access = provider.createAccess(1L, "sid", NOW.plusSeconds(3600));

        assertThat(provider.parseChallenge(challenge)).isPresent();
        assertThat(provider.parseAccess(access)).isPresent();
        assertThat(provider.parseAccess(challenge)).isEmpty();
        assertThat(provider.parseChallenge(access)).isEmpty();
    }

    // challenge 토큰은 5분 뒤 만료되어야 합니다.
    @Test
    void challengeExpiresAfterFiveMinutes() {
        String challenge = provider(Clock.fixed(NOW, ZoneId.of("UTC")),
                "admin-test-secret-must-be-longer-than-32-bytes", "user-secret-x").createChallenge(1L, "TOTP");

        AdminTokenProvider later = provider(Clock.fixed(NOW.plusSeconds(301), ZoneId.of("UTC")),
                "admin-test-secret-must-be-longer-than-32-bytes", "user-secret-x");

        assertThat(later.parseChallenge(challenge)).isEmpty();
    }

    // 관리자 키가 없거나 짧거나 사용자 JWT 키와 같으면 발급 자체를 거부해야 합니다.
    @Test
    void rejectsMissingWeakOrSharedSecret() {
        Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));
        String shared = "shared-secret-that-is-longer-than-32-bytes";

        assertThatThrownBy(() -> provider(clock, "", "x").createChallenge(1L, "TOTP"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider(clock, "too-short", "x").createChallenge(1L, "TOTP"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider(clock, shared, shared).createChallenge(1L, "TOTP"))
                .isInstanceOf(IllegalStateException.class);
    }
}
