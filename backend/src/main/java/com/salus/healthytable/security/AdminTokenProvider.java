package com.salus.healthytable.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * 관리자 전용 토큰을 발급·검증합니다. 사용자 JWT(JwtTokenProvider)와 서명 키(JWT_ADMIN_SECRET)가 다르고,
 * aud도 salus-admin으로 구분하므로 사용자 토큰은 관리자 API에서 서명 검증부터 실패합니다.
 *
 * - challenge 토큰: 비밀번호 확인 후 다음 단계(비밀번호 변경, TOTP 등록, TOTP 입력)까지 5분 동안만 쓰는 토큰
 * - access 토큰: 로그인 완료 후 API 호출에 쓰는 토큰. 세션 ID(sid)를 담고 요청마다 서버 세션을 확인합니다.
 */
@Component
public class AdminTokenProvider {

    public static final String AUDIENCE = "salus-admin";
    static final String TYPE_CHALLENGE = "admin-challenge";
    static final String TYPE_ACCESS = "admin-access";
    private static final long CHALLENGE_VALIDITY_SECONDS = 300;
    private static final int MIN_SECRET_BYTES = 32;

    private final Clock clock;

    @Value("${admin.jwt.secret:}")
    private String adminSecret;

    // 사용자 JWT 키와 같은 값을 쓰면 분리 의미가 없어지므로 비교용으로만 읽습니다.
    @Value("${jwt.secret:}")
    private String userSecret;

    public AdminTokenProvider(Clock clock) {
        this.clock = clock;
    }

    public String createChallenge(Long adminId, String stage) {
        Instant now = clock.instant();
        return Jwts.builder()
                .setSubject(String.valueOf(adminId))
                .setAudience(AUDIENCE)
                .setId(UUID.randomUUID().toString())
                .claim("typ", TYPE_CHALLENGE)
                .claim("stg", stage)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(CHALLENGE_VALIDITY_SECONDS)))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String createAccess(Long adminId, String sessionId, Instant expiresAt) {
        return Jwts.builder()
                .setSubject(String.valueOf(adminId))
                .setAudience(AUDIENCE)
                .claim("typ", TYPE_ACCESS)
                .claim("sid", sessionId)
                .setIssuedAt(Date.from(clock.instant()))
                .setExpiration(Date.from(expiresAt))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Optional<Claims> parseChallenge(String token) {
        return parse(token, TYPE_CHALLENGE);
    }

    public Optional<Claims> parseAccess(String token) {
        return parse(token, TYPE_ACCESS);
    }

    // 서명, 만료, aud, 토큰 종류가 모두 맞을 때만 claims를 돌려줍니다. 실패 이유는 드러내지 않습니다.
    private Optional<Claims> parse(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setClock(() -> Date.from(clock.instant()))
                    .requireAudience(AUDIENCE)
                    .require("typ", expectedType)
                    .setSigningKey(signingKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // 키가 없거나 약하거나 사용자 JWT 키와 같으면 관리자 인증을 전부 거부합니다(fail closed).
    // 애플리케이션 시작은 막지 않아 사용자 서비스는 계속 동작합니다.
    private Key signingKey() {
        if (adminSecret == null || adminSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_ADMIN_SECRET은 32바이트 이상이어야 합니다.");
        }
        if (adminSecret.equals(userSecret)) {
            throw new IllegalStateException("JWT_ADMIN_SECRET은 JWT_SECRET과 달라야 합니다.");
        }
        return Keys.hmacShaKeyFor(adminSecret.getBytes(StandardCharsets.UTF_8));
    }
}
