package com.salus.healthytable.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class IpWhitelistFilterTest {

    @Test
    void disabledWhitelistDoesNotBlockAdminRequest() throws Exception {
        IpWhitelistFilter filter = new IpWhitelistFilter();
        ReflectionTestUtils.setField(filter, "ipWhitelistEnabled", false);
        ReflectionTestUtils.setField(filter, "allowedIps", "127.0.0.1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/dashboard/stats");
        request.setRemoteAddr("10.0.0.8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void enabledWhitelistBlocksUnknownAdminIp() throws Exception {
        IpWhitelistFilter filter = new IpWhitelistFilter();
        ReflectionTestUtils.setField(filter, "ipWhitelistEnabled", true);
        ReflectionTestUtils.setField(filter, "allowedIps", "127.0.0.1,::1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/dashboard/stats");
        request.setRemoteAddr("10.0.0.8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("관리자 접근이 허용되지 않은 IP");
    }

    @Test
    void enabledWhitelistAllowsConfiguredAdminIp() throws Exception {
        IpWhitelistFilter filter = new IpWhitelistFilter();
        ReflectionTestUtils.setField(filter, "ipWhitelistEnabled", true);
        ReflectionTestUtils.setField(filter, "allowedIps", "127.0.0.1,10.0.0.8");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/dashboard/stats");
        request.setRemoteAddr("10.0.0.8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
