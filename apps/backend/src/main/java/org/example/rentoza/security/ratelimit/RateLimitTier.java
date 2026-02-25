package org.example.rentoza.security.ratelimit;

/**
 * Classification of endpoint criticality for rate-limit failure behavior.
 *
 * <ul>
 *   <li>{@link #CRITICAL} — auth, payment, booking-mutation endpoints.
 *       On Redis failure the request is <strong>blocked</strong> (fail-closed, 503).</li>
 *   <li>{@link #STANDARD} — read-only browsing endpoints.
 *       On Redis failure the request is <strong>allowed</strong> (fail-open).</li>
 * </ul>
 *
 * @since Security Audit Hardening — B2
 */
public enum RateLimitTier {
    /** Auth, payment, and booking-mutation endpoints: fail-closed on Redis outage. */
    CRITICAL,
    /** Read-only / non-financial endpoints: fail-open on Redis outage. */
    STANDARD
}
