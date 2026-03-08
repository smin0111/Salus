package com.mychefai.healthytable.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Cross-Origin-Opener-Policy 헤더를 완화하여 Google OAuth 팝업이
 * 정상적으로 토큰을 전달할 수 있도록 하는 필터.
 *
 * COOP 기본값(same-origin)은 구글 OAuth 팝업의 window.close/window.closed 접근을 차단함.
 * unsafe-none으로 설정해야 OAuth 흐름이 정상 작동함.
 */
@Component
public class CoopHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        // COOP와 COEP를 OAuth 팝업 호환 값으로 설정
        response.setHeader("Cross-Origin-Opener-Policy", "unsafe-none");
        response.setHeader("Cross-Origin-Embedder-Policy", "unsafe-none");

        filterChain.doFilter(request, response);
    }
}
