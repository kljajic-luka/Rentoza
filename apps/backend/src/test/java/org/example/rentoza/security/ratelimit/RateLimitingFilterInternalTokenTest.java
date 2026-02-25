package org.example.rentoza.security.ratelimit;

import jakarta.servlet.ServletException;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.deprecated.jwt.JwtUtil;
import org.example.rentoza.security.InternalServiceJwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for B1: X-Internal-Service-Token validation in RateLimitingFilter.
 * Verifies that only cryptographically valid internal tokens bypass rate limiting.
 */
class RateLimitingFilterInternalTokenTest {

    private RateLimitService rateLimitService;
    private JwtUtil jwtUtil;
    private InternalServiceJwtUtil internalServiceJwtUtil;
    private AppProperties appProperties;
    private RateLimitingFilter filter;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        jwtUtil = mock(JwtUtil.class);
        internalServiceJwtUtil = mock(InternalServiceJwtUtil.class);

        appProperties = new AppProperties();
        appProperties.getRateLimit().setEnabled(true);
        appProperties.getRateLimit().setDefaultLimit(100);
        appProperties.getRateLimit().setDefaultWindowSeconds(60);

        filter = new RateLimitingFilter(rateLimitService, appProperties, jwtUtil, internalServiceJwtUtil);
    }

    @Test
    @DisplayName("B1: No internal token header — rate limit is applied normally")
    void givenNoInternalToken_rateLimitIsApplied() throws ServletException, IOException {
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bookings");
        request.setRemoteAddr("10.0.0.1");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(internalServiceJwtUtil, never()).validateServiceToken(anyString());
        verify(rateLimitService).allowRequest(anyString(), eq(100), eq(60));
    }

    @Test
    @DisplayName("B1: Valid internal token — rate limit is bypassed")
    void givenValidInternalToken_rateLimitIsBypassed() throws ServletException, IOException {
        when(internalServiceJwtUtil.validateServiceToken("valid-jwt-token")).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bookings");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Internal-Service-Token", "valid-jwt-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(internalServiceJwtUtil).validateServiceToken("valid-jwt-token");
        verify(rateLimitService, never()).allowRequest(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("B1: Invalid internal token — rate limit is applied normally (not bypassed)")
    void givenInvalidInternalToken_rateLimitIsAppliedNormally() throws ServletException, IOException {
        when(internalServiceJwtUtil.validateServiceToken("bad-token")).thenReturn(false);
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bookings");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Internal-Service-Token", "bad-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(internalServiceJwtUtil).validateServiceToken("bad-token");
        verify(rateLimitService).allowRequest(anyString(), eq(100), eq(60));
    }

    @Test
    @DisplayName("B1: Spoofed non-empty token 'anything' — no bypass, rate limit proceeds")
    void givenSpoofedNonEmptyToken_noBypassOccurs() throws ServletException, IOException {
        when(internalServiceJwtUtil.validateServiceToken("anything")).thenReturn(false);
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cars");
        request.setRemoteAddr("203.0.113.50");
        request.addHeader("X-Internal-Service-Token", "anything");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(internalServiceJwtUtil).validateServiceToken("anything");
        verify(rateLimitService).allowRequest(startsWith("ip:"), eq(100), eq(60));
    }

    @Test
    @DisplayName("B1: Empty internal token header — treated as absent, rate limit applied")
    void givenEmptyInternalToken_rateLimitIsApplied() throws ServletException, IOException {
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bookings");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Internal-Service-Token", "");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(internalServiceJwtUtil, never()).validateServiceToken(anyString());
        verify(rateLimitService).allowRequest(anyString(), eq(100), eq(60));
    }
}
