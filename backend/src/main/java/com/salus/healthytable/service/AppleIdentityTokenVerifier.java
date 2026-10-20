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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

/**
 * Sign in with Apple의 identity token(JWT)을 검증합니다.
 *
 * 검증 항목: Apple 공개키(JWKS) RS256 서명, iss, aud(Salus 번들 ID/Services ID), 만료, nonce.
 * nonce는 클라이언트가 만든 원문의 SHA-256 값을 Apple에 넘기고 원문을 서버로 보내는 방식입니다.
 * 토큰만 탈취해서는 원문 nonce를 모르므로 다른 요청에 재사용하기 어렵습니다.
 */
@Component
public class AppleIdentityTokenVerifier {

    static final String APPLE_ISSUER = "https://appleid.apple.com";
    private static final String APPLE_KEYS_URL = "https://appleid.apple.com/auth/keys";
    private static final long CLOCK_SKEW_SECONDS = 60;
    private static final int MAX_NAME_LENGTH = 100;

    private final Clock clock;
    // Apple 공개키 캐시(구글 ID token 검증과 같은 방식)
    private final JwksKeyCache keys;

    // 쉼표로 구분한 aud 허용 목록(iOS 번들 ID, 웹용 Services ID)
    @Value("${oauth.apple.client-ids:}")
    private String appleClientIds;

    public AppleIdentityTokenVerifier(WebClient.Builder webClientBuilder, Clock clock) {
        this.clock = clock;
        this.keys = new JwksKeyCache(webClientBuilder, clock, APPLE_KEYS_URL);
    }

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
                            return keys.findKey(header.getKeyId());
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
