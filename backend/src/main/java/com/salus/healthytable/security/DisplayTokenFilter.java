package com.salus.healthytable.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * 24시간 관제 화면(무인 디스플레이) 전용 인증 필터입니다. /api/monitor/** 보안 설정에서만 동작합니다.
 *
 * 사람 계정이 아니라 "화면 장치"에 권한을 줍니다. 토큰은 만료가 없고, 서버 설정에서 해시를 지우면 즉시 폐기됩니다.
 * 허용 권한은 ROLE_MONITOR(집계 통계 조회)뿐이라, 장치에서 토큰이 새어도 개인정보나 변경 기능에는 닿지 않습니다.
 * 서버에는 원문이 아닌 SHA-256 해시만 두고, 비교는 상수 시간으로 합니다.
 */
public class DisplayTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Salus-Display-Token";

    private final List<byte[]> allowedHashes;

    public DisplayTokenFilter(String configuredHashes) {
        this.allowedHashes = parseHashes(configuredHashes);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token != null && !token.isBlank() && matches(token.trim())) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "monitor-display", null, List.of(new SimpleGrantedAuthority("ROLE_MONITOR")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        // 토큰이 없거나 틀리면 인증 없이 넘기고, 보안 설정의 규칙이 401로 정리합니다.
        filterChain.doFilter(request, response);
    }

    private boolean matches(String token) {
        byte[] candidate = sha256(token);
        boolean matched = false;
        // 일치하는 해시를 찾아도 끝까지 비교해 응답 시간으로 토큰 정보를 흘리지 않습니다.
        for (byte[] allowed : allowedHashes) {
            matched |= MessageDigest.isEqual(candidate, allowed);
        }
        return matched;
    }

    // 설정이 비어 있거나 형식이 틀린 해시는 버립니다. 허용 해시가 없으면 어떤 토큰도 통과하지 않습니다.
    private static List<byte[]> parseHashes(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(hash -> hash.matches("[0-9a-f]{64}"))
                .map(HexFormat.of()::parseHex)
                .toList();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
