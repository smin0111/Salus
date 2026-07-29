package com.salus.healthytable.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Component
public class IpWhitelistFilter extends OncePerRequestFilter {

    @Value("${app.admin.ip-whitelist.enabled:false}")
    private boolean ipWhitelistEnabled;

    @Value("${app.admin.allowed-ips:127.0.0.1,0:0:0:0:0:0:0:1,::1,localhost}")
    private String allowedIps;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // 관리자 엔드포인트 IP 제한은 운영 환경에 따라 선택적으로 켭니다.
        // 컨테이너나 프록시 뒤에서는 실제 사용자 IP가 다르게 보일 수 있어 기본값은 꺼 둡니다.
        if (ipWhitelistEnabled && path.startsWith("/api/admin/")) {
            String remoteAddr = request.getRemoteAddr();

            if (!parseAllowedIps().contains(remoteAddr)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("관리자 접근이 허용되지 않은 IP입니다. (" + remoteAddr + ")");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private List<String> parseAllowedIps() {
        return Arrays.stream(allowedIps.split(","))
                .map(String::trim)
                .filter(ip -> !ip.isBlank())
                .toList();
    }
}
