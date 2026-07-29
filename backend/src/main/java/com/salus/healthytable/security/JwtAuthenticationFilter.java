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
                                    new SimpleGrantedAuthority("ROLE_" + role));

                            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                    String.valueOf(parsedUserId.get()), null, authorities);

                            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

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

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
