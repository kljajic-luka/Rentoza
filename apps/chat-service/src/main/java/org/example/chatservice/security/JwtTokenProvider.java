package org.example.chatservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.function.Function;

@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secret;

    private SecretKey key;

    @PostConstruct
    public void init() {
        // CRITICAL FIX: Decode BASE64 secret to match main service JWT signing
        // The main service uses Decoders.BASE64.decode(secret), so we must do the same
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    public String extractUserId(String token) {
        // Try to extract userId from claims first (new tokens)
        try {
            Claims claims = extractAllClaims(token);
            String userId = claims.get("userId", String.class);
            if (userId != null) {
                return userId;
            }
        } catch (Exception e) {
            // Fall through to subject extraction
        }
        
        // Fallback to subject for backward compatibility (old tokens with email)
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token.trim())
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return !isTokenExpired(claims);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTokenExpired(Claims claims) {
        return claims.getExpiration().before(new Date());
    }
}
