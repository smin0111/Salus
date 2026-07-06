package com.mychefai.healthytable.security;

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
        validateSecretKey(secretKey);
    }

    private Key getSigningKey() {
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
