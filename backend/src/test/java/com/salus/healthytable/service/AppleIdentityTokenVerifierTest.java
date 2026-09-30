package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AppleIdentityTokenVerifier} 테스트입니다.
 * 서명·발급자·aud·만료·nonce 중 하나라도 맞지 않으면 로그인 정보가 만들어지지 않아야 합니다.
 */
class AppleIdentityTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-30T03:00:00Z");
    private static final String BUNDLE_ID = "com.salus.healthytable";
    private static final String RAW_NONCE = "raw-nonce-value";

    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    private final KeyPair appleKey = rsaKeyPair();
    private final AtomicInteger keyFetches = new AtomicInteger();
    private final AppleIdentityTokenVerifier verifier = verifierWithKeys(jwks("kid-1", appleKey));

    // 올바른 토큰이면 sub로 식별하고, 이메일 검증 여부와 클라이언트가 보낸 이름을 전달해야 합니다.
    @Test
    void validTokenReturnsIdentity() {
        VerifiedSocialIdentity identity = verifier.verifyLogin(validToken().compact(), RAW_NONCE, "  홍길동 ");

        assertThat(identity.provider()).isEqualTo(SocialProvider.APPLE);
        assertThat(identity.providerUserId()).isEqualTo("apple-sub");
        assertThat(identity.email()).isEqualTo("relay@privaterelay.appleid.com");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.name()).isEqualTo("홍길동");
    }

    // Apple 공개키는 캐시해서 로그인마다 다시 받지 않아야 합니다.
    @Test
    void appleKeysAreCachedBetweenLogins() {
        verifier.verifyLogin(validToken().compact(), RAW_NONCE, null);
        verifier.verifyLogin(validToken().compact(), RAW_NONCE, null);

        assertThat(keyFetches).hasValue(1);
    }

    // 이름이 없으면 기본 표시 이름을 쓰고, 너무 길면 users.name 길이에 맞춰 잘라야 합니다.
    @Test
    void displayNameFallsBackAndIsTruncated() {
        assertThat(verifier.verifyLogin(validToken().compact(), RAW_NONCE, null).name()).isEqualTo("Apple User");
        assertThat(verifier.verifyLogin(validToken().compact(), RAW_NONCE, "가".repeat(150)).name()).hasSize(100);
    }

    // 다른 앱(aud)에 발급된 토큰은 거부해야 합니다.
    @Test
    void tokenForAnotherAppIsRejected() {
        String token = validToken().setAudience("com.other.app").compact();

        assertReason(() -> verifier.verifyLogin(token, RAW_NONCE, null), "audience_mismatch");
    }

    // 요청의 원문 nonce가 토큰의 nonce 해시와 다르면(재사용 시도) 거부해야 합니다.
    @Test
    void nonceMismatchIsRejected() {
        assertReason(() -> verifier.verifyLogin(validToken().compact(), "other-nonce", null), "nonce_mismatch");
    }

    // Apple 키가 아닌 키로 서명한 토큰은 kid가 같아도 거부해야 합니다.
    @Test
    void tokenSignedByAnotherKeyIsRejected() {
        String token = validToken().signWith(rsaKeyPair().getPrivate(), SignatureAlgorithm.RS256).compact();

        assertReason(() -> verifier.verifyLogin(token, RAW_NONCE, null), "identity_token_invalid");
    }

    // 만료된 토큰은 허용 오차(60초)를 넘으면 거부해야 합니다.
    @Test
    void expiredTokenIsRejected() {
        String token = validToken().setExpiration(Date.from(NOW.minusSeconds(120))).compact();

        assertReason(() -> verifier.verifyLogin(token, RAW_NONCE, null), "identity_token_invalid");
    }

    // 발급자가 Apple이 아니면 거부해야 합니다.
    @Test
    void wrongIssuerIsRejected() {
        String token = validToken().setIssuer("https://evil.example").compact();

        assertReason(() -> verifier.verifyLogin(token, RAW_NONCE, null), "identity_token_invalid");
    }

    // 헤더의 alg를 HS256으로 바꾼 토큰(알고리즘 혼동 공격)은 키를 찾기 전에 거부해야 합니다.
    @Test
    void nonRsaAlgorithmIsRejected() {
        String token = validToken()
                .signWith(Keys.hmacShaKeyFor("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();

        assertReason(() -> verifier.verifyLogin(token, RAW_NONCE, null), "unexpected_algorithm");
    }

    // Apple 키 목록에 없는 kid는 거부해야 합니다.
    @Test
    void unknownKeyIdIsRejected() {
        String token = validToken().setHeaderParam("kid", "kid-unknown").compact();

        assertReason(() -> verifier.verifyLogin(token, RAW_NONCE, null), "unknown_key_id");
    }

    // aud 허용 목록 설정이 비어 있으면 검증을 건너뛰지 않고 Apple 호출 전에 실패해야 합니다.
    @Test
    void failsClosedWhenClientIdsMissing() {
        ReflectionTestUtils.setField(verifier, "appleClientIds", "");

        assertThatThrownBy(() -> verifier.verifyLogin(validToken().compact(), RAW_NONCE, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(keyFetches).hasValue(0);
    }

    private JwtBuilder validToken() {
        return Jwts.builder()
                .setHeaderParam("kid", "kid-1")
                .setIssuer(AppleIdentityTokenVerifier.APPLE_ISSUER)
                .setAudience(BUNDLE_ID)
                .setSubject("apple-sub")
                .setIssuedAt(Date.from(NOW))
                .setExpiration(Date.from(NOW.plusSeconds(600)))
                .claim("nonce", AppleIdentityTokenVerifier.sha256Hex(RAW_NONCE))
                .claim("email", "relay@privaterelay.appleid.com")
                .claim("email_verified", "true")
                .signWith(appleKey.getPrivate(), SignatureAlgorithm.RS256);
    }

    private void assertReason(Runnable call, String reason) {
        assertThatThrownBy(call::run)
                .isInstanceOf(OAuthVerificationException.class)
                .hasMessage(reason);
    }

    private AppleIdentityTokenVerifier verifierWithKeys(String jwksJson) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    keyFetches.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(jwksJson)
                            .build());
                });
        AppleIdentityTokenVerifier created = new AppleIdentityTokenVerifier(builder, clock);
        ReflectionTestUtils.setField(created, "appleClientIds", BUNDLE_ID + ",com.salus.web");
        return created;
    }

    private static String jwks(String kid, KeyPair keyPair) {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"use\":\"sig\",\"alg\":\"RS256\","
                + "\"n\":\"" + encoder.encodeToString(publicKey.getModulus().toByteArray()) + "\","
                + "\"e\":\"" + encoder.encodeToString(publicKey.getPublicExponent().toByteArray()) + "\"}]}";
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
