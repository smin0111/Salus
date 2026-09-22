package com.salus.healthytable.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IpWhitelistFilter} 테스트입니다.
 */
class IpWhitelistFilterTest {

    // IP 제한이 꺼져 있으면 관리자 요청을 막지 않아야 합니다.
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

    // IP 제한이 켜져 있으면 허용 목록에 없는 IP의 관리자 요청을 403으로 막아야 합니다.
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

    // 허용 목록에 있는 IP는 통과해야 합니다.
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
