package com.salus.healthytable.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CoopHeaderFilterTest {

    private final CoopHeaderFilter filter = new CoopHeaderFilter();

    @Test
    void authApiRequestsKeepOAuthPopupCompatibleHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/google");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isEqualTo("unsafe-none");
        assertThat(response.getHeader("Cross-Origin-Embedder-Policy")).isEqualTo("unsafe-none");
    }

    @Test
    void normalApiRequestsDoNotReceiveOAuthPopupHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isNull();
        assertThat(response.getHeader("Cross-Origin-Embedder-Policy")).isNull();
    }
}
