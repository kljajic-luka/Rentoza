package org.example.rentoza.security.supabase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.*;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.ECParameterSpec;
import java.security.interfaces.ECPublicKey;
import java.security.AlgorithmParameters;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Supabase JWT validation utility with ES256 (Elliptic Curve) support.
 * 
 * <p><b>CRITICAL FIX:</b> Supabase uses ES256 (asymmetric EC) algorithm, NOT HS256 (symmetric HMAC).
 * This class fetches the JWKS (JSON Web Key Set) from Supabase and uses the public key for validation.
 * 
 * <p>Token Structure (Supabase JWT):
 * <ul>
 *   <li>sub: Supabase Auth UUID (user identifier)</li>
 *   <li>aud: "authenticated" for logged-in users</li>
 *   <li>role: "authenticated" or "anon"</li>
 *   <li>email: User's email address</li>
 *   <li>exp: Expiration timestamp</li>
 *   <li>iat: Issued-at timestamp</li>
 * </ul>
 * 
 * @since Phase 2 - Supabase Auth Migration (ES256 Fix)
 */
@Component
public class SupabaseJwtUtil {

    private static final Logger log = LoggerFactory.getLogger(SupabaseJwtUtil.class);

    private final String supabaseUrl;
    private final String jwtSecret; // Retained for JwtUtil consumers; not used for Supabase token validation
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final Retry jwksRetry;
    
    // Cache for JWKS public keys (kid -> PublicKey)
    private final Map<String, PublicKey> publicKeyCache = new ConcurrentHashMap<>();
    private volatile long lastJwksRefresh = 0;
    private static final long JWKS_CACHE_DURATION_MS = 3600000; // 1 hour

    public SupabaseJwtUtil(
            @Value("${supabase.jwt-secret}") String jwtSecret,
            @Value("${supabase.url}") String supabaseUrl,
            @Value("${supabase.jwt.jwks.connect-timeout-ms:3000}") int jwksConnectTimeoutMs,
            @Value("${supabase.jwt.jwks.read-timeout-ms:5000}") int jwksReadTimeoutMs,
            RetryRegistry retryRegistry
    ) {
        this.jwtSecret = jwtSecret;
        this.supabaseUrl = supabaseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(jwksConnectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(jwksReadTimeoutMs));
        this.restTemplate = new RestTemplate(factory);
        this.jwksRetry = retryRegistry.retry("supabaseJwks");
    }

    @PostConstruct
    public void init() {
        log.info("SupabaseJwtUtil initialized for project: {} (ES256 support enabled)", supabaseUrl);
        // Pre-fetch JWKS at startup
        try {
            refreshJwks();
            log.info("JWKS pre-fetched successfully. Cached {} public keys", publicKeyCache.size());
        } catch (Exception e) {
            log.warn("Failed to pre-fetch JWKS at startup (will retry on first request): {}", e.getMessage());
        }
    }

    // =====================================================
    // 🔐 TOKEN VALIDATION
    // =====================================================

    /**
     * Validate a Supabase JWT token using ES256 public key.
     * 
     * @param token JWT token string (without "Bearer " prefix)
     * @return true if token is valid and not expired
     */
    public boolean validateToken(String token) {
        try {
            // Parse header to get algorithm and key ID
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                log.warn("Invalid JWT format");
                return false;
            }
            
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode header = objectMapper.readTree(headerJson);
            String alg = header.has("alg") ? header.get("alg").asText() : "HS256";
            String kid = header.has("kid") ? header.get("kid").asText() : null;
            
            log.debug("Validating JWT with alg={}, kid={}", alg, kid);

            if ("ES256".equals(alg)) {
                return validateEs256Token(token, kid);
            } else {
                log.warn("Rejected non-ES256 Supabase token (alg={})", alg);
                return false;
            }
        } catch (ExpiredJwtException e) {
            log.debug("Supabase JWT expired: {}", e.getMessage());
            return false;
        } catch (UnsupportedJwtException | MalformedJwtException e) {
            log.warn("Invalid Supabase JWT: {}", e.getClass().getSimpleName());
            return false;
        } catch (io.jsonwebtoken.security.SignatureException e) {
            log.warn("Supabase JWT signature validation failed");
            return false;
        } catch (Exception e) {
            log.warn("JWT validation error: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Validate ES256 (Elliptic Curve) signed JWT using JWKS public key.
     */
    private boolean validateEs256Token(String token, String kid) {
        try {
            PublicKey publicKey = getPublicKey(kid);
            if (publicKey == null) {
                log.error("No public key found for kid: {}", kid);
                return false;
            }

            Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .requireIssuer(supabaseUrl + "/auth/v1")
                    .requireAudience("authenticated")
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("ES256 JWT expired");
            return false;
        } catch (Exception e) {
            log.warn("ES256 JWT validation failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Get public key from cache or fetch from JWKS endpoint.
     */
    private PublicKey getPublicKey(String kid) {
        // Check if we need to refresh JWKS
        if (System.currentTimeMillis() - lastJwksRefresh > JWKS_CACHE_DURATION_MS) {
            refreshJwks();
        }
        
        // Get key from cache
        PublicKey key = publicKeyCache.get(kid);
        if (key == null && kid != null) {
            // Key not in cache, force refresh
            log.debug("Key {} not in cache, forcing JWKS refresh", kid);
            refreshJwks();
            key = publicKeyCache.get(kid);
        }
        
        return key;
    }
    
    /**
     * Fetch JWKS from Supabase and cache public keys.
     */
    private synchronized void refreshJwks() {
        try {
            String jwksUrl = supabaseUrl + "/auth/v1/.well-known/jwks.json";
            log.debug("Fetching JWKS from: {}", jwksUrl);
            
            String jwksJson = Retry.decorateSupplier(jwksRetry,
                    () -> restTemplate.getForObject(jwksUrl, String.class)).get();
            JsonNode jwks = objectMapper.readTree(jwksJson);
            JsonNode keys = jwks.get("keys");
            
            if (keys != null && keys.isArray()) {
                for (JsonNode keyNode : keys) {
                    String kid = keyNode.has("kid") ? keyNode.get("kid").asText() : "default";
                    String kty = keyNode.has("kty") ? keyNode.get("kty").asText() : "";
                    
                    if ("EC".equals(kty)) {
                        PublicKey publicKey = parseEcPublicKey(keyNode);
                        if (publicKey != null) {
                            publicKeyCache.put(kid, publicKey);
                            log.debug("Cached EC public key with kid: {}", kid);
                        }
                    }
                }
            }
            
            lastJwksRefresh = System.currentTimeMillis();
            log.info("JWKS refreshed. Total cached keys: {}", publicKeyCache.size());
        } catch (Exception e) {
            log.error("Failed to fetch JWKS: {}", e.getMessage());
        }
    }
    
    /**
     * Parse EC public key from JWK JSON.
     */
    private PublicKey parseEcPublicKey(JsonNode keyNode) {
        try {
            String crv = keyNode.get("crv").asText(); // P-256 for ES256
            String xBase64 = keyNode.get("x").asText();
            String yBase64 = keyNode.get("y").asText();
            
            // Decode coordinates
            byte[] xBytes = Base64.getUrlDecoder().decode(xBase64);
            byte[] yBytes = Base64.getUrlDecoder().decode(yBase64);
            
            BigInteger x = new BigInteger(1, xBytes);
            BigInteger y = new BigInteger(1, yBytes);
            
            // Get EC parameters for P-256 curve
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1")); // P-256 = secp256r1
            ECParameterSpec ecSpec = parameters.getParameterSpec(ECParameterSpec.class);
            
            // Create public key
            ECPoint point = new ECPoint(x, y);
            ECPublicKeySpec pubKeySpec = new ECPublicKeySpec(point, ecSpec);
            
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePublic(pubKeySpec);
        } catch (Exception e) {
            log.error("Failed to parse EC public key: {}", e.getMessage());
            return null;
        }
    }

    // =====================================================
    // 🧾 CLAIMS EXTRACTION
    // =====================================================

    /**
     * Extract the Supabase user UUID from the token.
     * This is the primary user identifier in Supabase Auth.
     * 
     * @param token JWT token string
     * @return Supabase Auth user UUID
     */
    public UUID getSupabaseUserId(String token) {
        String sub = extractClaim(token, Claims::getSubject);
        return sub != null ? UUID.fromString(sub) : null;
    }

    /**
     * Extract the user's email from the token.
     * 
     * @param token JWT token string
     * @return User's email address
     */
    public String getEmailFromToken(String token) {
        return extractClaim(token, claims -> claims.get("email", String.class));
    }

    /**
     * Extract the Supabase role from the token.
     * Typically "authenticated" for logged-in users, "anon" for anonymous.
     * 
     * @param token JWT token string
     * @return Supabase role string
     */
    public String getSupabaseRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    /**
     * Check if the token belongs to an authenticated user (not anonymous).
     * 
     * @param token JWT token string
     * @return true if user is authenticated
     */
    public boolean isAuthenticated(String token) {
        String role = getSupabaseRole(token);
        return "authenticated".equals(role);
    }

    /**
     * Extract the token expiration date.
     * 
     * @param token JWT token string
     * @return Expiration date
     */
    public Date getExpirationDateFromToken(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Extract all claims from the token.
     * 
     * @param token JWT token string
     * @return All JWT claims
     */
    public Claims getAllClaims(String token) {
        return extractAllClaims(token);
    }

    /**
     * Extract a specific claim using a resolver function.
     * 
     * @param token JWT token string
     * @param claimsResolver Function to extract the desired claim
     * @return The extracted claim value
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claims != null ? claimsResolver.apply(claims) : null;
    }

    private Claims extractAllClaims(String token) {
        try {
            // Parse header to determine algorithm
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;

            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode header = objectMapper.readTree(headerJson);
            String kid = header.has("kid") ? header.get("kid").asText() : null;

            PublicKey publicKey = getPublicKey(kid);
            if (publicKey == null) {
                log.error("SECURITY: No public key found for kid={}. Rejecting token.", kid);
                return null;
            }

            return Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            log.debug("Supabase JWT expired at {}", e.getClaims().getExpiration());
            return null;
        } catch (Exception e) {
            log.warn("Unable to parse Supabase JWT: {}", e.getMessage());
            return null;
        }
    }
    

    // =====================================================
    // 🔧 UTILITY METHODS
    // =====================================================

    /**
     * Check if a token is expired.
     * 
     * @param token JWT token string
     * @return true if token is expired
     */
    public boolean isTokenExpired(String token) {
        try {
            Date expiration = getExpirationDateFromToken(token);
            return expiration != null && expiration.before(new Date());
        } catch (Exception e) {
            return true; // Treat errors as expired
        }
    }

    /**
     * Get the Supabase project URL.
     * Used for constructing API calls to Supabase.
     * 
     * @return Supabase project URL
     */
    public String getSupabaseUrl() {
        return supabaseUrl;
    }
    
    /**
     * Force refresh of JWKS cache.
     * Call this if key rotation is detected.
     */
    public void forceJwksRefresh() {
        lastJwksRefresh = 0;
        refreshJwks();
    }
}
