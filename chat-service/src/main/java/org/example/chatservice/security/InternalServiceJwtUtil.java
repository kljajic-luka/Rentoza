package org.example.chatservice.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

/**
 * Utility class for generating and validating internal service JWT tokens.
 * These tokens are used for secure service-to-service communication between
 * the chat-service and the main backend service.
 */
@Component
public class InternalServiceJwtUtil {

    private static final Logger logger = LoggerFactory.getLogger(InternalServiceJwtUtil.class);

    private final SecretKey key;
    private final long expirationMs;
    private final String serviceName;

    public InternalServiceJwtUtil(
            @Value("${internal.service.jwt.secret}") String secret,
            @Value("${internal.service.jwt.expiration:3600000}") long expirationMs,
            @Value("${spring.application.name:chat-service}") String serviceName) {
        // Decode the Base64-encoded secret key
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
        this.serviceName = serviceName;
        logger.info("✅ InternalServiceJwtUtil initialized for service: {}", serviceName);
    }

    /**
     * Generate an internal service JWT token.
     * 
     * @return JWT token string
     */
    public String generateServiceToken() {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMs);

        String token = Jwts.builder()
                .claims(Map.of(
                        "service", serviceName,
                        "type", "INTERNAL_SERVICE"
                ))
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();

        logger.debug("Generated internal service token for service: {}, expires: {}", serviceName, expiration);
        return token;
    }

    /**
     * Validate an internal service JWT token.
     * 
     * @param token The JWT token to validate
     * @return true if the token is valid, false otherwise
     */
    public boolean validateServiceToken(String token) {
        try {
            if (token == null || token.isEmpty()) {
                logger.warn("❌ Token validation failed: Token is null or empty");
                return false;
            }

            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Verify this is an internal service token
            String tokenType = claims.get("type", String.class);
            if (!"INTERNAL_SERVICE".equals(tokenType)) {
                logger.warn("❌ Token validation failed: Invalid token type: {}", tokenType);
                return false;
            }

            // Check expiration
            Date expiration = claims.getExpiration();
            if (expiration.before(new Date())) {
                logger.warn("❌ Token validation failed: Token expired at {}", expiration);
                return false;
            }

            String service = claims.get("service", String.class);
            logger.debug("✅ Token validated successfully for service: {}", service);
            return true;

        } catch (JwtException e) {
            logger.error("❌ Token validation failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("❌ Token validation failed: Unexpected error - {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get the service name from a token without full validation.
     * 
     * @param token The JWT token
     * @return The service name, or null if extraction fails
     */
    public String getServiceNameFromToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return claims.get("service", String.class);
        } catch (Exception e) {
            logger.error("Failed to extract service name from token: {}", e.getMessage());
            return null;
        }
    }
}

