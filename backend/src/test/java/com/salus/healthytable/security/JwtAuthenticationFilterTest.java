package com.salus.healthytable.security;

import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserRole;
import com.salus.healthytable.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JwtAuthenticationFilter} 테스트입니다.
 */
class JwtAuthenticationFilterTest {

    private final ApiSecurityErrorHandler errorHandler = new ApiSecurityErrorHandler(new ObjectMapper());

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
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository, errorHandler);

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
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository, errorHandler);

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
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository, errorHandler);

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
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository, errorHandler);

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
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository, errorHandler);

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
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository, errorHandler);

        when(tokenProvider.validateToken("valid-token")).thenReturn(true);
        when(tokenProvider.getUserId("valid-token")).thenReturn("not-a-number");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userRepository, never()).findById(anyLong());
    }

    // 만료된 토큰은 공개 API라도 게스트로 넘기지 않고, 갱신하라는 TOKEN_EXPIRED 401을 받아야 합니다.
    @Test
    void expiredTokenReturnsTokenExpiredWithoutCallingChain() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository, errorHandler);
        when(tokenProvider.validateToken("expired-token")).thenReturn(false);
        when(tokenProvider.isExpired("expired-token")).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/message");
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"error\":\"TOKEN_EXPIRED\"");
        assertThat(chain.getRequest()).isNull();
    }

    // 위조되었거나 탈퇴한 사용자의 토큰은 UNAUTHORIZED 401을 받아야 합니다(갱신 대상 아님).
    @Test
    void invalidTokenReturnsUnauthorizedWithoutCallingChain() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userRepository, errorHandler);
        when(tokenProvider.validateToken("valid-token")).thenReturn(true);
        when(tokenProvider.getUserId("valid-token")).thenReturn("7");
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/recipes/1");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"error\":\"UNAUTHORIZED\"");
        assertThat(chain.getRequest()).isNull();
    }

    // 토큰이 없는 게스트 요청은 그대로 통과해 SecurityConfig 규칙에 맡겨야 합니다.
    @Test
    void requestWithoutTokenPassesThrough() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        JwtAuthenticationFilter filter =
                new JwtAuthenticationFilter(tokenProvider, mock(UserRepository.class), errorHandler);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/message");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(tokenProvider);
    }

    // 로그인·갱신·로그아웃 API는 만료된 access token이 붙어 와도 검사 없이 통과해야 합니다.
    @Test
    void authEndpointsIgnoreStaleAccessToken() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        JwtAuthenticationFilter filter =
                new JwtAuthenticationFilter(tokenProvider, mock(UserRepository.class), errorHandler);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.addHeader("Authorization", "Bearer expired-token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(tokenProvider);
    }
}
