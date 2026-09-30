package com.salus.healthytable.security;

import com.salus.healthytable.service.adminauth.AdminPrincipal;
import com.salus.healthytable.service.adminauth.AdminSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 관리자 access 토큰을 확인하는 필터입니다. /api/admin/** 보안 설정에서만 동작합니다.
 *
 * 토큰 서명(JWT_ADMIN_SECRET)과 서버 세션(무조작 30분, 최대 8시간, 강제 종료)을 모두 확인하고,
 * 권한은 DB의 관리자 역할(ADMIN, ADMIN_VIEWER)로 정합니다. 사용자 JWT는 서명 키가 달라 여기서 통과할 수 없습니다.
 */
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    // 로그인 단계 API는 헤더 토큰 대신 요청 본문의 challenge 토큰을 쓰므로 여기서 검사하지 않습니다.
    public static final Set<String> LOGIN_STEP_PATHS = Set.of(
            "/api/admin/auth/login",
            "/api/admin/auth/password",
            "/api/admin/auth/totp",
            "/api/admin/auth/totp/setup",
            "/api/admin/auth/totp/confirm");

    private final AdminSessionService sessionService;
    private final ApiSecurityErrorHandler errorHandler;

    public AdminAuthenticationFilter(AdminSessionService sessionService, ApiSecurityErrorHandler errorHandler) {
        this.sessionService = sessionService;
        this.errorHandler = errorHandler;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = bearerToken(request);
        if (!StringUtils.hasText(token) || LOGIN_STEP_PATHS.contains(pathWithinApplication(request))) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AdminPrincipal> principal;
        try {
            principal = sessionService.authenticate(token);
        } catch (RuntimeException ex) {
            // 관리자 키 설정 누락 같은 오류는 인증 실패로 처리합니다(fail closed).
            logger.warn("Admin authentication failed: " + ex.getClass().getSimpleName());
            principal = Optional.empty();
        }

        if (principal.isEmpty()) {
            errorHandler.handleInvalidToken(request, response, false);
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal.get(), null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.get().role().name())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }
}
