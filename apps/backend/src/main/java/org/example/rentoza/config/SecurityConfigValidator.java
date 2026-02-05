package org.example.rentoza.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * P0-1, P0-2: Validates critical security configuration at application startup.
 * 
 * <p>Fails fast if production profile is active but security settings are misconfigured.
 * This prevents accidental deployment with development/insecure settings.
 * 
 * <h2>Validated Settings</h2>
 * <ul>
 *   <li>JWT secrets are externalized (not hardcoded)</li>
 *   <li>CORS origins are production-safe (no wildcards, HTTPS only)</li>
 *   <li>Cookie security flags are enabled</li>
 * </ul>
 */
@Component
public class SecurityConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfigValidator.class);

    private final Environment environment;
    private final AppProperties appProperties;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${internal.service.jwt.secret:}")
    private String internalJwtSecret;

    @Value("${supabase.jwt-secret:}")
    private String supabaseJwtSecret;

    public SecurityConfigValidator(Environment environment, AppProperties appProperties) {
        this.environment = environment;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void validateSecurityConfiguration() {
        boolean isProduction = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        log.info("=== Security Configuration Validation ===");
        log.info("Active profiles: {}", Arrays.toString(environment.getActiveProfiles()));
        log.info("Production mode: {}", isProduction);

        // Validate JWT secrets
        validateJwtSecrets(isProduction);

        // Validate CORS configuration
        validateCorsConfiguration(isProduction);

        // Validate cookie security
        validateCookieSecurity(isProduction);

        log.info("=== Security Configuration Validation Complete ===");
    }

    private void validateJwtSecrets(boolean isProduction) {
        // Check if secrets look like they're hardcoded (common patterns)
        boolean jwtLooksHardcoded = looksLikeHardcodedSecret(jwtSecret);
        boolean internalJwtLooksHardcoded = looksLikeHardcodedSecret(internalJwtSecret);

        if (isProduction) {
            if (jwtSecret == null || jwtSecret.isBlank()) {
                throw new SecurityConfigurationException(
                    "JWT_SECRET environment variable is not set. " +
                    "Production deployment requires externalized JWT secrets.");
            }

            if (internalJwtSecret == null || internalJwtSecret.isBlank()) {
                throw new SecurityConfigurationException(
                    "INTERNAL_SERVICE_JWT_SECRET environment variable is not set. " +
                    "Production deployment requires externalized JWT secrets.");
            }

            if (supabaseJwtSecret == null || supabaseJwtSecret.isBlank()) {
                throw new SecurityConfigurationException(
                    "SUPABASE_JWT_SECRET environment variable is not set. " +
                    "Production deployment requires Supabase configuration.");
            }

            log.info("✓ JWT secrets: All externalized via environment variables");
        } else {
            // Development mode - warn but don't fail
            if (jwtLooksHardcoded) {
                log.warn("⚠ JWT secret appears to be hardcoded. Use environment variables for production.");
            }
            if (internalJwtLooksHardcoded) {
                log.warn("⚠ Internal service JWT secret appears to be hardcoded. Use environment variables for production.");
            }
            log.info("✓ JWT secrets: Configuration accepted for development");
        }
    }

    private void validateCorsConfiguration(boolean isProduction) {
        String corsOrigins = appProperties.getCors().getAllowedOrigins();
        boolean isSafe = appProperties.getCors().isProductionSafe();

        if (isProduction) {
            if (!isSafe) {
                throw new SecurityConfigurationException(
                    "CORS configuration is not production-safe. " +
                    "Current value: '" + corsOrigins + "'. " +
                    "Production requires HTTPS origins only (no wildcards, no HTTP except localhost).");
            }

            // Additional check: ensure it's not localhost in production
            if (corsOrigins.toLowerCase().contains("localhost")) {
                throw new SecurityConfigurationException(
                    "CORS origins contain 'localhost' which is not valid for production. " +
                    "Set CORS_ORIGINS environment variable to your production domain(s).");
            }

            log.info("✓ CORS origins: {} (production-safe)", corsOrigins);
        } else {
            if (!isSafe) {
                log.warn("⚠ CORS configuration contains insecure settings (wildcards or HTTP). " +
                         "This is only acceptable in development mode.");
            }
            log.info("✓ CORS origins: {} (development mode)", corsOrigins);
        }
    }

    private void validateCookieSecurity(boolean isProduction) {
        boolean secureCookies = appProperties.getCookie().isSecure();
        String sameSite = appProperties.getCookie().getSameSite();

        if (isProduction) {
            if (!secureCookies) {
                throw new SecurityConfigurationException(
                    "Cookie Secure flag is disabled. " +
                    "Production requires app.cookie.secure=true for HTTPS-only cookies.");
            }

            if (!"Strict".equalsIgnoreCase(sameSite) && !"Lax".equalsIgnoreCase(sameSite)) {
                throw new SecurityConfigurationException(
                    "Cookie SameSite is set to '" + sameSite + "'. " +
                    "Production requires 'Strict' or 'Lax' for CSRF protection.");
            }

            log.info("✓ Cookie security: Secure={}, SameSite={}", secureCookies, sameSite);
        } else {
            if (!secureCookies) {
                log.info("ℹ Cookie Secure flag disabled (acceptable for HTTP development)");
            }
            log.info("✓ Cookie security: Secure={}, SameSite={} (development mode)", secureCookies, sameSite);
        }
    }

    /**
     * Heuristic check to detect if a secret looks hardcoded.
     * This is not foolproof but catches common patterns.
     */
    private boolean looksLikeHardcodedSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return false; // Not hardcoded, just missing
        }
        // Common hardcoded secret patterns
        return secret.equals("secret") ||
               secret.equals("changeme") ||
               secret.length() < 32 ||
               secret.startsWith("test") ||
               secret.startsWith("dev");
    }

    /**
     * Exception thrown when security configuration is invalid.
     */
    public static class SecurityConfigurationException extends RuntimeException {
        public SecurityConfigurationException(String message) {
            super("SECURITY CONFIGURATION ERROR: " + message);
        }
    }
}
