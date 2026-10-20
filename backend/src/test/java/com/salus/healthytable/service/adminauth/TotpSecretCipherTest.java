package com.salus.healthytable.service.adminauth;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TotpSecretCipher} 테스트입니다.
 */
class TotpSecretCipherTest {

    static TotpSecretCipher cipherWithKey(byte fill) {
        TotpSecretCipher cipher = new TotpSecretCipher();
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        ReflectionTestUtils.setField(cipher, "encodedKey", Base64.getEncoder().encodeToString(key));
        return cipher;
    }

    // 암호화한 값은 원래 비밀키로 복호화되고, 같은 값도 매번 다른 암호문이 되어야 합니다(IV 무작위).
    @Test
    void roundTripsWithRandomIv() {
        TotpSecretCipher cipher = cipherWithKey((byte) 1);
        byte[] secret = "secret-bytes-20-long".getBytes();

        String first = cipher.encrypt(secret);
        String second = cipher.encrypt(secret);

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(secret);
    }

    // 다른 키나 변조된 값은 복호화하지 않아야 합니다(GCM 인증 실패).
    @Test
    void rejectsWrongKeyOrTamperedValue() {
        String stored = cipherWithKey((byte) 1).encrypt("secret".getBytes());
        byte[] payload = Base64.getDecoder().decode(stored.substring(3));
        payload[payload.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(payload);

        assertThatThrownBy(() -> cipherWithKey((byte) 2).decrypt(stored)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipherWithKey((byte) 1).decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    // 키 설정이 없거나 길이가 틀리면 암호화 자체를 거부해야 합니다.
    @Test
    void failsClosedWithoutValidKey() {
        TotpSecretCipher missing = new TotpSecretCipher();
        ReflectionTestUtils.setField(missing, "encodedKey", "");
        TotpSecretCipher shortKey = new TotpSecretCipher();
        ReflectionTestUtils.setField(shortKey, "encodedKey", Base64.getEncoder().encodeToString(new byte[16]));

        assertThatThrownBy(() -> missing.encrypt(new byte[20])).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> shortKey.encrypt(new byte[20])).isInstanceOf(IllegalStateException.class);
    }
}
