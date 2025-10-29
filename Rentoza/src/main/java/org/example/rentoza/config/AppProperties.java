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

    public Cors getCors() {
        return cors;
    }

    public Cookie getCookie() {
        return cookie;
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
        private String sameSite = "Strict";

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
}
