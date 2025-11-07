package org.example.rentoza.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.Map;

/**
 * Utility for generating and validating internal service-to-service JWT tokens.
 * These tokens are used for secure communication between microservices.
 */
@Component
@Slf4j
public class InternalServiceJwtUtil {

    private final Key key;
    private final long expirationMs;

    public InternalServiceJwtUtil(
            @Value("${internal.service.jwt.secret}") String secret,
            @Value("${internal.service.jwt.expiration:3600000}") long expirationMs // Default 1 hour
    ) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
        log.info("InternalServiceJwtUtil initialized with expiration: {}ms", expirationMs);
    }

    /**
     * Generate an internal service token
     *
     * @param serviceName The name of the service (e.g., "chat-service")
     * @return JWT token string
     */
    public String generateServiceToken(String serviceName) {
        return Jwts.builder()
                .setClaims(Map.of(
                        "service", serviceName,
                        "type", "INTERNAL_SERVICE"
                ))
                .setSubject(serviceName)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Validate an internal service token
     *
     * @param token The JWT token to validate
     * @return true if valid, false otherwise
     */
    public boolean validateServiceToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // Verify it's an internal service token
            String type = claims.get("type", String.class);
            if (!"INTERNAL_SERVICE".equals(type)) {
                log.warn("Token type mismatch. Expected INTERNAL_SERVICE, got: {}", type);
                return false;
            }

            log.debug("Valid internal service token for: {}", claims.getSubject());
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("Internal service JWT expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("Unsupported internal service JWT: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("Malformed internal service JWT: {}", e.getMessage());
        } catch (SignatureException e) {
            log.warn("Invalid internal service JWT signature: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Empty or null internal service token");
        }
        return false;
    }

    /**
     * Extract service name from token
     *
     * @param token The JWT token
     * @return Service name
     */
    public String getServiceNameFromToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims.get("service", String.class);
        } catch (JwtException | IllegalArgumentException e) {
            log.error("Error extracting service name from token: {}", e.getMessage());
            return null;
        }
    }
}
