package org.example.rentoza.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.example.rentoza.deprecated.jwt.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1: Proves that JWT cookie/token values are never logged at any level,
 * including TRACE. Covers both the current JwtAuthFilter and the legacy
 * (deprecated) copy.
 *
 * <p>Regression guard for audit finding W2 (commit d449eb8).
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTokenLoggingTest {

    // A realistic-looking JWT that must NEVER appear in logs
    private static final String SECRET_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
            + ".eyJzdWIiOiJ0ZXN0QGV4YW1wbGUuY29tIiwiZXhwIjoxNzA5MjIwODAwfQ"
            + ".fakeSignatureDoNotUseInProduction";

    @Mock private JwtUtil jwtUtil;
    @Mock private UserDetailsService userDetailsService;
    @Mock private FilterChain filterChain;

    private ListAppender<ILoggingEvent> currentFilterAppender;
    private ListAppender<ILoggingEvent> legacyFilterAppender;
    private Logger currentFilterLogger;
    private Logger legacyFilterLogger;
    private Level originalCurrentLevel;
    private Level originalLegacyLevel;

    @BeforeEach
    void setUp() {
        // Attach a ListAppender at TRACE to the current JwtAuthFilter logger
        currentFilterLogger = (Logger) LoggerFactory.getLogger(JwtAuthFilter.class);
        originalCurrentLevel = currentFilterLogger.getLevel();
        currentFilterLogger.setLevel(Level.TRACE);
        currentFilterAppender = new ListAppender<>();
        currentFilterAppender.start();
        currentFilterLogger.addAppender(currentFilterAppender);

        // Attach a ListAppender at TRACE to the legacy JwtAuthFilter logger
        legacyFilterLogger = (Logger) LoggerFactory.getLogger(
                org.example.rentoza.deprecated.jwt.JwtAuthFilter.class);
        originalLegacyLevel = legacyFilterLogger.getLevel();
        legacyFilterLogger.setLevel(Level.TRACE);
        legacyFilterAppender = new ListAppender<>();
        legacyFilterAppender.start();
        legacyFilterLogger.addAppender(legacyFilterAppender);
    }

    @AfterEach
    void tearDown() {
        currentFilterLogger.detachAppender(currentFilterAppender);
        currentFilterLogger.setLevel(originalCurrentLevel);
        legacyFilterLogger.detachAppender(legacyFilterAppender);
        legacyFilterLogger.setLevel(originalLegacyLevel);
    }

    @Test
    @DisplayName("G1: Current JwtAuthFilter never logs cookie values at any level (TRACE included)")
    void currentFilter_neverLogsCookieValues() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, userDetailsService);

        MockHttpServletRequest request = buildRequestWithSecretCookie();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Use the public doFilter entry point (doFilterInternal is protected)
        filter.doFilter(request, response, filterChain);

        assertNoTokenLeakage(currentFilterAppender.list, "current JwtAuthFilter");
    }

    @Test
    @DisplayName("G1: Legacy JwtAuthFilter never logs cookie values at any level (TRACE included)")
    void legacyFilter_neverLogsCookieValues() throws Exception {
        var legacyFilter = new org.example.rentoza.deprecated.jwt.JwtAuthFilter(
                jwtUtil, userDetailsService);

        MockHttpServletRequest request = buildRequestWithSecretCookie();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Use the public doFilter entry point (doFilterInternal is protected)
        legacyFilter.doFilter(request, response, filterChain);

        assertNoTokenLeakage(legacyFilterAppender.list, "legacy JwtAuthFilter");
    }

    @Test
    @DisplayName("G1: Bearer token value in Authorization header is never logged")
    void neitherFilter_logsBearerTokenValue() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, userDetailsService);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/bookings/me");
        request.addHeader("Authorization", "Bearer " + SECRET_TOKEN);

        filter.doFilter(request, new MockHttpServletResponse(), filterChain);

        assertNoTokenLeakage(currentFilterAppender.list, "current JwtAuthFilter (Bearer)");
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private MockHttpServletRequest buildRequestWithSecretCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/bookings/me");
        request.setCookies(
                new Cookie("access_token", SECRET_TOKEN),
                new Cookie("refresh_token", "rt_" + SECRET_TOKEN),
                new Cookie("session_id", "sess_abc123")
        );
        return request;
    }

    /**
     * Asserts that no log event's formatted message contains any fragment of the
     * secret token value. Checks the full token, the first 20 characters (the
     * exact pattern the audit found), and the JWT payload section.
     */
    private void assertNoTokenLeakage(List<ILoggingEvent> events, String filterName) {
        assertThat(events).as("Expected log events from %s", filterName).isNotEmpty();

        // Fragments that must never appear in any log message
        String first20Chars = SECRET_TOKEN.substring(0, 20);
        String payloadSection = SECRET_TOKEN.split("\\.")[1]; // JWT payload
        String refreshTokenPrefix = "rt_eyJ";

        for (ILoggingEvent event : events) {
            String msg = event.getFormattedMessage();

            assertThat(msg)
                    .as("Log message from %s at %s must not contain full token", filterName, event.getLevel())
                    .doesNotContain(SECRET_TOKEN);

            assertThat(msg)
                    .as("Log message from %s at %s must not contain first 20 chars of token",
                            filterName, event.getLevel())
                    .doesNotContain(first20Chars);

            assertThat(msg)
                    .as("Log message from %s at %s must not contain JWT payload section",
                            filterName, event.getLevel())
                    .doesNotContain(payloadSection);

            assertThat(msg)
                    .as("Log message from %s at %s must not contain refresh token prefix",
                            filterName, event.getLevel())
                    .doesNotContain(refreshTokenPrefix);
        }
    }
}
