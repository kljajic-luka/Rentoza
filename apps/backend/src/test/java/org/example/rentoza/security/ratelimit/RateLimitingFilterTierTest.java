package org.example.rentoza.security.ratelimit;

import jakarta.servlet.ServletException;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.deprecated.jwt.JwtUtil;
import org.example.rentoza.security.InternalServiceJwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for B2: rate-limit tier classification and fail-closed behavior
 * on Redis outage for critical endpoints.
 */
class RateLimitingFilterTierTest {

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

    // ── Tier classification tests ─────────────────────────────────────────

    @ParameterizedTest(name = "B2: {0} {1} → CRITICAL")
    @CsvSource({
        "POST, /api/auth/login",
        "POST, /api/auth/register",
        "POST, /api/auth/refresh",
        "POST, /api/auth/supabase/login",
        "POST, /api/auth/supabase/register",
        "POST, /api/auth/supabase/refresh",
        "POST, /api/payments/bookings/42/reauthorize",
        "POST, /api/bookings",
        "GET,  /api/payments/bookings/42/status"
    })
    void criticalEndpoints_resolveAsCritical(String method, String path) {
        assertThat(RateLimitingFilter.resolveRateLimitTier(path.trim(), method.trim()))
                .isEqualTo(RateLimitTier.CRITICAL);
    }

    @ParameterizedTest(name = "B2: {0} {1} → STANDARD")
    @CsvSource({
        "GET,  /api/cars",
        "GET,  /api/cars/search",
        "GET,  /api/bookings/me",
        "GET,  /api/reviews/car/5",
        "PUT,  /api/bookings/cancel/42"
    })
    void standardEndpoints_resolveAsStandard(String method, String path) {
        assertThat(RateLimitingFilter.resolveRateLimitTier(path.trim(), method.trim()))
                .isEqualTo(RateLimitTier.STANDARD);
    }

    // ── Redis-down behavior tests ─────────────────────────────────────────

    @Test
    @DisplayName("B2: Redis down on login (CRITICAL) → filter returns 503")
    void givenRedisDownOnLoginEndpoint_filterReturns503() throws ServletException, IOException {
        // Simulate Redis failure: tier-aware method returns false, remaining returns 0
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt(), eq(RateLimitTier.CRITICAL)))
                .thenReturn(false);
        when(rateLimitService.getRemainingSeconds(anyString())).thenReturn(0L);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/supabase/login");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("SERVICE_TEMPORARILY_UNAVAILABLE");
    }

    @Test
    @DisplayName("B2: Redis down on payment (CRITICAL) → filter returns 503")
    void givenRedisDownOnPaymentEndpoint_filterReturns503() throws ServletException, IOException {
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt(), eq(RateLimitTier.CRITICAL)))
                .thenReturn(false);
        when(rateLimitService.getRemainingSeconds(anyString())).thenReturn(0L);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments/bookings/1/reauthorize");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("SERVICE_TEMPORARILY_UNAVAILABLE");
    }

    @Test
    @DisplayName("B2: Redis down on GET /api/cars (STANDARD) → request is allowed through")
    void givenRedisDownOnStandardEndpoint_requestIsAllowed() throws ServletException, IOException {
        // Standard tier: allowRequest returns true even when Redis is down (fail-open default)
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt(), eq(RateLimitTier.STANDARD)))
                .thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cars");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // Filter chain continued → request was allowed
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("B2: Redis healthy on CRITICAL endpoint → normal bucket check applies")
    void givenRedisHealthy_criticalEndpoint_normalBucketLogicApplies() throws ServletException, IOException {
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt(), eq(RateLimitTier.CRITICAL)))
                .thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bookings");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verify(rateLimitService).allowRequest(anyString(), eq(100), eq(60), eq(RateLimitTier.CRITICAL));
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
