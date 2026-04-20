package org.example.rentoza.security.network;

import jakarta.servlet.http.HttpServletRequest;
import org.example.rentoza.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TrustedProxyIpExtractor}.
 *
 * Verifies rightmost-trusted XFF parsing, CIDR matching, IP sanitization,
 * and spoof-chain resistance.
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
    // Rightmost-trusted XFF parsing
    // ================================================================

    @Nested
    @DisplayName("Rightmost-trusted XFF parsing")
    class RightmostParsing {

        @Test
        @DisplayName("Single proxy: returns the client IP (leftmost)")
        void singleProxy() {
            when(rateLimit.getTrustedProxies()).thenReturn(Set.of("10.0.0.1"));
            extractor = new TrustedProxyIpExtractor(appProperties);

            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn("10.0.0.1");
            when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 10.0.0.1");

            String ip = extractor.extractClientIp(request);

            assertEquals("203.0.113.50", ip,
                    "With single trusted hop stripped from right, the remaining IP is the client");
        }

        @Test
        @DisplayName("Multi-proxy chain: strips all trusted hops from right")
        void multiProxyChain() {
            when(rateLimit.getTrustedProxies()).thenReturn(Set.of("10.0.0.1", "10.0.0.2"));
            extractor = new TrustedProxyIpExtractor(appProperties);

            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn("10.0.0.2");
            when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 10.0.0.1, 10.0.0.2");

            String ip = extractor.extractClientIp(request);

            assertEquals("203.0.113.50", ip);
        }

        @Test
        @DisplayName("Spoof attack: attacker prepends fake IP — rightmost parsing ignores it")
        void spoofAttackIgnored() {
            when(rateLimit.getTrustedProxies()).thenReturn(Set.of("10.0.0.1"));
            extractor = new TrustedProxyIpExtractor(appProperties);

            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn("10.0.0.1");
            // Attacker sets X-Forwarded-For to "1.1.1.1" before reaching proxy
            // Proxy appends real client: "1.1.1.1, 203.0.113.50, 10.0.0.1"
            when(request.getHeader("X-Forwarded-For")).thenReturn("1.1.1.1, 203.0.113.50, 10.0.0.1");

            String ip = extractor.extractClientIp(request);

            // Rightmost parsing: 10.0.0.1 is trusted → next is 203.0.113.50 (not trusted) → that's the client
            assertEquals("203.0.113.50", ip,
                    "Spoofed leftmost IP must be ignored; rightmost untrusted is the real client");
        }

        @Test
        @DisplayName("All hops trusted: returns leftmost as last resort")
        void allHopsTrusted() {
            when(rateLimit.getTrustedProxies()).thenReturn(Set.of("10.0.0.1", "10.0.0.2", "10.0.0.3"));
            extractor = new TrustedProxyIpExtractor(appProperties);

            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn("10.0.0.3");
            when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2, 10.0.0.3");

            String ip = extractor.extractClientIp(request);

            assertEquals("10.0.0.1", ip, "When all hops are trusted, leftmost is returned");
        }
    }

    // ================================================================
    // Untrusted remote addr
    // ================================================================

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

        assertEquals("10.0.0.1", ip);
    }

    // ================================================================
    // Default trust (no explicit config) — private network ranges
    // ================================================================

    @Nested
    @DisplayName("Default private network trust (CIDR-based)")
    class DefaultTrust {

        @Test
        @DisplayName("Private-network remote addr honours XFF (rightmost)")
        void privateNetworkHonoursXff() {
            when(rateLimit.getTrustedProxies()).thenReturn(Set.of());
            extractor = new TrustedProxyIpExtractor(appProperties);

            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn("192.168.1.1");
            when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10");

            String ip = extractor.extractClientIp(request);

            assertEquals("203.0.113.10", ip,
                    "Private-network remote should be trusted by default");
        }

        @Test
        @DisplayName("Public remote addr ignores XFF")
        void publicRemoteIgnoresXff() {
            when(rateLimit.getTrustedProxies()).thenReturn(Set.of());
            extractor = new TrustedProxyIpExtractor(appProperties);

            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn("203.0.113.99");
            when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");

            String ip = extractor.extractClientIp(request);

            assertEquals("203.0.113.99", ip);
        }

        @Test
        @DisplayName("Loopback 127.x.x.x is trusted by default")
        void loopbackTrustedByDefault() {
            when(rateLimit.getTrustedProxies()).thenReturn(Set.of());
            extractor = new TrustedProxyIpExtractor(appProperties);

            assertTrue(new TrustedProxyIpExtractor(appProperties).isTrustedProxy("127.0.0.1"));
            assertTrue(new TrustedProxyIpExtractor(appProperties).isTrustedProxy("127.10.20.30"));
        }
    }

    // ================================================================
    // CIDR matching
    // ================================================================

    @Nested
    @DisplayName("CIDR support")
    class CidrSupport {

        @Test
        @DisplayName("Configured CIDR matches IPs within range")
        void cidrMatches() {
            when(rateLimit.getTrustedProxies()).thenReturn(Set.of("35.191.0.0/16"));
            extractor = new TrustedProxyIpExtractor(appProperties);

            assertTrue(extractor.isTrustedProxy("35.191.1.1"));
            assertTrue(extractor.isTrustedProxy("35.191.255.255"));
            assertFalse(extractor.isTrustedProxy("35.192.0.1"));
        }

        @Test
        @DisplayName("Mixed exact + CIDR config works")
        void mixedExactAndCidr() {
            when(rateLimit.getTrustedProxies()).thenReturn(Set.of("10.0.0.1", "172.16.0.0/12"));
            extractor = new TrustedProxyIpExtractor(appProperties);

            assertTrue(extractor.isTrustedProxy("10.0.0.1"));
            assertFalse(extractor.isTrustedProxy("10.0.0.2"));
            assertTrue(extractor.isTrustedProxy("172.20.5.6"));
            assertFalse(extractor.isTrustedProxy("172.32.0.1"));
        }

        @Test
        @DisplayName("isInCidr handles invalid input gracefully")
        void cidrInvalidInput() {
            assertFalse(TrustedProxyIpExtractor.isInCidr("not-an-ip", "10.0.0.0/8"));
            assertFalse(TrustedProxyIpExtractor.isInCidr("10.0.0.1", "bad-cidr"));
            assertFalse(TrustedProxyIpExtractor.isInCidr("10.0.0.1", "10.0.0.0"));
        }

        @Test
        @DisplayName("isInCidr rejects out-of-range prefix lengths")
        void cidrRejectsOutOfRangePrefix() {
            // Negative prefix should NOT match
            assertFalse(TrustedProxyIpExtractor.isInCidr("10.0.0.1", "10.0.0.0/-1"));
            // Prefix > 32 for IPv4
            assertFalse(TrustedProxyIpExtractor.isInCidr("10.0.0.1", "10.0.0.0/33"));
            // Boundary: /0 and /32 are valid
            assertTrue(TrustedProxyIpExtractor.isInCidr("10.0.0.1", "0.0.0.0/0"));
            assertTrue(TrustedProxyIpExtractor.isInCidr("10.0.0.1", "10.0.0.1/32"));
        }
    }

    // ================================================================
    // IP sanitization
    // ================================================================

    @Nested
    @DisplayName("IP sanitization")
    class Sanitization {

        @Test
        @DisplayName("Null/blank IP returns UNKNOWN")
        void nullReturnsUnknown() {
            assertEquals("UNKNOWN", TrustedProxyIpExtractor.sanitizeIp(null));
            assertEquals("UNKNOWN", TrustedProxyIpExtractor.sanitizeIp(""));
            assertEquals("UNKNOWN", TrustedProxyIpExtractor.sanitizeIp("   "));
        }

        @Test
        @DisplayName("Overlong IP is truncated to 45 chars")
        void overlongTruncated() {
            String longIp = "a".repeat(100);
            String result = TrustedProxyIpExtractor.sanitizeIp(longIp);
            assertEquals(45, result.length());
        }

        @Test
        @DisplayName("Non-printable chars are stripped")
        void nonPrintableStripped() {
            String result = TrustedProxyIpExtractor.sanitizeIp("10.0\u0000.0.1");
            assertEquals("10.0.0.1", result);
        }
    }
}
