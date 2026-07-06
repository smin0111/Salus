package com.mychefai.healthytable.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void addsGeneratedRequestIdToResponseAndMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest servletRequest,
                    jakarta.servlet.ServletResponse servletResponse)
                    throws IOException, ServletException {
                String requestId = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);

                assertThat(requestId).isNotBlank();
                assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo(requestId);
                assertThat(MDC.get("requestId")).isEqualTo(requestId);
            }
        });

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void reusesSafeIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/community/posts");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "mobile-20260704_090000");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("mobile-20260704_090000");
        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo("mobile-20260704_090000");
    }

    @Test
    void replacesUnsafeIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/community/posts");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "bad id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER))
                .isNotBlank()
                .isNotEqualTo("bad id with spaces");
    }
}
