package com.mychefai.healthytable.security;

import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.domain.UserRole;
import com.mychefai.healthytable.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedAdminTokenGetsAdminAuthority() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository);

        User admin = new User();
        admin.setId(7L);
        admin.setRole(UserRole.ADMIN);

        when(tokenProvider.validateToken("valid-token")).thenReturn(true);
        when(tokenProvider.getUserId("valid-token")).thenReturn("7");
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo("7");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void legacyUserWithoutRoleGetsUserAuthority() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository);

        User user = new User();
        user.setId(8L);
        user.setRole(null);

        when(tokenProvider.validateToken("valid-token")).thenReturn(true);
        when(tokenProvider.getUserId("valid-token")).thenReturn("8");
        when(userRepository.findById(8L)).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void existingTokenUsesLatestDatabaseRoleOnEachRequest() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository);

        User admin = new User();
        admin.setId(7L);
        admin.setRole(UserRole.ADMIN);

        User demotedUser = new User();
        demotedUser.setId(7L);
        demotedUser.setRole(UserRole.USER);

        when(tokenProvider.validateToken("valid-token")).thenReturn(true);
        when(tokenProvider.getUserId("valid-token")).thenReturn("7");
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin), Optional.of(demotedUser));

        MockHttpServletRequest firstRequest = new MockHttpServletRequest();
        firstRequest.addHeader("Authorization", "Bearer valid-token");

        filter.doFilter(firstRequest, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");

        SecurityContextHolder.clearContext();

        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
        secondRequest.addHeader("Authorization", "Bearer valid-token");

        filter.doFilter(secondRequest, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void invalidTokenDoesNotAuthenticateUser() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository);

        when(tokenProvider.validateToken("invalid-token")).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    void tokenWithNonNumericSubjectDoesNotAuthenticateUser() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository);

        when(tokenProvider.validateToken("valid-token")).thenReturn(true);
        when(tokenProvider.getUserId("valid-token")).thenReturn("not-a-number");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRepository, never()).findById(anyLong());
    }
}
