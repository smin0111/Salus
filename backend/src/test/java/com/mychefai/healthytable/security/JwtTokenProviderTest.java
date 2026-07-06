package com.mychefai.healthytable.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    @Test
    void createsAndValidatesTokenWithStrongSecret() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "secretKey", "local-test-secret-must-be-longer-than-32-bytes");

        provider.validateSecretOnStartup();

        String token = provider.createToken("42");

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUserId(token)).isEqualTo("42");
    }

    @Test
    void rejectsShortSecretOnStartup() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "secretKey", "too-short");

        assertThatThrownBy(provider::validateSecretOnStartup)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최소 32바이트");
    }

    @Test
    void rejectsExampleSecretOnStartup() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "secretKey", "replace-with-at-least-32-byte-local-secret");

        assertThatThrownBy(provider::validateSecretOnStartup)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("예시값");
    }
}
