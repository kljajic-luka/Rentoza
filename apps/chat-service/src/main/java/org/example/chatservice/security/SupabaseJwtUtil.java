package org.example.chatservice.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;
import org.example.chatservice.exception.ForbiddenException;
import org.example.chatservice.model.User;
import org.example.chatservice.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.example.rentoza.security.jwt.SupabaseTokenValidator;

/**
 * Supabase JWT Utility for ES256 Token Validation
 * 
 * <p>Algorithm: ES256 (Elliptic Curve Digital Signature with SHA-256)</p>
 * <p>Purpose: Validate Supabase Auth JWTs with asymmetric public key verification</p>
 * 
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Dynamic JWKS endpoint for automatic key rotation</li>
 *   <li>In-memory key cache with 24-hour TTL</li>
 *   <li>UUID (auth.uid) to BIGINT (users.id) mapping</li>
 *   <li>Caffeine cache for user ID lookups</li>
 *   <li>Performance monitoring via Micrometer</li>
 * </ul>
 * 
 * @author Rentoza Development Team
 * @since 2.0.0 (Supabase Migration)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupabaseJwtUtil {

    private final WebClient.Builder webClientBuilder;
    private final UserRepository userRepository;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${supabase.jwt.jwks-url}")
    private String jwksUrl;

    @Value("${supabase.jwt.issuer}")
    private String issuer;

    @Value("${supabase.jwt.audience:authenticated}")
    private String audience;

    @Value("${supabase.anon-key}")
    private String supabaseAnonKey;

    // In-memory cache for JWKS public keys (kid -> ECPublicKey)
    private final Map<String, ECPublicKey> keyCache = new ConcurrentHashMap<>();

    // Track last JWKS fetch time for cache invalidation
    private long lastJwksFetchTime = 0;
    private static final long JWKS_CACHE_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

    // SECURITY (M-11): Shared validator prevents security drift between backend and chat-service
    private SupabaseTokenValidator sharedValidator;

    @PostConstruct
    public void init() {
        // Register BouncyCastle security provider (required for EC key operations)
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            log.info("✅ BouncyCastle security provider registered");
        }
        
        // Validate required configuration
        if (jwksUrl == null || jwksUrl.contains("your-project")) {
            throw new IllegalStateException(
                "SUPABASE_JWKS_URL not configured - still contains placeholder or is null");
        }
        
        if (issuer == null || issuer.contains("your-project")) {
            throw new IllegalStateException(
                "SUPABASE_JWT_ISSUER not configured - still contains placeholder or is null");
        }
        
        log.info("✅ Supabase config validated");
        log.info("Initializing Supabase JWT validation with JWKS endpoint: {}", jwksUrl);
        
        try {
            refreshPublicKeysWithRetry();
            log.info("JWKS cache initialized successfully with {} keys", keyCache.size());
        } catch (Exception e) {
            log.error("Failed to initialize JWKS cache: {}. Token validation will fail until keys are fetched.", 
                e.getMessage(), e);
        }

        // SECURITY (M-11): Create shared validator linking to this service's key management
        this.sharedValidator = new SupabaseTokenValidator(
                kid -> getPublicKey(kid),
                issuer, audience);
    }

    @Timed(value = "auth.jwt.validation", description = "JWT validation duration", percentiles = {0.5, 0.95, 0.99})
    public boolean validateToken(String token) {
        // SECURITY (M-11): Delegate to shared validator in production
        if (sharedValidator != null) {
            boolean valid = sharedValidator.validateToken(token);
            if (valid) {
                meterRegistry.counter("auth.jwt.validation.success").increment();
            } else {
                meterRegistry.counter("auth.jwt.validation.failure", "reason", "shared_validator_rejected").increment();
            }
            return valid;
        }
        // Local fallback for unit tests where @PostConstruct was not invoked
        try {
            // Parse unverified header to get kid (key ID)
            String kid = extractKeyId(token);
            if (kid == null) {
                log.warn("Token missing 'kid' (key ID) in header");
                meterRegistry.counter("auth.jwt.validation.failure", "reason", "missing_kid").increment();
                return false;
            }

            // Get public key from cache (refresh if needed)
            ECPublicKey publicKey = getPublicKey(kid);
            if (publicKey == null) {
                log.warn("No public key found for kid: {}", kid);
                meterRegistry.counter("auth.jwt.validation.failure", "reason", "key_not_found").increment();
                return false;
            }

            // Parse and verify token
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)  // ES256 signature verification
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();

            // Check expiration
            if (isTokenExpired(claims)) {
                log.debug("Token expired: exp={}", claims.getExpiration());
                meterRegistry.counter("auth.jwt.validation.failure", "reason", "expired").increment();
                return false;
            }

            log.debug("Token validated successfully for user: {}", claims.getSubject());
            meterRegistry.counter("auth.jwt.validation.success").increment();
            return true;

        } catch (SignatureException e) {
            log.warn("Invalid token signature: {}", e.getMessage());
            meterRegistry.counter("auth.jwt.validation.failure", "reason", "invalid_signature").increment();
            return false;
        } catch (Exception e) {
            log.error("Token validation failed: {}", e.getMessage());
            meterRegistry.counter("auth.jwt.validation.failure", "reason", "exception").increment();
            return false;
        }
    }

    public UUID getSupabaseUserId(String token) {
        try {
            Claims claims = extractAllClaims(token);
            String subject = claims.getSubject();
            return UUID.fromString(subject);
        } catch (Exception e) {
            log.error("Failed to extract Supabase user ID from token: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid token or subject not a valid UUID", e);
        }
    }

    @Timed(value = "auth.user.mapping.total", description = "Total user mapping time")
    public Long getRentozaUserId(String token) {
        UUID authUid = getSupabaseUserId(token);
        return getRentozaUserIdCached(authUid);
    }

    @Cacheable(value = "userIdMapping", key = "#authUid", unless = "#result == null")
    @Timed(value = "auth.user.cache.lookup", description = "Cache lookup + DB fallback", percentiles = {0.5, 0.95, 0.99})
    public synchronized Long getRentozaUserIdCached(UUID authUid) {
        log.debug("Cache MISS: fetching user ID for authUid={}", authUid);
        
        return userRepository.findByAuthUid(authUid)
                .map(user -> {
                    log.debug("User found: userId={}, authUid={}", user.getId(), authUid);
                    meterRegistry.counter("auth.user.mapping.success").increment();
                    return user.getId();
                })
                .orElseThrow(() -> {
                    log.warn("User not found for authUid: {}", authUid);
                    meterRegistry.counter("auth.user.mapping.failure", "reason", "user_not_found").increment();
                    return new ForbiddenException("User not found for auth_uid: " + authUid);
                });
    }

    public String getEmail(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.get("email", String.class);
        } catch (Exception e) {
            log.debug("Failed to extract email from token: {}", e.getMessage());
            return null;
        }
    }

    public String getRole(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.get("role", String.class);
        } catch (Exception e) {
            log.debug("Failed to extract role from token: {}", e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // PRIVATE HELPER METHODS - JWKS/ES256
    // ========================================================================

    private String extractKeyId(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                throw new MalformedJwtException("Invalid JWT format - missing header/payload");
            }
            
            String headerJson = new String(
                Base64.getUrlDecoder().decode(parts[0]),
                StandardCharsets.UTF_8
            );
            
            JsonNode header = objectMapper.readTree(headerJson);
            
            if (!header.has("kid")) {
                log.warn("JWT missing 'kid' header - cannot identify key");
                return null;
            }
            
            String kid = header.get("kid").asText();
            log.debug("Extracted kid from JWT: {}", kid);
            
            return kid;
            
        } catch (Exception e) {
            log.error("Failed to extract kid from JWT: {}", e.getMessage());
            return null;
        }
    }

    private ECPublicKey getPublicKey(String kid) {
        ECPublicKey key = keyCache.get(kid);
        
        if (key == null || isCacheExpired()) {
            log.info("JWKS cache miss or expired for kid: {}, refreshing...", kid);
            try {
                refreshPublicKeys();
            } catch (IOException e) {
                log.error("Failed to refresh JWKS for kid {}: {}", kid, e.getMessage());
            }
            key = keyCache.get(kid);
        }
        
        return key;
    }

    private boolean isCacheExpired() {
        return (System.currentTimeMillis() - lastJwksFetchTime) > JWKS_CACHE_TTL_MS;
    }

    @Scheduled(fixedDelay = 86400000, initialDelay = 86400000)
    public void refreshPublicKeysWithRetry() {
        int maxAttempts = 3;
        int attempt = 0;
        
        while (attempt < maxAttempts) {
            try {
                attempt++;
                log.debug("JWKS refresh attempt {}/{}", attempt, maxAttempts);
                
                refreshPublicKeys();
                
                log.info("✅ JWKS cache refreshed: {} keys loaded", keyCache.size());
                meterRegistry.counter("jwks.refresh.success").increment();
                return;
                
            } catch (IOException e) {
                log.warn("JWKS refresh attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                
                if (attempt >= maxAttempts) {
                    log.error("JWKS refresh failed after {} retries.", maxAttempts, e);
                    meterRegistry.counter("jwks.refresh.failure").increment();
                    return;
                }
                
                try {
                    long waitMs = 2000L * attempt;
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void refreshPublicKeys() throws IOException {
        log.debug("Fetching JWKS from: {}", jwksUrl);
        
        String jwksJson = webClientBuilder.build()
                .get()
                .uri(jwksUrl)
                .header("apikey", supabaseAnonKey)  // Required for Supabase
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.error("Failed to fetch JWKS: {}", e.getMessage());
                    return Mono.error(new IOException("JWKS fetch failed", e));
                })
                .block();

        if (jwksJson == null || jwksJson.isEmpty()) {
            throw new IOException("JWKS endpoint returned empty response");
        }

        parseAndCacheKeys(jwksJson);
        lastJwksFetchTime = System.currentTimeMillis();
    }

    private void parseAndCacheKeys(String jwksJson) throws IOException {
        try {
            JsonNode jwks = objectMapper.readTree(jwksJson);
            JsonNode keys = jwks.get("keys");
            
            if (keys == null || !keys.isArray()) {
                throw new IOException("Invalid JWKS format: 'keys' array not found");
            }
            
            int parsedCount = 0;
            
            for (JsonNode key : keys) {
                String kid = key.get("kid").asText();
                String kty = key.get("kty").asText();
                
                if (!"EC".equals(kty)) {
                    log.warn("Skipping non-EC key: kid={}, kty={}", kid, kty);
                    continue;
                }
                
                String x = key.get("x").asText();
                String y = key.get("y").asText();
                String crv = key.get("crv").asText();
                
                ECPublicKey publicKey = buildECPublicKey(x, y, crv);
                keyCache.put(kid, publicKey);
                
                parsedCount++;
                log.info("Cached public key: kid={}, curve={}", kid, crv);
            }
            
            if (parsedCount == 0) {
                throw new IOException("No valid EC keys in JWKS");
            }
            
            log.info("Successfully parsed {} EC keys from JWKS", parsedCount);
            
        } catch (Exception e) {
            log.error("Failed to parse JWKS: {}", e.getMessage(), e);
            throw new IOException("JWKS parsing failed", e);
        }
    }

    private ECPublicKey buildECPublicKey(String xBase64Url, String yBase64Url, String curveName) 
            throws Exception {
        
        byte[] xBytes = Base64.getUrlDecoder().decode(xBase64Url);
        byte[] yBytes = Base64.getUrlDecoder().decode(yBase64Url);
        
        BigInteger x = new BigInteger(1, xBytes);
        BigInteger y = new BigInteger(1, yBytes);
        
        ECNamedCurveParameterSpec curveSpec;
        switch (curveName) {
            case "P-256":
                curveSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
                break;
            case "P-384":
                curveSpec = ECNamedCurveTable.getParameterSpec("secp384r1");
                break;
            case "P-521":
                curveSpec = ECNamedCurveTable.getParameterSpec("secp521r1");
                break;
            default:
                throw new IllegalArgumentException("Unsupported curve: " + curveName);
        }
        
        ECPoint point = curveSpec.getCurve().createPoint(x, y);
        ECPublicKeySpec publicKeySpec = new ECPublicKeySpec(point, curveSpec);
        
        KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
        return (ECPublicKey) keyFactory.generatePublic(publicKeySpec);
    }

    private Claims extractAllClaims(String token) {
        String kid = extractKeyId(token);
        ECPublicKey publicKey = getPublicKey(kid);
        
        if (publicKey == null) {
            throw new IllegalArgumentException("No public key found for token");
        }

        return Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token.trim())
                .getPayload();
    }

    private boolean isTokenExpired(Claims claims) {
        return claims.getExpiration().before(new Date());
    }
}
