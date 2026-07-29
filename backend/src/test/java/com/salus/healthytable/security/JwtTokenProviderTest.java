package com.salus.healthytable.security;

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

        // 짧은 Secret은 토큰 서명을 추측하기 쉬워 인증 체계 전체를 약하게 만듭니다.
        // 서버 시작 시점에 실패시켜 잘못된 설정이 운영까지 올라가지 않게 합니다.
        assertThatThrownBy(provider::validateSecretOnStartup)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최소 32바이트");
    }

    @Test
    void rejectsExampleSecretOnStartup() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "secretKey", "replace-with-at-least-32-byte-local-secret");

        // 예시 Secret은 길어 보여도 공개 문서나 저장소에 노출된 값일 가능성이 높습니다.
        // 길이뿐 아니라 placeholder 패턴까지 막아야 실수 배포를 줄일 수 있습니다.
        assertThatThrownBy(provider::validateSecretOnStartup)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("예시값");
    }
}
