package com.mychefai.healthytable.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
public class IpWhitelistFilter extends OncePerRequestFilter {

    // 허용된 IP 목록 (Localhost 및 일반적인 로컬 주소)
    private static final List<String> ALLOWED_IPS = Arrays.asList(
            "127.0.0.1",
            "0:0:0:0:0:0:0:1",
            "localhost",
            "172.30.1.86", // 로컬 네트워크 IP
            "121.125.161.88" // 공인 IP (2026-03-08 등록)
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // 관리자 엔드포인트(/api/admin/)에만 화이트리스트 적용
        if (path.startsWith("/api/admin/")) {
            String remoteAddr = request.getRemoteAddr();

            if (!ALLOWED_IPS.contains(remoteAddr)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("Access Denied: IP not whitelisted (" + remoteAddr + ")");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
