package org.example.rentoza.security.network;

import jakarta.servlet.http.HttpServletRequest;
import org.example.rentoza.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Shared trusted-proxy IP extraction utility.
 *
 * <p>Validates X-Forwarded-For headers against a trusted proxy list before using them.
 * Prevents IP spoofing attacks where malicious clients set fake X-Forwarded-For headers.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@code EnhancedAuthController} — consent IP capture during owner registration</li>
 *   <li>{@code ProfileCompletionService} — consent IP capture during profile completion</li>
 *   <li>{@code RateLimitingFilter} — IP-based rate limiting</li>
 * </ul>
 *
 * <p>Trust hierarchy:
 * <ol>
 *   <li>If {@code app.rate-limit.trusted-proxies} is configured, use that list</li>
 *   <li>Otherwise, trust common private network ranges (development-friendly default)</li>
 * </ol>
 *
 * @since Phase 2 — 02-02 Consent IP hardening
 */
@Component
public class TrustedProxyIpExtractor {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxyIpExtractor.class);

    /**
     * Common private network CIDR ranges.
     * Automatically trusted when no explicit trustedProxies are configured.
     */
    private static final List<String> DEFAULT_TRUSTED_NETWORKS = List.of(
            "127.0.0.1",        // Loopback
            "::1",              // IPv6 loopback
            "10.",              // Class A private (10.0.0.0/8)
            "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.",
            "172.24.", "172.25.", "172.26.", "172.27.",
            "172.28.", "172.29.", "172.30.", "172.31.", // Class B private (172.16.0.0/12)
            "192.168."          // Class C private (192.168.0.0/16)
    );

    private final AppProperties appProperties;

    public TrustedProxyIpExtractor(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * Extract the real client IP address with X-Forwarded-For validation.
     *
     * <p>Only trusts X-Forwarded-For when the direct connection (remoteAddr) is from
     * a trusted proxy. This prevents IP spoofing via forged headers.
     *
     * @param request The HTTP request
     * @return The validated client IP address
     */
    public String extractClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // Only trust X-Forwarded-For if the direct connection is from a trusted proxy
        if (isTrustedProxy(remoteAddr)) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                // X-Forwarded-For: client, proxy1, proxy2
                // Take first IP (original client)
                String clientIp = xForwardedFor.split(",")[0].trim();
                log.debug("Trusted proxy {} forwarding request from client {}", remoteAddr, clientIp);
                return clientIp;
            }
        } else {
            // Log if untrusted source tries to use X-Forwarded-For
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                log.warn("Ignoring X-Forwarded-For from untrusted source: {}. Header value: {}",
                        remoteAddr, xForwardedFor);
            }
        }

        // Use direct connection IP
        return remoteAddr;
    }

    /**
     * Check if an IP address is from a trusted proxy.
     *
     * @param ipAddress The IP to check
     * @return true if trusted
     */
    boolean isTrustedProxy(String ipAddress) {
        if (ipAddress == null) {
            return false;
        }

        Set<String> configuredProxies = appProperties.getRateLimit().getTrustedProxies();

        // If explicit proxies are configured, use them
        if (configuredProxies != null && !configuredProxies.isEmpty()) {
            return configuredProxies.contains(ipAddress);
        }

        // No explicit config — trust private network ranges (dev-friendly default)
        for (String prefix : DEFAULT_TRUSTED_NETWORKS) {
            if (ipAddress.startsWith(prefix) || ipAddress.equals(prefix)) {
                return true;
            }
        }

        return false;
    }
}
