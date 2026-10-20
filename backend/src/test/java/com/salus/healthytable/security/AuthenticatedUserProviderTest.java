package com.salus.healthytable.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuthenticatedUserProvider} 테스트입니다.
 */
class AuthenticatedUserProviderTest {

    private final AuthenticatedUserProvider provider = new AuthenticatedUserProvider();

    // 테스트끼리 인증 정보가 섞이지 않도록 SecurityContext를 비웁니다.
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // 인증된 principal의 사용자 ID를 반환해야 합니다.
    @Test
    void returnsCurrentUserIdFromAuthenticatedPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("42", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(provider.getCurrentUserId()).contains(42L);
        assertThat(provider.requireUserId()).isEqualTo(42L);
    }

    // 익명 사용자는 로그인하지 않은 것으로 보고, requireUserId는 401 예외를 던져야 합니다.
    @Test
    void anonymousUserIsNotAuthenticatedForApplicationUse() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(provider.getCurrentUserId()).isEmpty();
        assertThatThrownBy(provider::requireUserId)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("로그인이 필요합니다");
    }

    // 숫자가 아닌 principal은 무시해야 합니다.
    @Test
    void malformedPrincipalIsIgnored() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("not-a-number", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(provider.getCurrentUserId()).isEmpty();
    }
}
