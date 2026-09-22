package com.salus.healthytable.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * OAuth 로그인 API에서만 Cross-Origin-Opener-Policy 헤더를 완화하여
 * 브라우저 팝업 기반 로그인 흐름과 충돌하지 않도록 하는 필터.
 *
 * 일반 API 응답까지 전부 완화하면 보안 헤더의 의미가 약해지므로
 * /api/auth/** 요청에만 예외적으로 적용한다.
 */
@Component
public class CoopHeaderFilter extends OncePerRequestFilter {

    private static final String AUTH_API_PREFIX = "/api/auth/";

    // OncePerRequestFilter는 한 요청당 딱 한 번만 실행되도록 보장해 주는 스프링 필터 기본 클래스입니다.
    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        if (isAuthApiRequest(request)) {
            response.setHeader("Cross-Origin-Opener-Policy", "unsafe-none");
            response.setHeader("Cross-Origin-Embedder-Policy", "unsafe-none");
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 요청 경로가 인증 API(/api/auth/**)인지 확인합니다.
     * 서버가 /app 같은 context path 아래에서 실행될 수도 있으므로, 앞부분 context path를 떼고 비교합니다.
     */
    private boolean isAuthApiRequest(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();

        if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
            requestUri = requestUri.substring(contextPath.length());
        }

        return requestUri.startsWith(AUTH_API_PREFIX);
    }
}
