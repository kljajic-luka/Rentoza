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

    public Cors getCors() {
        return cors;
    }

    public Cookie getCookie() {
        return cookie;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /**
     * CORS configuration properties
     */
    public static class Cors {
        /**
         * Allowed origins for CORS requests (comma-separated).
         * Example: "https://rentoza.rs,https://www.rentoza.rs"
         */
        private String allowedOrigins = "http://localhost:4200";
        //FOR DEVELOPMENT CHECK ON OTHER DEVICES
        //private String allowedOrigins = "http://*:4200";

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
}
