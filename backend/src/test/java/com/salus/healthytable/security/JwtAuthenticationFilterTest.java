package com.salus.healthytable.security;

import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserRole;
import com.salus.healthytable.repository.UserRepository;
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

/**
 * {@link JwtAuthenticationFilter} 테스트입니다.
 */
class JwtAuthenticationFilterTest {

    // 테스트끼리 인증 정보가 섞이지 않도록 SecurityContext를 비웁니다.
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // 관리자 사용자의 토큰은 ROLE_ADMIN 권한을 받아야 합니다.
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

    // role 값이 없는 예전 사용자는 ROLE_USER로 처리해야 합니다.
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

    // 같은 토큰이라도 요청마다 DB의 최신 role을 기준으로 권한을 정해야 합니다.
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

    // 탈퇴한(DB에 없는) 사용자의 토큰은 인증되지 않아야 합니다.
    @Test
    void tokenForDeletedUserDoesNotAuthenticateUser() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository);

        when(tokenProvider.validateToken("valid-token")).thenReturn(true);
        when(tokenProvider.getUserId("valid-token")).thenReturn("7");
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRepository).findById(7L);
    }

    // 유효하지 않은 토큰은 DB 조회 없이 인증되지 않아야 합니다.
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

    // subject가 숫자가 아닌 토큰은 인증되지 않아야 합니다.
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
