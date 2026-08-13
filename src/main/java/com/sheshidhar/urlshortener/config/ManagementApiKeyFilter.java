package com.sheshidhar.urlshortener.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ManagementApiKeyFilter extends OncePerRequestFilter {

    private static final String MANAGEMENT_API_PREFIX = "/api/v1/urls";

    private final SecurityProperties securityProperties;

    public ManagementApiKeyFilter(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        return !securityProperties.managementApiKeyEnabled()
                || "OPTIONS".equals(request.getMethod())
                || !(requestUri.equals(MANAGEMENT_API_PREFIX) || requestUri.startsWith(MANAGEMENT_API_PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String suppliedKey = request.getHeader(securityProperties.managementApiKeyHeader());
        if (keysMatch(securityProperties.managementApiKey(), suppliedKey)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"url-shortener-management\"");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"urn:problem:url-shortener:unauthorized","title":"Unauthorized","status":401,"detail":"A valid management API key is required","code":"UNAUTHORIZED"}
                """);
    }

    private boolean keysMatch(String expectedKey, String suppliedKey) {
        if (suppliedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8)
        );
    }
}
