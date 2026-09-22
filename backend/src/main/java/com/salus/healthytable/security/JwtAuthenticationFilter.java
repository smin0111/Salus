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
 * 토큰이 없거나 잘못되어도 여기서 요청을 막지 않고, 막을지 여부는 SecurityConfig 규칙이 결정합니다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt)) {
                boolean isValid = tokenProvider.validateToken(jwt);

                if (isValid) {
                    String userId = tokenProvider.getUserId(jwt);
                    Optional<Long> parsedUserId = parseUserId(userId);

                    if (parsedUserId.isPresent()) {
                        // JWT 서명이 맞아도 User를 DB에서 다시 확인합니다.
                        // 탈퇴한 사용자나 role이 바뀐 사용자의 오래된 토큰이 계속 권한을 갖지 않게 하기 위해서입니다.
                        Optional<User> user = userRepository.findById(parsedUserId.get());

                        if (user.isPresent()) {
                            String role = user.get().getRole() != null ? user.get().getRole().name() : "USER";
                            List<SimpleGrantedAuthority> authorities = List.of(
                                    // 스프링 시큐리티의 hasRole("ADMIN")은 내부적으로 "ROLE_ADMIN" 권한을 찾으므로 접두사를 붙입니다.
                                    new SimpleGrantedAuthority("ROLE_" + role));

                            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                    String.valueOf(parsedUserId.get()), null, authorities);

                            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                            // 이후 컨트롤러나 AuthenticatedUserProvider가 이 인증 정보를 읽어 사용자 ID를 알 수 있습니다.
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            // 잘못된 토큰 하나 때문에 공개 API까지 500으로 실패하면 장애처럼 보입니다.
            // 인증 설정이 필요한 엔드포인트는 이후 SecurityConfig에서 401/403으로 정리됩니다.
            logger.warn("JWT authentication was skipped: " + ex.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug("JWT authentication failure details", ex);
            }
        }

        filterChain.doFilter(request, response);
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
