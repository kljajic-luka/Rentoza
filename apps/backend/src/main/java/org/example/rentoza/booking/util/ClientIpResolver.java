package org.example.rentoza.booking.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Utility for extracting the real client IP address from an HTTP request
 * behind Google Cloud Load Balancer (GCLB) and Cloud Run.
 *
 * <h2>Algorithm</h2>
 * <p>GCLB appends the client IP to {@code X-Forwarded-For} as the second-to-last
 * element (the last element is the GCLB frontend IP itself). Parsing from the
 * <b>right</b> prevents spoofing via attacker-controlled left-side entries.
 *
 * <ol>
 *   <li>Read {@code X-Forwarded-For} header</li>
 *   <li>If 2+ comma-separated IPs: use second-to-last (client IP added by GCLB)</li>
 *   <li>If exactly 1 IP: use it (direct connection through single proxy)</li>
 *   <li>Otherwise fall back to {@code request.getRemoteAddr()}</li>
 *   <li>Sanitize: trim, strip non-printable characters, cap at 45 chars (max IPv6)</li>
 * </ol>
 *
 * <p>{@code X-Client-IP} is intentionally <b>not</b> trusted because it is
 * trivially spoofable by any client.
 */
public final class ClientIpResolver {

    private static final int MAX_IP_LENGTH = 45;

    private ClientIpResolver() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Resolve the real client IP address from the request.
     *
     * @param request the current HTTP request
     * @return sanitized client IP, or {@code "UNKNOWN"} if it cannot be determined
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return "UNKNOWN";
        }

        String ip = extractFromXForwardedFor(request);

        if (ip == null) {
            ip = request.getRemoteAddr();
        }

        return sanitize(ip);
    }

    private static String extractFromXForwardedFor(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return null;
        }

        String[] parts = xff.split(",");
        if (parts.length >= 2) {
            // Second-to-last: the IP appended by GCLB (trusted proxy)
            return parts[parts.length - 2].trim();
        }
        // Single IP
        return parts[0].trim();
    }

    private static String sanitize(String ip) {
        if (ip == null || ip.isBlank()) {
            return "UNKNOWN";
        }

        // Remove non-printable characters
        String sanitized = ip.trim().replaceAll("[^\\x20-\\x7E]", "");

        if (sanitized.isEmpty()) {
            return "UNKNOWN";
        }

        // Cap at max IPv6 length
        if (sanitized.length() > MAX_IP_LENGTH) {
            sanitized = sanitized.substring(0, MAX_IP_LENGTH);
        }

        return sanitized;
    }
}
