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
    private static final Duration EXPIRED_RETRY_INTERVAL = Duration.ofSeconds(10);

    private final WebClient.Builder webClientBuilder;
    private final Clock clock;
    private final String keysUrl;

    private volatile Map<String, PublicKey> cachedKeys = Map.of();
    private volatile Instant keysFetchedAt = Instant.EPOCH;
    // 마지막으로 키를 받으려 한 시각(성공·실패 모두). 위조 토큰을 동시에 보내 갱신을 겹쳐 부르는 것을 막습니다.
    private Instant lastRefreshAttemptAt = Instant.EPOCH;

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
        // 갱신을 건너뛰었거나(재시도 간격) 실패해 캐시가 아직 만료 상태면, 오래된 키로 검증하지 않고 실패시킵니다(fail closed).
        if (keysFetchedAt.plus(KEY_CACHE_TTL).isBefore(now)) {
            throw new OAuthVerificationException("signing_keys_unavailable");
        }
        if (key == null) {
            throw new OAuthVerificationException("unknown_key_id");
        }
        return key;
    }

    @SuppressWarnings("unchecked")
    private synchronized void refresh(Instant now) {
        // 잠금을 기다리는 동안 다른 요청이 이미 받아 왔거나 방금 시도했으면 다시 부르지 않습니다(2026-11-06 보안 점검).
        // 캐시가 만료되기 전에는 성공·실패와 상관없이 1분에 한 번만 외부를 부릅니다.
        // 캐시가 비었거나 만료됐을 때도 실패가 이어지면 매 요청이 외부를 부르지 않게 10초 간격을 둡니다.
        boolean expired = keysFetchedAt.plus(KEY_CACHE_TTL).isBefore(now);
        Duration interval = expired ? EXPIRED_RETRY_INTERVAL : MIN_REFRESH_INTERVAL;
        if (lastRefreshAttemptAt.plus(interval).isAfter(now)) {
            return;
        }
        lastRefreshAttemptAt = now;
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
