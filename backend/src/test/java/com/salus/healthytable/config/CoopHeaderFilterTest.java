package com.salus.healthytable.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoopHeaderFilter} 테스트입니다. OAuth 팝업 호환 헤더가 인증 API에만 붙는지 확인합니다.
 */
class CoopHeaderFilterTest {

    private final CoopHeaderFilter filter = new CoopHeaderFilter();

    // /api/auth/** 요청에는 COOP/COEP 헤더를 unsafe-none으로 설정합니다.
    @Test
    void authApiRequestsKeepOAuthPopupCompatibleHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/google");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isEqualTo("unsafe-none");
        assertThat(response.getHeader("Cross-Origin-Embedder-Policy")).isEqualTo("unsafe-none");
    }

    // 일반 API 요청에는 완화된 헤더를 붙이지 않습니다.
    @Test
    void normalApiRequestsDoNotReceiveOAuthPopupHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isNull();
        assertThat(response.getHeader("Cross-Origin-Embedder-Policy")).isNull();
    }
}
