package org.example.rentoza.deprecated.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Deprecated(since = "2.1.0", forRemoval = true)
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    private final Key key;
    private final long expirationMs;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expirationMs
    ) {
        // decode the base64 secret key for extra safety
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
    }

    // =====================================================
    // 🔐 TOKEN CREATION
    // =====================================================

    public String generateToken(String email, String role) {
        return Jwts.builder()
                .setClaims(Map.of("role", role))
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // Enhanced method with userId included
    public String generateToken(String email, String role, Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);

        String userIdClaim;
        if (userId != null) {
            userIdClaim = userId.toString();
        } else {
            userIdClaim = "oidc-temp-" + email;
            log.debug("Generating JWT for OAuth2 transient user: {}", email);
        }
        claims.put("userId", userIdClaim);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // Simplified method for backward compatibility (no role param)
    public String generateToken(String email) {
        return generateToken(email, "USER");
    }

    // =====================================================
    // 🧾 CLAIMS EXTRACTION HELPERS
    // =====================================================

    public String getEmailFromToken(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String getUserIdFromToken(String token) {
        return extractClaim(token, claims -> claims.get("userId", String.class));
    }

    public String getRoleFromToken(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    public Date getExpirationDateFromToken(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            throw new RuntimeException("Token expired");
        } catch (JwtException | IllegalArgumentException e) {
            throw new RuntimeException("Invalid token");
        }
    }

    // =====================================================
    // 🧩 VALIDATION
    // =====================================================

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (ExpiredJwtException e) {
            // Expected error - use debug level, don't expose details
            log.debug("JWT expired: {}", e.getMessage());
            return false;
        } catch (UnsupportedJwtException | MalformedJwtException | SignatureException e) {
            // Suspicious activity - log warning without exposing implementation details
            log.warn("Invalid JWT token: {}", e.getClass().getSimpleName());
            return false;
        } catch (IllegalArgumentException e) {
            // Expected error for empty/null tokens
            log.debug("Empty or null JWT token");
            return false;
        }
    }

    // =====================================================
    // 🔁 REFRESH TOKEN
    // =====================================================

    public String refreshToken(String oldToken) {
        if (!validateToken(oldToken)) throw new RuntimeException("Invalid or expired token");

        String email = getEmailFromToken(oldToken);
        String role = getRoleFromToken(oldToken);
        return generateToken(email, role);
    }
}
