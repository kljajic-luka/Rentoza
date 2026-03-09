package org.example.rentoza.security.ratelimit;

import jakarta.servlet.ServletException;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.security.InternalServiceJwtUtil;
import org.example.rentoza.security.network.TrustedProxyIpExtractor;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitingFilterCheckInEndpointsTest {

    private RateLimitService rateLimitService;
    private SupabaseJwtUtil supabaseJwtUtil;
    private InternalServiceJwtUtil internalServiceJwtUtil;
    private AppProperties appProperties;
    private TrustedProxyIpExtractor ipExtractor;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        supabaseJwtUtil = mock(SupabaseJwtUtil.class);
        internalServiceJwtUtil = mock(InternalServiceJwtUtil.class);

        appProperties = new AppProperties();
        appProperties.getRateLimit().setEnabled(true);
        appProperties.getRateLimit().setDefaultLimit(100);
        appProperties.getRateLimit().setDefaultWindowSeconds(60);

        AppProperties.RateLimit.EndpointLimit status = new AppProperties.RateLimit.EndpointLimit();
        status.setLimit(30);
        status.setWindowSeconds(60);

        AppProperties.RateLimit.EndpointLimit licenseVerification = new AppProperties.RateLimit.EndpointLimit();
        licenseVerification.setLimit(5);
        licenseVerification.setWindowSeconds(60);

        appProperties.getRateLimit().setEndpoints(Map.of(
                "/api/bookings/*/check-in/status", status,
                "/api/bookings/*/check-in/host/license-verification", licenseVerification
        ));

        ipExtractor = new TrustedProxyIpExtractor(appProperties);
    }

    @Test
    @DisplayName("applies configured limit to booking status polling endpoint")
    void statusEndpoint_usesConfiguredLimit() throws ServletException, IOException {
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt(), any(RateLimitTier.class)))
                .thenReturn(true);

        RateLimitingFilter filter = new RateLimitingFilter(rateLimitService, appProperties, supabaseJwtUtil, internalServiceJwtUtil, ipExtractor);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/bookings/42/check-in/status");
        request.setRemoteAddr("127.0.0.1");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(rateLimitService).allowRequest(eq("ip:127.0.0.1:/api/bookings/*/check-in/status"), eq(30), eq(60), eq(RateLimitTier.STANDARD));
    }

    @Test
    @DisplayName("applies configured limit to host license verification endpoint")
    void licenseVerificationEndpoint_usesConfiguredLimit() throws ServletException, IOException {
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt(), any(RateLimitTier.class)))
                .thenReturn(true);

        RateLimitingFilter filter = new RateLimitingFilter(rateLimitService, appProperties, supabaseJwtUtil, internalServiceJwtUtil, ipExtractor);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bookings/42/check-in/host/license-verification");
        request.setRemoteAddr("127.0.0.1");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(rateLimitService).allowRequest(eq("ip:127.0.0.1:/api/bookings/*/check-in/host/license-verification"), eq(5), eq(60), eq(RateLimitTier.CRITICAL));
    }
}