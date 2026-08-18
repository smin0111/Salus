package com.salus.healthytable.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secretKey;

    private final long VALIDITY_IN_MS = 3600000; // 1h
    private static final int MIN_SECRET_BYTES = 32;

    @PostConstruct
    void validateSecretOnStartup() {
        // JWT secret이 약하거나 예시값이면 애플리케이션 시작 단계에서 바로 실패시킵니다.
        // 운영 중에야 토큰 위조 위험을 발견하는 것보다 배포를 멈추는 편이 훨씬 안전합니다.
        validateSecretKey(secretKey);
    }

    private Key getSigningKey() {
        // 설정이 런타임에 바뀌는 환경까지 고려해 서명 키를 만들 때도 한 번 더 검증합니다.
        // 같은 검증이 반복되어도 보안 설정 실패를 조용히 넘기는 것보다 낫습니다.
        validateSecretKey(secretKey);
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private void validateSecretKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("JWT_SECRET 또는 jwt.secret 설정이 비어 있습니다.");
        }

        String normalized = value.toLowerCase();
        if (normalized.contains("replace-with")
                || normalized.contains("change-me")
                || normalized.contains("your-secret")
                || normalized.contains("example")) {
            throw new IllegalArgumentException("JWT_SECRET에 예시값이 들어 있습니다. 운영 또는 로컬 전용 난수 값으로 바꿔야 합니다.");
        }

        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("JWT 시크릿 키는 보안을 위해 반드시 최소 32바이트(256비트) 이상이어야 합니다.");
        }
    }

    public String createToken(String userId) {
        Date now = new Date();
        Date validity = new Date(now.getTime() + VALIDITY_IN_MS);

        return Jwts.builder()
                .setSubject(userId)
                .setIssuedAt(now)
                .setExpiration(validity)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // 만료, 변조, 형식 오류는 모두 인증 실패로만 처리합니다.
            // 자세한 실패 이유를 사용자에게 드러내면 공격자가 토큰 검증 방식을 추측하기 쉬워집니다.
            return false;
        }
    }

    public String getUserId(String token) {
        return Jwts.parserBuilder().setSigningKey(getSigningKey()).build()
                .parseClaimsJws(token).getBody().getSubject();
    }

    public String resolveToken(jakarta.servlet.http.HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
