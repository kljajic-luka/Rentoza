package org.example.rentoza.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Application-wide configuration properties.
 * Maps values from application.properties with the 'app' prefix.
 */
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Cors cors = new Cors();
    private final Cookie cookie = new Cookie();
    private final RateLimit rateLimit = new RateLimit();
    private final Consent consent = new Consent();

    public Cors getCors() {
        return cors;
    }

    public Cookie getCookie() {
        return cookie;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Consent getConsent() {
        return consent;
    }

    /**
     * CORS configuration properties
     */
    public static class Cors {
        /**
         * Allowed origins for CORS requests (comma-separated).
         * Example: "https://rentoza.rs,https://www.rentoza.rs"
         * 
         * SECURITY: In production, MUST be set via environment variable CORS_ORIGINS.
         * Default is for local development only.
         */
        private String allowedOrigins = "https://localhost:4200";

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        /**
         * Parse comma-separated origins into an array
         */
        public String[] getAllowedOriginsArray() {
            return allowedOrigins.split(",");
        }

        /**
         * P0-2 FIX: Check if CORS contains insecure wildcard or development origins.
         * @return true if configuration is production-safe
         */
        public boolean isProductionSafe() {
            if (allowedOrigins == null || allowedOrigins.isBlank()) {
                return false;
            }
            // Wildcards are never allowed
            if (allowedOrigins.contains("*")) {
                return false;
            }
            // HTTP (non-secure) origins are not allowed in production
            // Exception: localhost for development
            for (String origin : getAllowedOriginsArray()) {
                String trimmed = origin.trim().toLowerCase();
                if (trimmed.startsWith("http://") && !trimmed.contains("localhost")) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Cookie configuration properties
     */
    public static class Cookie {
        /**
         * Whether cookies should use the Secure flag (HTTPS only).
         * Should be true in production, false in local development.
         */
        private boolean secure = false;

        /**
         * Cookie domain (e.g., "rentoza.rs" or "localhost")
         */
        private String domain = "localhost";

        /**
         * SameSite cookie policy (Strict, Lax, or None)
         */
        private String sameSite = "Lax";

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getSameSite() {
            return sameSite;
        }

        public void setSameSite(String sameSite) {
            this.sameSite = sameSite;
        }
    }

    /**
     * Rate limiting configuration properties
     */
    public static class RateLimit {
        /**
         * Whether rate limiting is enabled globally
         */
        private boolean enabled = true;

        /**
         * Redis key prefix for rate limit counters
         */
        private String redisKeyPrefix = "rate_limit";

        /**
         * Default rate limit (requests per window)
         */
        private int defaultLimit = 100;

        /**
         * Default time window in seconds
         */
        private int defaultWindowSeconds = 60;

        /**
         * Endpoint-specific rate limits (map of path -> RateLimitConfig)
         */
        private java.util.Map<String, EndpointLimit> endpoints = new java.util.HashMap<>();

        /**
         * Trusted proxy IP addresses for X-Forwarded-For validation.
         * Only requests from these IPs will honor the X-Forwarded-For header.
         * Leave empty to trust all proxies (NOT RECOMMENDED for production).
         * 
         * Common values:
         * - 127.0.0.1 - Local loopback
         * - 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 - Private networks
         * - Load balancer IPs
         * - CDN edge server IPs
         */
        private java.util.Set<String> trustedProxies = new java.util.HashSet<>();

        public java.util.Set<String> getTrustedProxies() {
            return trustedProxies;
        }

        public void setTrustedProxies(java.util.Set<String> trustedProxies) {
            this.trustedProxies = trustedProxies;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRedisKeyPrefix() {
            return redisKeyPrefix;
        }

        public void setRedisKeyPrefix(String redisKeyPrefix) {
            this.redisKeyPrefix = redisKeyPrefix;
        }

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getDefaultWindowSeconds() {
            return defaultWindowSeconds;
        }

        public void setDefaultWindowSeconds(int defaultWindowSeconds) {
            this.defaultWindowSeconds = defaultWindowSeconds;
        }

        public java.util.Map<String, EndpointLimit> getEndpoints() {
            return endpoints;
        }

        public void setEndpoints(java.util.Map<String, EndpointLimit> endpoints) {
            this.endpoints = endpoints;
        }

        /**
         * Configuration for a specific endpoint's rate limit
         */
        public static class EndpointLimit {
            private int limit;
            private int windowSeconds;

            public int getLimit() {
                return limit;
            }

            public void setLimit(int limit) {
                this.limit = limit;
            }

            public int getWindowSeconds() {
                return windowSeconds;
            }

            public void setWindowSeconds(int windowSeconds) {
                this.windowSeconds = windowSeconds;
            }
        }
    }

    /**
     * Consent policy version tracking configuration.
     * Records which policy version and document hash each user consented to.
     * Used for GDPR/compliance audit trail.
     */
    public static class Consent {
        /**
         * Version identifier of the current consent/terms policy.
         * Format: YYYY-MM-DD-vN (e.g., "2025-01-01-v1").
         * Update when terms of service are revised.
         */
        private String policyVersion = "2025-01-01-v1";

        /**
         * SHA-256 hash of the current consent/terms document.
         * Provides tamper-proof link between user consent and document content.
         * Update when terms of service document changes.
         */
        private String policyHash = "placeholder-update-when-terms-finalized";

        public String getPolicyVersion() {
            return policyVersion;
        }

        public void setPolicyVersion(String policyVersion) {
            this.policyVersion = policyVersion;
        }

        public String getPolicyHash() {
            return policyHash;
        }

        public void setPolicyHash(String policyHash) {
            this.policyHash = policyHash;
        }
    }
}
