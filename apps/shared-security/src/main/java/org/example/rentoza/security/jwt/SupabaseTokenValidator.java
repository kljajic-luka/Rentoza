package org.example.rentoza.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Date;

/**
 * SECURITY (M-11): Shared Supabase ES256 JWT token validator.
 * Core validation logic used by both main backend and chat-service.
 * Prevents security drift by centralizing signature verification,
 * issuer/audience checks, and expiry validation.
 *
 * <p>This class is stateless except for the key provider reference.
 * Thread-safe for concurrent use.</p>
 *
 * <h3>Validation Steps:</h3>
 * <ol>
 *   <li>Extract key ID (kid) from JWT header</li>
 *   <li>Fetch corresponding EC public key from JWKS cache</li>
 *   <li>Verify ES256 signature</li>
 *   <li>Validate issuer and audience claims</li>
 *   <li>Check token expiry</li>
 * </ol>
 */
public class SupabaseTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(SupabaseTokenValidator.class);

    private final JwtKeyProvider keyProvider;
    private final String expectedIssuer;
    private final String expectedAudience;

    /**
     * @param keyProvider  JWKS key provider for EC public key lookup
     * @param expectedIssuer  Expected JWT issuer (e.g., "https://xxx.supabase.co/auth/v1")
     * @param expectedAudience  Expected JWT audience (typically "authenticated")
     */
    public SupabaseTokenValidator(JwtKeyProvider keyProvider, String expectedIssuer, String expectedAudience) {
        this.keyProvider = keyProvider;
        this.expectedIssuer = expectedIssuer;
        this.expectedAudience = expectedAudience;
    }

    /**
     * Validate a Supabase JWT token.
     *
     * @param token The raw JWT string
     * @return true if token is valid (signature, issuer, audience, expiry all pass)
     */
    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        try {
            String kid = extractKidFromHeader(token);
            if (kid == null) {
                log.warn("JWT missing 'kid' header field");
                return false;
            }

            ECPublicKey publicKey = keyProvider.getKey(kid);
            if (publicKey == null) {
                log.warn("No public key found for kid={}", kid);
                return false;
            }

            // Use parser-level validation for issuer/audience (cross-version JJWT compatible)
            Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .requireIssuer(expectedIssuer)
                    .requireAudience(expectedAudience)
                    .build()
                    .parseClaimsJws(token);

            // Issuer, audience, and expiry are all validated by the parser
            return true;

        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
            return false;
        } catch (SignatureException e) {
            log.warn("JWT signature verification failed");
            return false;
        } catch (MalformedJwtException e) {
            log.warn("Malformed JWT token");
            return false;
        } catch (Exception e) {
            log.error("JWT validation error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Parse and return JWT claims without validation (for extracting user info after validation).
     *
     * @param token The raw JWT string (must be validated first)
     * @return Claims from the JWT body
     */
    public Claims parseClaims(String token) {
        String kid = extractKidFromHeader(token);
        ECPublicKey publicKey = keyProvider.getKey(kid);

        return Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .requireIssuer(expectedIssuer)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Extract the Supabase auth UID (UUID) from a validated token.
     *
     * @param token The raw JWT string (must be validated first)
     * @return The 'sub' claim as a string UUID
     */
    public String extractSubject(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Extract key ID (kid) from JWT header without validation.
     */
    public static String extractKidFromHeader(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;

            String headerJson = new String(
                    Base64.getUrlDecoder().decode(parts[0]),
                    java.nio.charset.StandardCharsets.UTF_8
            );

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode header = mapper.readTree(headerJson);

            return header.has("kid") ? header.get("kid").asText() : null;
        } catch (Exception e) {
            log.warn("Failed to extract kid from JWT header: {}", e.getMessage());
            return null;
        }
    }
}
