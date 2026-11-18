package com.remoteroles.pipeline.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminTokenFilterTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private int status(AdminTokenFilter filter, String method, String servletPath, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, servletPath);
        request.setServletPath(servletPath);
        if (token != null) request.addHeader(AdminTokenFilter.HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void refusesWithoutTheToken() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter(TOKEN);
        assertThat(status(filter, "POST", "/admin/rank/1", null)).isEqualTo(403);
        assertThat(status(filter, "POST", "/admin/newsletter/send", "wrong")).isEqualTo(403);
        assertThat(status(filter, "POST", "/somewhere/else", null)).isEqualTo(403);
    }

    @Test
    void passesWithTheToken() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter(TOKEN);
        assertThat(status(filter, "POST", "/admin/rank/1", TOKEN)).isEqualTo(200);
    }

    @Test
    void healthStaysOpen() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter(TOKEN);
        assertThat(status(filter, "GET", "/admin/health", null)).isEqualTo(200);
        assertThat(status(filter, "POST", "/admin/health", null)).isEqualTo(403);
    }

    @Test
    void closedWhenTheTokenIsMissingOrShort() throws Exception {
        assertThat(status(new AdminTokenFilter(""), "POST", "/admin/rank/1", "")).isEqualTo(403);
        assertThat(status(new AdminTokenFilter("short"), "POST", "/admin/rank/1", "short")).isEqualTo(403);
    }
}
