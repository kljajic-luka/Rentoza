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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RateLimitingFilterAuthEndpointsTest {

    private RateLimitService rateLimitService;
    private JwtUtil jwtUtil;
    private InternalServiceJwtUtil internalServiceJwtUtil;
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        jwtUtil = mock(JwtUtil.class);
        internalServiceJwtUtil = mock(InternalServiceJwtUtil.class);

        appProperties = new AppProperties();
        appProperties.getRateLimit().setEnabled(true);
        appProperties.getRateLimit().setDefaultLimit(100);
        appProperties.getRateLimit().setDefaultWindowSeconds(60);

        AppProperties.RateLimit.EndpointLimit login = new AppProperties.RateLimit.EndpointLimit();
        login.setLimit(5);
        login.setWindowSeconds(60);

        AppProperties.RateLimit.EndpointLimit forgot = new AppProperties.RateLimit.EndpointLimit();
        forgot.setLimit(3);
        forgot.setWindowSeconds(300);

        AppProperties.RateLimit.EndpointLimit reset = new AppProperties.RateLimit.EndpointLimit();
        reset.setLimit(5);
        reset.setWindowSeconds(300);

        appProperties.getRateLimit().setEndpoints(Map.of(
                "/api/auth/supabase/login", login,
                "/api/auth/supabase/forgot-password", forgot,
                "/api/auth/supabase/reset-password", reset
        ));
    }

    @Test
    @DisplayName("Applies /login limit 5 per 60 seconds")
    void loginEndpoint_usesConfiguredLimit() throws ServletException, IOException {
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt())).thenReturn(true);

        RateLimitingFilter filter = new RateLimitingFilter(rateLimitService, appProperties, jwtUtil, internalServiceJwtUtil);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/supabase/login");
        request.setRemoteAddr("127.0.0.1");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(rateLimitService).allowRequest(eq("ip:127.0.0.1"), eq(5), eq(60));
    }

    @Test
    @DisplayName("Applies /forgot-password limit 3 per 5 minutes")
    void forgotPasswordEndpoint_usesConfiguredLimit() throws ServletException, IOException {
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt())).thenReturn(true);

        RateLimitingFilter filter = new RateLimitingFilter(rateLimitService, appProperties, jwtUtil, internalServiceJwtUtil);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/supabase/forgot-password");
        request.setRemoteAddr("127.0.0.1");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(rateLimitService).allowRequest(eq("ip:127.0.0.1"), eq(3), eq(300));
    }

    @Test
    @DisplayName("Applies /reset-password limit 5 per 5 minutes")
    void resetPasswordEndpoint_usesConfiguredLimit() throws ServletException, IOException {
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt())).thenReturn(true);

        RateLimitingFilter filter = new RateLimitingFilter(rateLimitService, appProperties, jwtUtil, internalServiceJwtUtil);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/supabase/reset-password");
        request.setRemoteAddr("127.0.0.1");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(rateLimitService).allowRequest(eq("ip:127.0.0.1"), eq(5), eq(300));
    }

    @Test
    @DisplayName("Blocks request when limit is exceeded")
    void exceededLimit_throwsRateLimitExceededException() {
        when(rateLimitService.allowRequest(anyString(), anyInt(), anyInt())).thenReturn(false);
        when(rateLimitService.getRemainingSeconds(anyString())).thenReturn(45L);

        RateLimitingFilter filter = new RateLimitingFilter(rateLimitService, appProperties, jwtUtil, internalServiceJwtUtil);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/supabase/login");
        request.setRemoteAddr("127.0.0.1");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain()))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Rate limit exceeded");
    }
}
