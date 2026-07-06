package com.mychefai.healthytable.security;

import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.repository.UserRepository;
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
