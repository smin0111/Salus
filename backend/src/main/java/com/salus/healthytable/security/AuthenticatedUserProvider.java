package com.salus.healthytable.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * 현재 요청을 보낸 로그인 사용자의 ID를 꺼내 주는 도우미 클래스입니다.
 *
 * JwtAuthenticationFilter가 SecurityContext에 저장한 인증 정보를 읽습니다.
 * 컨트롤러/서비스는 SecurityContextHolder를 직접 다루지 않고 이 클래스를 통해 사용자 ID를 얻습니다.
 */
@Component
public class AuthenticatedUserProvider {

    /**
     * 로그인한 사용자 ID를 반환합니다. 게스트(비로그인)이면 빈 Optional을 반환합니다.
     */
    public Optional<Long> getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        // principal에는 필터 구현에 따라 Long 또는 문자열 형태의 사용자 ID가 들어 있을 수 있어 둘 다 처리합니다.
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long userId) {
            return Optional.of(userId);
        }
        if (principal instanceof String userId && !"anonymousUser".equals(userId)) {
            try {
                return Optional.of(Long.parseLong(userId));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * 로그인이 필수인 기능에서 사용합니다. 로그인하지 않았다면 401 예외를 던집니다.
     */
    public Long requireUserId() {
        return getCurrentUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }
}
