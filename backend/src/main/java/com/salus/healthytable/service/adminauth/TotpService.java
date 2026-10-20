package com.salus.healthytable.service.adminauth;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * TOTP(RFC 6238) 2단계 인증 코드를 만들고 검증합니다. 인증 앱(Google Authenticator, 1Password 등)과 호환됩니다.
 *
 * - HMAC-SHA1, 30초 간격, 6자리(인증 앱 기본값)
 * - 기기 시계 오차를 고려해 앞뒤 한 구간(±30초)까지 허용합니다.
 * - 같은 코드를 두 번 쓰지 못하도록, 마지막으로 사용한 구간보다 뒤의 구간만 인정합니다.
 */
@Component
public class TotpService {

    static final int PERIOD_SECONDS = 30;
    static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20;
    private static final int ALLOWED_DRIFT_STEPS = 1;
    private static final String ISSUER = "Salus Admin";
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private final SecureRandom secureRandom = new SecureRandom();

    public byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secret);
        return secret;
    }

    /**
     * 코드가 맞으면 사용한 시간 구간을 돌려줍니다. 호출한 쪽은 이 값을 저장해 재사용을 막아야 합니다.
     */
    public OptionalLong verify(byte[] secret, String code, Instant now, Long lastUsedStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long currentStep = now.getEpochSecond() / PERIOD_SECONDS;
        long minimumStep = lastUsedStep == null ? Long.MIN_VALUE : lastUsedStep + 1;
        byte[] expected = code.getBytes(StandardCharsets.US_ASCII);

        OptionalLong matched = OptionalLong.empty();
        for (long step = currentStep - ALLOWED_DRIFT_STEPS; step <= currentStep + ALLOWED_DRIFT_STEPS; step++) {
            byte[] candidate = generate(secret, step).getBytes(StandardCharsets.US_ASCII);
            // 비교 시간으로 코드 정보가 새지 않도록 상수 시간 비교를 쓰고, 일치해도 반복을 끝까지 돕니다.
            if (MessageDigest.isEqual(candidate, expected) && step >= minimumStep && matched.isEmpty()) {
                matched = OptionalLong.of(step);
            }
        }
        return matched;
    }

    // RFC 4226 HOTP: HMAC-SHA1(secret, step) → dynamic truncation → 10^DIGITS로 나눈 나머지
    String generate(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1을 사용할 수 없습니다.", e);
        }
    }

    /**
     * 인증 앱 등록용 otpauth URI입니다(QR 코드로 보여 줍니다).
     */
    public String otpauthUri(String username, byte[] secret) {
        String label = encode(ISSUER + ":" + username);
        return "otpauth://totp/" + label
                + "?secret=" + base32(secret)
                + "&issuer=" + encode(ISSUER)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    // 인증 앱에 직접 입력할 때 쓰는 Base32 문자열(RFC 4648, 패딩 없음)
    public String base32(byte[] data) {
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                result.append(BASE32[(buffer >> (bitsLeft - 5)) & 0x1f]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            result.append(BASE32[(buffer << (5 - bitsLeft)) & 0x1f]);
        }
        return result.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
