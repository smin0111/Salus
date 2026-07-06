package com.mychefai.healthytable.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mychefai.healthytable.config.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSecurityErrorHandlerTest {

    private final ApiSecurityErrorHandler handler = new ApiSecurityErrorHandler(new ObjectMapper());

    @Test
    void authenticationFailureReturnsJson401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleAuthenticationException(request, response, new BadCredentialsException("bad token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsString())
                .contains("\"status\":401")
                .contains("\"error\":\"UNAUTHORIZED\"")
                .contains("\"message\":\"로그인이 필요합니다.\"")
                .contains("\"path\":\"/api/users/me\"");
    }

    @Test
    void accessDeniedReturnsJson403() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/stats");
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "req-admin-403");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleAccessDeniedException(request, response, new AccessDeniedException("not admin"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsString())
                .contains("\"status\":403")
                .contains("\"error\":\"FORBIDDEN\"")
                .contains("\"message\":\"접근 권한이 없습니다.\"")
                .contains("\"path\":\"/api/admin/stats\"")
                .contains("\"requestId\":\"req-admin-403\"");
    }
}
