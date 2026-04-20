package org.example.rentoza.security.network;

import jakarta.servlet.http.HttpServletRequest;
import org.example.rentoza.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

/**
 * Shared trusted-proxy IP extraction utility.
 *
 * <p>Parses {@code X-Forwarded-For} from the <b>right</b> (trusted side) to prevent
 * client-controlled spoofing. Validates IPs against a trusted proxy list supporting
 * both exact addresses and CIDR notation.
 *
 * <h2>Algorithm (GCLB-safe rightmost strategy)</h2>
 * <ol>
 *   <li>Walk the X-Forwarded-For chain from right to left.</li>
 *   <li>Strip every hop whose IP matches a trusted proxy.</li>
 *   <li>The first non-trusted hop is the real client IP.</li>
 *   <li>If every hop is trusted, use the leftmost hop.</li>
 * </ol>
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
 *   <li>If {@code app.rate-limit.trusted-proxies} is configured (supports exact IPs and CIDR), use that list</li>
 *   <li>Otherwise, trust common private network ranges (development-friendly default)</li>
 * </ol>
 *
 * @since Phase 2 — 02-02 Consent IP hardening
 */
@Component
public class TrustedProxyIpExtractor {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxyIpExtractor.class);

    /** Maximum length of a valid IP address (IPv6 with zone ID). */
    private static final int MAX_IP_LENGTH = 45;

    /**
     * Default trusted CIDR ranges used when no explicit trustedProxies are configured.
     * These cover standard private/loopback networks (development-friendly default).
     */
    private static final List<String> DEFAULT_TRUSTED_CIDRS = List.of(
            "127.0.0.0/8",      // Loopback
            "::1/128",           // IPv6 loopback
            "10.0.0.0/8",       // Class A private
            "172.16.0.0/12",    // Class B private
            "192.168.0.0/16"    // Class C private
    );

    private final AppProperties appProperties;

    public TrustedProxyIpExtractor(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * Extract the real client IP address using rightmost-trusted X-Forwarded-For parsing.
     *
     * <p>Only trusts X-Forwarded-For when the direct connection (remoteAddr) is from
     * a trusted proxy. Walks the header from right to left, stripping trusted proxy hops.
     * The first non-trusted hop is the real client.
     *
     * <p>All returned IPs are sanitized: trimmed, non-printable chars removed, capped at 45 chars.
     *
     * @param request The HTTP request
     * @return The validated and sanitized client IP address
     */
    public String extractClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // Only trust X-Forwarded-For if the direct connection is from a trusted proxy
        if (isTrustedProxy(remoteAddr)) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                String clientIp = extractRightmostUntrusted(xForwardedFor);
                log.debug("Trusted proxy {} forwarding request from client {}", remoteAddr, clientIp);
                return sanitizeIp(clientIp);
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
        return sanitizeIp(remoteAddr);
    }

    /**
     * Parse X-Forwarded-For from right to left, returning the first IP
     * that is NOT a trusted proxy. If all hops are trusted, returns the leftmost.
     *
     * <p>Example: {@code "spoofed, real-client, proxy1, proxy2"}
     * with proxy1 and proxy2 trusted → returns {@code "real-client"}.
     */
    String extractRightmostUntrusted(String xForwardedFor) {
        String[] hops = xForwardedFor.split(",");

        // Walk from right to left
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) continue;
            if (!isTrustedProxy(hop)) {
                return hop;
            }
        }
        // All hops trusted — return leftmost (best effort)
        return hops[0].trim();
    }

    /**
     * Check if an IP address is from a trusted proxy.
     * Supports both exact IP addresses and CIDR notation in configuration.
     *
     * @param ipAddress The IP to check
     * @return true if trusted
     */
    boolean isTrustedProxy(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return false;
        }

        Set<String> configuredProxies = appProperties.getRateLimit().getTrustedProxies();

        // If explicit proxies are configured, use them (supports exact IPs and CIDR)
        if (configuredProxies != null && !configuredProxies.isEmpty()) {
            for (String entry : configuredProxies) {
                if (entry.contains("/")) {
                    // CIDR notation
                    if (isInCidr(ipAddress, entry)) {
                        return true;
                    }
                } else {
                    // Exact IP match
                    if (entry.equals(ipAddress)) {
                        return true;
                    }
                }
            }
            return false;
        }

        // No explicit config — trust private network ranges (dev-friendly default)
        for (String cidr : DEFAULT_TRUSTED_CIDRS) {
            if (isInCidr(ipAddress, cidr)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check whether {@code ip} falls within the given CIDR block.
     *
     * @param ip   the IP address to test (e.g. "10.0.0.5")
     * @param cidr the CIDR block (e.g. "10.0.0.0/8")
     * @return true if ip is within the CIDR range
     */
    static boolean isInCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) return false;

            InetAddress network = InetAddress.getByName(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);

            InetAddress address = InetAddress.getByName(ip);

            byte[] networkBytes = network.getAddress();
            byte[] addressBytes = address.getAddress();

            // IPv4 vs IPv6 mismatch
            if (networkBytes.length != addressBytes.length) return false;

            // Validate prefix range: 0..32 for IPv4, 0..128 for IPv6
            int maxPrefix = networkBytes.length * 8;
            if (prefixLength < 0 || prefixLength > maxPrefix) return false;

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != addressBytes[i]) return false;
            }

            if (remainingBits > 0 && fullBytes < networkBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((networkBytes[fullBytes] & mask) != (addressBytes[fullBytes] & mask)) return false;
            }

            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            log.debug("Invalid CIDR check: ip={}, cidr={}: {}", ip, cidr, e.getMessage());
            return false;
        }
    }

    /**
     * Sanitize an IP address: trim, remove non-printable characters, cap length.
     */
    static String sanitizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "UNKNOWN";
        }
        String sanitized = ip.trim().replaceAll("[^\\x20-\\x7E]", "");
        if (sanitized.isEmpty()) return "UNKNOWN";
        if (sanitized.length() > MAX_IP_LENGTH) {
            sanitized = sanitized.substring(0, MAX_IP_LENGTH);
        }
        return sanitized;
    }
}
