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
 * {@link GoogleIdTokenVerifier} 테스트입니다. 서명·발급자·aud·만료·sub·nonce 중 하나라도 맞지 않으면 거부해야 합니다.
 */
class GoogleIdTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final String CLIENT_ID = "ios-client.apps.googleusercontent.com";

    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    private final KeyPair googleKey = rsaKeyPair();
    private final AtomicInteger keyFetches = new AtomicInteger();
    private final GoogleIdTokenVerifier verifier = verifierWithKeys(jwks("kid-g1", googleKey));

    // 올바른 토큰이면 sub로 식별하고, 소비할 nonce와 이름·이메일을 돌려줘야 합니다.
    @Test
    void validTokenReturnsIdentityAndNonce() {
        GoogleIdTokenVerifier.Result result = verifier.verify(validToken().compact());

        assertThat(result.nonce()).isEqualTo("server-nonce");
        assertThat(result.identity().provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(result.identity().providerUserId()).isEqualTo("google-sub");
        assertThat(result.identity().email()).isEqualTo("me@gmail.com");
        assertThat(result.identity().emailVerified()).isTrue();
        assertThat(result.identity().name()).isEqualTo("홍길동");
        assertThat(result.identity().identityConfirmedAt()).isNull();
    }

    // 두 가지 발급자 표기(accounts.google.com / https://accounts.google.com)를 모두 받아야 합니다.
    @Test
    void bothGoogleIssuerFormsAreAccepted() {
        assertThat(verifier.verify(validToken().setIssuer("accounts.google.com").compact()).identity()).isNotNull();
    }

    // 외부 이메일 구글 계정은 email_verified가 true여도 미확인, 회사·학교(hd)는 확인됨이어야 합니다.
    @Test
    void externalEmailIsUnverifiedButWorkspaceIsVerified() {
        assertThat(verifier.verify(validToken().claim("email", "me@naver.com").compact()).identity().emailVerified()).isFalse();
        assertThat(verifier.verify(validToken().claim("email", "me@school.ac.kr").claim("hd", "school.ac.kr").compact())
                .identity().emailVerified()).isTrue();
    }

    // 공개키는 캐시해서 로그인마다 다시 받지 않아야 합니다.
    @Test
    void keysAreCached() {
        verifier.verify(validToken().compact());
        verifier.verify(validToken().compact());
        assertThat(keyFetches).hasValue(1);
    }

    // nonce가 없는 토큰은 이번 요청의 응답인지 확인할 수 없으므로 거부해야 합니다.
    @Test
    void tokenWithoutNonceIsRejected() {
        String token = Jwts.builder()
                .setHeaderParam("kid", "kid-g1")
                .setIssuer("https://accounts.google.com")
                .setAudience(CLIENT_ID)
                .setSubject("google-sub")
                .setExpiration(Date.from(NOW.plusSeconds(600)))
                .signWith(googleKey.getPrivate(), SignatureAlgorithm.RS256)
                .compact();
        assertReason(() -> verifier.verify(token), "nonce_missing");
    }

    // 다른 앱(aud)·다른 발급자·만료·다른 키 서명·HS256 혼동 토큰은 모두 거부해야 합니다.
    @Test
    void invalidTokensAreRejected() {
        assertReason(() -> verifier.verify(validToken().setAudience("other-app").compact()), "audience_mismatch");
        assertReason(() -> verifier.verify(validToken().setIssuer("https://evil.example").compact()), "identity_token_invalid");
        assertReason(() -> verifier.verify(validToken().setExpiration(Date.from(NOW.minusSeconds(120))).compact()),
                "identity_token_invalid");
        assertReason(() -> verifier.verify(validToken().signWith(rsaKeyPair().getPrivate(), SignatureAlgorithm.RS256).compact()),
                "identity_token_invalid");
        assertReason(() -> verifier.verify(validToken()
                .signWith(Keys.hmacShaKeyFor("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256).compact()), "unexpected_algorithm");
        assertReason(() -> verifier.verify(validToken().setHeaderParam("kid", "kid-unknown").compact()), "unknown_key_id");
    }

    // 클라이언트 ID 설정이 비어 있으면 검증을 건너뛰지 않고 실패해야 합니다.
    @Test
    void failsClosedWhenClientIdsMissing() {
        ReflectionTestUtils.setField(verifier, "googleClientIds", "");
        assertThatThrownBy(() -> verifier.verify(validToken().compact())).isInstanceOf(IllegalStateException.class);
        assertThat(keyFetches).hasValue(0);
    }

    private JwtBuilder validToken() {
        return Jwts.builder()
                .setHeaderParam("kid", "kid-g1")
                .setIssuer("https://accounts.google.com")
                .setAudience(CLIENT_ID)
                .setSubject("google-sub")
                .setIssuedAt(Date.from(NOW))
                .setExpiration(Date.from(NOW.plusSeconds(600)))
                .claim("nonce", "server-nonce")
                .claim("email", "me@gmail.com")
                .claim("email_verified", true)
                .claim("name", "홍길동")
                .signWith(googleKey.getPrivate(), SignatureAlgorithm.RS256);
    }

    private void assertReason(Runnable call, String reason) {
        assertThatThrownBy(call::run).isInstanceOf(OAuthVerificationException.class).hasMessage(reason);
    }

    private GoogleIdTokenVerifier verifierWithKeys(String jwksJson) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            keyFetches.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json").body(jwksJson).build());
        });
        GoogleIdTokenVerifier created = new GoogleIdTokenVerifier(builder, clock);
        ReflectionTestUtils.setField(created, "googleClientIds", "web-client," + CLIENT_ID);
        return created;
    }

    private static String jwks(String kid, KeyPair keyPair) {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"alg\":\"RS256\",\"use\":\"sig\","
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
