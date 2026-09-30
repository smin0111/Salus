package com.salus.healthytable.security;

import com.salus.healthytable.domain.User;
import com.salus.healthytable.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 요청 헤더의 JWT(Authorization: Bearer 토큰)를 확인해 로그인 사용자를 식별하는 필터입니다.
 *
 * 흐름: 헤더에서 토큰 추출 → 서명/만료 검증 → 토큰 속 사용자 ID로 DB 조회
 * → 사용자의 role로 권한(ROLE_USER, ROLE_ADMIN)을 만들어 SecurityContext에 저장.
 * 토큰이 없으면 막지 않고 SecurityConfig 규칙에 맡깁니다(게스트 허용 API 등).
 * 토큰이 있는데 만료·위조·탈퇴 사용자라 인증할 수 없으면 공개 API라도 401을 돌려줍니다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final ApiSecurityErrorHandler errorHandler;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String jwt = getJwtFromRequest(request);

        // 로그인·갱신·로그아웃 API는 access token 없이도 동작해야 하므로, 오래된 헤더가 붙어 와도 검사하지 않습니다.
        if (!StringUtils.hasText(jwt) || isAuthEndpoint(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean authenticated;
        try {
            authenticated = authenticate(jwt, request);
        } catch (Exception ex) {
            // DB 장애 같은 예외로 공개 API까지 500이 되지 않도록, 예외는 기존처럼 인증 없이 통과시킵니다.
            // 인증이 필요한 엔드포인트는 이후 SecurityConfig에서 401/403으로 정리됩니다.
            logger.warn("JWT authentication was skipped: " + ex.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("JWT authentication failure details", ex);
            }
            filterChain.doFilter(request, response);
            return;
        }

        if (!authenticated) {
            // 토큰을 보냈는데 인증할 수 없으면 공개 API라도 게스트로 조용히 넘기지 않고 401을 줍니다.
            // 로그인 사용자의 채팅이 만료 토큰 때문에 게스트 요청(알레르기 미반영)으로 처리되는 일을 막기 위해서입니다.
            errorHandler.handleInvalidToken(request, response, tokenProvider.isExpired(jwt));
            return;
        }

        filterChain.doFilter(request, response);
    }

    // 흐름: 서명/만료 검증 → 토큰 속 사용자 ID로 DB 조회 → role로 권한을 만들어 SecurityContext에 저장
    private boolean authenticate(String jwt, HttpServletRequest request) {
        if (!tokenProvider.validateToken(jwt)) {
            return false;
        }

        Optional<Long> parsedUserId = parseUserId(tokenProvider.getUserId(jwt));
        if (parsedUserId.isEmpty()) {
            return false;
        }

        // JWT 서명이 맞아도 User를 DB에서 다시 확인합니다.
        // 탈퇴한 사용자나 role이 바뀐 사용자의 오래된 토큰이 계속 권한을 갖지 않게 하기 위해서입니다.
        Optional<User> user = userRepository.findById(parsedUserId.get());
        if (user.isEmpty()) {
            return false;
        }

        String role = user.get().getRole() != null ? user.get().getRole().name() : "USER";
        List<SimpleGrantedAuthority> authorities = List.of(
                // 스프링 시큐리티의 hasRole("ADMIN")은 내부적으로 "ROLE_ADMIN" 권한을 찾으므로 접두사를 붙입니다.
                new SimpleGrantedAuthority("ROLE_" + role));

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                String.valueOf(parsedUserId.get()), null, authorities);

        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        // 이후 컨트롤러나 AuthenticatedUserProvider가 이 인증 정보를 읽어 사용자 ID를 알 수 있습니다.
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return true;
    }

    private boolean isAuthEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.startsWith("/api/auth/");
    }

    // 토큰의 subject(사용자 ID 문자열)를 숫자로 바꿉니다. 숫자가 아니면 인증하지 않은 것으로 처리합니다.
    private Optional<Long> parseUserId(String userId) {
        try {
            return Optional.of(Long.parseLong(userId));
        } catch (NumberFormatException ex) {
            if (logger.isDebugEnabled()) {
                logger.debug("JWT subject is not a numeric user id: " + userId);
            }
            return Optional.empty();
        }
    }

    // "Bearer " 접두사(7글자)를 떼고 순수 토큰 문자열만 반환합니다.
    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
