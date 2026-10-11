package com.salus.healthytable.service;

import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * OIDC 제공자(Apple, Google)의 공개키 목록(JWKS)을 받아 kid별 RSA 공개키로 캐시합니다.
 *
 * 24시간 캐시하고, 모르는 kid가 오면(제공자가 키를 교체한 직후) 1분에 한 번까지만 다시 받아옵니다.
 * 키를 받지 못하거나 해당 kid가 없으면 검증을 실패시킵니다(fail closed).
 */
public class JwksKeyCache {

    private static final Duration KEY_CACHE_TTL = Duration.ofHours(24);
    private static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(1);

    private final WebClient.Builder webClientBuilder;
    private final Clock clock;
    private final String keysUrl;

    private volatile Map<String, PublicKey> cachedKeys = Map.of();
    private volatile Instant keysFetchedAt = Instant.EPOCH;

    public JwksKeyCache(WebClient.Builder webClientBuilder, Clock clock, String keysUrl) {
        this.webClientBuilder = webClientBuilder;
        this.clock = clock;
        this.keysUrl = keysUrl;
    }

    public PublicKey findKey(String keyId) {
        if (keyId == null) {
            throw new OAuthVerificationException("key_id_missing");
        }
        Instant now = clock.instant();
        PublicKey key = cachedKeys.get(keyId);
        boolean expired = keysFetchedAt.plus(KEY_CACHE_TTL).isBefore(now);
        if (key != null && !expired) {
            return key;
        }
        if (expired || keysFetchedAt.plus(MIN_REFRESH_INTERVAL).isBefore(now)) {
            refresh(now);
            key = cachedKeys.get(keyId);
        }
        if (key == null) {
            throw new OAuthVerificationException("unknown_key_id");
        }
        return key;
    }

    @SuppressWarnings("unchecked")
    private synchronized void refresh(Instant now) {
        Map<String, Object> body = webClientBuilder.build()
                .get()
                .uri(keysUrl)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        Object keys = body != null ? body.get("keys") : null;
        if (!(keys instanceof Collection<?> list)) {
            throw new OAuthVerificationException("signing_keys_unavailable");
        }

        Map<String, PublicKey> parsed = new HashMap<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> jwk && "RSA".equals(jwk.get("kty")) && jwk.get("kid") instanceof String kid) {
                parsed.put(kid, toRsaPublicKey((Map<String, Object>) jwk));
            }
        }
        if (parsed.isEmpty()) {
            throw new OAuthVerificationException("signing_keys_unavailable");
        }
        cachedKeys = Map.copyOf(parsed);
        keysFetchedAt = now;
    }

    private PublicKey toRsaPublicKey(Map<String, Object> jwk) {
        try {
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(String.valueOf(jwk.get("n"))));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(String.valueOf(jwk.get("e"))));
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new OAuthVerificationException("signing_keys_unavailable");
        }
    }
}
