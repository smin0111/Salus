package com.salus.healthytable.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.config.RequestIdFilter;
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

        // Spring Security 단계의 인증 실패도 JSON으로 내려와야 프론트엔드가 같은 오류 처리 유틸을 쓸 수 있습니다.
        // HTML 기본 오류 페이지가 내려오면 모바일 앱이나 웹 화면에서 메시지를 안정적으로 보여주기 어렵습니다.
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

        // 403 응답에 requestId가 포함되면 운영자가 관리자 접근 실패 로그를 더 빨리 찾을 수 있습니다.
        // 인증 실패와 권한 부족을 구분하는지도 함께 확인합니다.
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
