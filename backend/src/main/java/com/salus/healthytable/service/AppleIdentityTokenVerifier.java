package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SigningKeyResolverAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sign in with Apple의 identity token(JWT)을 검증합니다.
 *
 * 검증 항목: Apple 공개키(JWKS) RS256 서명, iss, aud(Salus 번들 ID/Services ID), 만료, nonce.
 * nonce는 클라이언트가 만든 원문의 SHA-256 값을 Apple에 넘기고 원문을 서버로 보내는 방식입니다.
 * 토큰만 탈취해서는 원문 nonce를 모르므로 다른 요청에 재사용하기 어렵습니다.
 */
@Component
@RequiredArgsConstructor
public class AppleIdentityTokenVerifier {

    static final String APPLE_ISSUER = "https://appleid.apple.com";
    private static final String APPLE_KEYS_URL = "https://appleid.apple.com/auth/keys";
    private static final Duration KEY_CACHE_TTL = Duration.ofHours(24);
    // 모르는 kid가 들어올 때마다 Apple을 호출하지 않도록 재조회 간격을 둡니다.
    private static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(1);
    private static final long CLOCK_SKEW_SECONDS = 60;
    private static final int MAX_NAME_LENGTH = 100;

    private final WebClient.Builder webClientBuilder;
    private final Clock clock;

    // 쉼표로 구분한 aud 허용 목록(iOS 번들 ID, 웹용 Services ID)
    @Value("${oauth.apple.client-ids:}")
    private String appleClientIds;

    private volatile Map<String, PublicKey> cachedKeys = Map.of();
    private volatile Instant keysFetchedAt = Instant.EPOCH;

    /**
     * identity token을 검증하고 로그인에 쓸 사용자 정보를 만듭니다.
     * Apple은 이름을 토큰에 넣지 않고 첫 로그인 때만 앱에 주므로, 이름은 클라이언트 값을 표시용으로만 씁니다.
     */
    public VerifiedSocialIdentity verifyLogin(String identityToken, String rawNonce, String clientName) {
        Claims claims = verifyClaims(identityToken, rawNonce);
        Object emailVerified = claims.get("email_verified");
        return new VerifiedSocialIdentity(
                SocialProvider.APPLE,
                claims.getSubject(),
                claims.get("email", String.class),
                Boolean.TRUE.equals(emailVerified) || "true".equalsIgnoreCase(String.valueOf(emailVerified)),
                displayName(clientName));
    }

    private Claims verifyClaims(String identityToken, String rawNonce) {
        List<String> allowedAudiences = parseList(appleClientIds);
        if (allowedAudiences.isEmpty()) {
            throw new IllegalStateException("Apple OAuth 설정이 누락되었습니다.");
        }
        if (rawNonce == null || rawNonce.isBlank()) {
            throw new OAuthVerificationException("nonce_missing");
        }

        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setClock(() -> Date.from(clock.instant()))
                    .setAllowedClockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .requireIssuer(APPLE_ISSUER)
                    .setSigningKeyResolver(new SigningKeyResolverAdapter() {
                        @Override
                        public Key resolveSigningKey(JwsHeader header, Claims ignored) {
                            // alg를 토큰 헤더에 맡기지 않고 RS256만 허용합니다.
                            if (!"RS256".equals(header.getAlgorithm())) {
                                throw new OAuthVerificationException("unexpected_algorithm");
                            }
                            return findKey(header.getKeyId());
                        }
                    })
                    .build()
                    .parseClaimsJws(identityToken)
                    .getBody();
        } catch (OAuthVerificationException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            throw new OAuthVerificationException("identity_token_invalid");
        }

        if (!audienceMatches(claims.get("aud"), allowedAudiences)) {
            throw new OAuthVerificationException("audience_mismatch");
        }
        if (!sha256Hex(rawNonce.trim()).equals(claims.get("nonce", String.class))) {
            throw new OAuthVerificationException("nonce_mismatch");
        }
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new OAuthVerificationException("provider_id_missing");
        }
        return claims;
    }

    private PublicKey findKey(String keyId) {
        if (keyId == null) {
            throw new OAuthVerificationException("key_id_missing");
        }
        Instant now = clock.instant();
        PublicKey key = cachedKeys.get(keyId);
        boolean expired = keysFetchedAt.plus(KEY_CACHE_TTL).isBefore(now);
        if (key != null && !expired) {
            return key;
        }
        // Apple이 키를 교체하면 새 kid가 먼저 나타날 수 있어, 캐시에 없을 때 한 번 다시 받아옵니다.
        if (expired || keysFetchedAt.plus(MIN_REFRESH_INTERVAL).isBefore(now)) {
            refreshKeys(now);
            key = cachedKeys.get(keyId);
        }
        if (key == null) {
            throw new OAuthVerificationException("unknown_key_id");
        }
        return key;
    }

    @SuppressWarnings("unchecked")
    private synchronized void refreshKeys(Instant now) {
        Map<String, Object> body = webClientBuilder.build()
                .get()
                .uri(APPLE_KEYS_URL)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        Object keys = body != null ? body.get("keys") : null;
        if (!(keys instanceof Collection<?> list)) {
            throw new OAuthVerificationException("apple_keys_unavailable");
        }

        Map<String, PublicKey> parsed = new HashMap<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> jwk && "RSA".equals(jwk.get("kty")) && jwk.get("kid") instanceof String kid) {
                parsed.put(kid, toRsaPublicKey((Map<String, Object>) jwk));
            }
        }
        if (parsed.isEmpty()) {
            throw new OAuthVerificationException("apple_keys_unavailable");
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
            throw new OAuthVerificationException("apple_keys_unavailable");
        }
    }

    // aud는 문자열 하나이거나 배열일 수 있습니다.
    private boolean audienceMatches(Object aud, List<String> allowed) {
        if (aud instanceof String value) {
            return allowed.contains(value);
        }
        if (aud instanceof Collection<?> values) {
            return values.stream().anyMatch(value -> allowed.contains(String.valueOf(value)));
        }
        return false;
    }

    // users.name은 VARCHAR(100)이며, 클라이언트가 보낸 값이라 길이만 제한해 표시용으로 씁니다.
    private String displayName(String clientName) {
        if (clientName == null || clientName.isBlank()) {
            return "Apple User";
        }
        String trimmed = clientName.trim();
        return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private List<String> parseList(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }
}
