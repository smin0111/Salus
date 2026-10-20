package com.salus.healthytable.service.adminauth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TotpService} 테스트입니다. RFC 6238 부록 B의 SHA-1 테스트 벡터로 코드 생성이 표준과 같은지 확인합니다.
 */
class TotpServiceTest {

    // RFC 6238 테스트용 비밀키(ASCII "12345678901234567890")
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private final TotpService totp = new TotpService();

    // RFC의 8자리 값에서 6자리(인증 앱 기본값)는 마지막 6자리와 같아야 합니다.
    @Test
    void generatesRfc6238Sha1Vectors() {
        assertThat(totp.generate(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(totp.generate(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(totp.generate(RFC_SECRET, 1111111111L / 30)).isEqualTo("050471");
        assertThat(totp.generate(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(totp.generate(RFC_SECRET, 2000000000L / 30)).isEqualTo("279037");
    }

    // 기기 시계 오차를 위해 앞뒤 30초까지는 허용하고, 그보다 벌어지면 거부해야 합니다.
    @Test
    void acceptsOneStepDriftOnly() {
        Instant now = Instant.ofEpochSecond(1234567890L);
        long step = now.getEpochSecond() / 30;

        assertThat(totp.verify(RFC_SECRET, totp.generate(RFC_SECRET, step), now, null)).hasValue(step);
        assertThat(totp.verify(RFC_SECRET, totp.generate(RFC_SECRET, step - 1), now, null)).hasValue(step - 1);
        assertThat(totp.verify(RFC_SECRET, totp.generate(RFC_SECRET, step + 1), now, null)).hasValue(step + 1);
        assertThat(totp.verify(RFC_SECRET, totp.generate(RFC_SECRET, step - 2), now, null)).isEmpty();
        assertThat(totp.verify(RFC_SECRET, totp.generate(RFC_SECRET, step + 2), now, null)).isEmpty();
    }

    // 한 번 쓴 코드(같은 구간이나 이전 구간)는 다시 받지 않아야 합니다.
    @Test
    void rejectsReusedCode() {
        Instant now = Instant.ofEpochSecond(1234567890L);
        long step = now.getEpochSecond() / 30;
        String code = totp.generate(RFC_SECRET, step);

        assertThat(totp.verify(RFC_SECRET, code, now, step)).isEmpty();
        assertThat(totp.verify(RFC_SECRET, totp.generate(RFC_SECRET, step - 1), now, step)).isEmpty();
        assertThat(totp.verify(RFC_SECRET, totp.generate(RFC_SECRET, step + 1), now, step)).hasValue(step + 1);
    }

    // 6자리 숫자가 아니면 검증하지 않아야 합니다.
    @Test
    void rejectsMalformedCode() {
        Instant now = Instant.ofEpochSecond(1234567890L);
        assertThat(totp.verify(RFC_SECRET, null, now, null)).isEmpty();
        assertThat(totp.verify(RFC_SECRET, "12345", now, null)).isEmpty();
        assertThat(totp.verify(RFC_SECRET, "12a456", now, null)).isEmpty();
    }

    // 인증 앱 등록 URI와 Base32(RFC 4648) 형식이 표준과 같아야 합니다.
    @Test
    void buildsBase32AndOtpauthUri() {
        assertThat(totp.base32(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(totp.otpauthUri("alice", RFC_SECRET)).isEqualTo(
                "otpauth://totp/Salus%20Admin%3Aalice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                        + "&issuer=Salus%20Admin&algorithm=SHA1&digits=6&period=30");
    }
}
