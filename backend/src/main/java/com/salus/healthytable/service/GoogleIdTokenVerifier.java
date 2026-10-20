package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SigningKeyResolverAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.security.Key;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 구글 ID token(JWT)을 검증합니다. 검증 항목: 구글 공개키(JWKS) RS256 서명, iss, aud(Salus 클라이언트 ID), 만료, sub, nonce 존재.
 *
 * nonce는 앱이 로그인 직전에 서버(/api/auth/nonce)에서 받아 구글 로그인 요청에 넣은 값입니다. 이 클래스는 값을 꺼내기만 하고,
 * 실제로 서버가 발급했고 아직 쓰지 않았는지는 호출한 쪽(SocialCredentialVerifier)이 소비하며 확인합니다.
 * nonce가 보장하는 것은 "이번 요청에 대한 응답"이라는 것까지이며, 비밀번호 재입력은 보장하지 않습니다.
 */
@Component
public class GoogleIdTokenVerifier {

    static final String GOOGLE_KEYS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");
    private static final long CLOCK_SKEW_SECONDS = 60;
    private static final int MAX_NAME_LENGTH = 100;

    private final Clock clock;
    private final JwksKeyCache keys;

    // 쉼표로 구분한 Salus의 Google OAuth 클라이언트 ID 목록(web, ios, android). access token 검증과 같은 설정입니다.
    @Value("${oauth.google.client-ids:}")
    private String googleClientIds;

    public GoogleIdTokenVerifier(WebClient.Builder webClientBuilder, Clock clock) {
        this.clock = clock;
        this.keys = new JwksKeyCache(webClientBuilder, clock, GOOGLE_KEYS_URL);
    }

    /** 검증된 사용자 정보와, 소비해야 할 nonce입니다. */
    public record Result(VerifiedSocialIdentity identity, String nonce) {
    }

    public Result verify(String idToken) {
        List<String> allowedAudiences = parseList(googleClientIds);
        if (allowedAudiences.isEmpty()) {
            throw new IllegalStateException("Google OAuth 설정이 누락되었습니다.");
        }

        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setClock(() -> Date.from(clock.instant()))
                    .setAllowedClockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .setSigningKeyResolver(new SigningKeyResolverAdapter() {
                        @Override
                        public Key resolveSigningKey(JwsHeader header, Claims ignored) {
                            // alg를 토큰 헤더에 맡기지 않고 RS256만 허용합니다.
                            if (!"RS256".equals(header.getAlgorithm())) {
                                throw new OAuthVerificationException("unexpected_algorithm");
                            }
                            return keys.findKey(header.getKeyId());
                        }
                    })
                    .build()
                    .parseClaimsJws(idToken)
                    .getBody();
        } catch (OAuthVerificationException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            throw new OAuthVerificationException("identity_token_invalid");
        }

        if (!GOOGLE_ISSUERS.contains(claims.getIssuer())) {
            throw new OAuthVerificationException("identity_token_invalid");
        }
        if (!audienceMatches(claims.get("aud"), allowedAudiences)) {
            throw new OAuthVerificationException("audience_mismatch");
        }
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new OAuthVerificationException("provider_id_missing");
        }
        String nonce = claims.get("nonce", String.class);
        if (nonce == null || nonce.isBlank()) {
            throw new OAuthVerificationException("nonce_missing");
        }

        String email = claims.get("email", String.class);
        VerifiedSocialIdentity identity = new VerifiedSocialIdentity(
                SocialProvider.GOOGLE,
                claims.getSubject(),
                email,
                GoogleEmailPolicy.isAuthoritative(email, claims.get("email_verified"), claims.get("hd")),
                displayName(claims.get("name", String.class)));
        return new Result(identity, nonce);
    }

    private boolean audienceMatches(Object aud, List<String> allowed) {
        if (aud instanceof String value) {
            return allowed.contains(value);
        }
        if (aud instanceof Collection<?> values) {
            return values.stream().anyMatch(value -> allowed.contains(String.valueOf(value)));
        }
        return false;
    }

    private String displayName(String name) {
        if (name == null || name.isBlank()) {
            return "Google User";
        }
        String trimmed = name.trim();
        return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
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
