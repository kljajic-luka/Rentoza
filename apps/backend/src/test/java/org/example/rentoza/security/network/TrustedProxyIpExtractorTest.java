package org.example.rentoza.security.network;

import jakarta.servlet.http.HttpServletRequest;
import org.example.rentoza.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TrustedProxyIpExtractor}.
 *
 * Verifies that X-Forwarded-For is only honoured when the direct connection
 * comes from a trusted proxy, preventing IP spoofing in consent records and
 * rate limiting.
 */
class TrustedProxyIpExtractorTest {

    private AppProperties appProperties;
    private AppProperties.RateLimit rateLimit;
    private TrustedProxyIpExtractor extractor;

    @BeforeEach
    void setUp() {
        appProperties = mock(AppProperties.class);
        rateLimit = mock(AppProperties.RateLimit.class);
        when(appProperties.getRateLimit()).thenReturn(rateLimit);
    }

    // ================================================================
    // Explicit trusted-proxy list configured
    // ================================================================

    @Test
    @DisplayName("Trusted proxy uses first X-Forwarded-For IP")
    void trustedProxyUsesXForwardedFor() {
        when(rateLimit.getTrustedProxies()).thenReturn(Set.of("10.0.0.1"));
        extractor = new TrustedProxyIpExtractor(appProperties);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 10.0.0.1");

        String ip = extractor.extractClientIp(request);

        assertEquals("203.0.113.50", ip,
                "Should return the first (original client) IP from X-Forwarded-For");
    }

    @Test
    @DisplayName("Untrusted remote ignores X-Forwarded-For")
    void untrustedRemoteIgnoresXForwardedFor() {
        when(rateLimit.getTrustedProxies()).thenReturn(Set.of("10.0.0.1"));
        extractor = new TrustedProxyIpExtractor(appProperties);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.99");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50");

        String ip = extractor.extractClientIp(request);

        assertEquals("198.51.100.99", ip,
                "Should return remoteAddr when source is not a trusted proxy");
    }

    @Test
    @DisplayName("Trusted proxy without X-Forwarded-For falls back to remoteAddr")
    void missingXffFallsBackToRemoteAddr() {
        when(rateLimit.getTrustedProxies()).thenReturn(Set.of("10.0.0.1"));
        extractor = new TrustedProxyIpExtractor(appProperties);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        String ip = extractor.extractClientIp(request);

        assertEquals("10.0.0.1", ip,
                "Should return remoteAddr when XFF header is absent");
    }

    // ================================================================
    // No explicit trusted-proxy list — default private network ranges
    // ================================================================

    @Test
    @DisplayName("Default trust: private-network remote addr honours XFF")
    void defaultTrustPrivateNetworkHonoursXff() {
        when(rateLimit.getTrustedProxies()).thenReturn(Set.of()); // empty = use defaults
        extractor = new TrustedProxyIpExtractor(appProperties);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10");

        String ip = extractor.extractClientIp(request);

        assertEquals("203.0.113.10", ip,
                "Private-network remote should be trusted by default");
    }

    @Test
    @DisplayName("Default trust: public remote addr ignores XFF")
    void defaultTrustPublicRemoteIgnoresXff() {
        when(rateLimit.getTrustedProxies()).thenReturn(Set.of()); // empty = use defaults
        extractor = new TrustedProxyIpExtractor(appProperties);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.99");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");

        String ip = extractor.extractClientIp(request);

        assertEquals("203.0.113.99", ip,
                "Public remote addr should not be trusted by default");
    }
}
