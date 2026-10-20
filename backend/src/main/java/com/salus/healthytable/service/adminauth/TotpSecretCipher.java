package com.salus.healthytable.service.adminauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 관리자 TOTP 비밀키를 DB에 저장하기 전에 AES-256-GCM으로 암호화합니다.
 * DB만 유출돼서는 2단계 인증 코드를 만들 수 없게 하기 위해서입니다.
 *
 * 저장 형식: "v1:" + Base64(IV 12바이트 + 암호문·인증태그)
 * 암호화 키(admin.totp.encryption-key)가 없거나 잘못되면 암호화·복호화를 모두 거부합니다(fail closed).
 */
@Component
public class TotpSecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    // Base64로 인코딩한 32바이트 키. 예: openssl rand -base64 32
    @Value("${admin.totp.encryption-key:}")
    private String encodedKey;

    public String encrypt(byte[] secret) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(secret);
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP 비밀키를 암호화하지 못했습니다.", e);
        }
    }

    public byte[] decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("저장된 TOTP 비밀키 형식이 올바르지 않습니다.");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            return cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 키가 바뀌었거나 값이 변조되면 GCM 인증이 실패합니다. 이 경우 로그인을 허용하지 않습니다.
            throw new IllegalStateException("TOTP 비밀키를 복호화하지 못했습니다.", e);
        }
    }

    private SecretKeySpec key() {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalStateException("ADMIN_TOTP_ENCRYPTION_KEY 설정이 비어 있습니다.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ADMIN_TOTP_ENCRYPTION_KEY는 Base64 형식이어야 합니다.");
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException("ADMIN_TOTP_ENCRYPTION_KEY는 32바이트(AES-256)여야 합니다.");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
