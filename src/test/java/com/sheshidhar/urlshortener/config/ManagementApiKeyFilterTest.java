package com.sheshidhar.urlshortener.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ManagementApiKeyFilterTest {

    private static final String API_KEY = "a-production-strength-management-key";

    @Test
    void rejectsManagementRequestWithoutConfiguredKey() throws Exception {
        ManagementApiKeyFilter filter = filter(API_KEY);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/urls");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void acceptsManagementRequestWithConfiguredKey() throws Exception {
        ManagementApiKeyFilter filter = filter(API_KEY);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/urls/product123");
        request.addHeader("X-API-Key", API_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesPublicRedirectUnauthenticated() throws Exception {
        ManagementApiKeyFilter filter = filter(API_KEY);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/product123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void remainsDisabledWhenNoKeyIsConfigured() throws Exception {
        ManagementApiKeyFilter filter = filter("");
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/urls/product123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doesNotProtectAnUnrelatedPrefix() throws Exception {
        ManagementApiKeyFilter filter = filter(API_KEY);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/urls-other");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private ManagementApiKeyFilter filter(String apiKey) {
        return new ManagementApiKeyFilter(new SecurityProperties(apiKey, "X-API-Key"));
    }
}
