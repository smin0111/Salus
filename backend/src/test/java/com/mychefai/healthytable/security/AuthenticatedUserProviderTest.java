package com.mychefai.healthytable.security;

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

class AuthenticatedUserProviderTest {

    private final AuthenticatedUserProvider provider = new AuthenticatedUserProvider();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsCurrentUserIdFromAuthenticatedPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("42", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(provider.getCurrentUserId()).contains(42L);
        assertThat(provider.requireUserId()).isEqualTo(42L);
    }

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

    @Test
    void malformedPrincipalIsIgnored() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("not-a-number", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(provider.getCurrentUserId()).isEmpty();
    }
}
