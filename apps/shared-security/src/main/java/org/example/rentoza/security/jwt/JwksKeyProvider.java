package org.example.rentoza.security.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SECURITY (M-11): Shared JWKS key provider for Supabase ES256 JWT validation.
 * Fetches and caches EC public keys from Supabase JWKS endpoint.
 * Used by both main backend and chat-service to prevent security drift.
 *
 * <p>Thread-safe with in-memory key caching and configurable TTL.</p>
 */
public class JwksKeyProvider implements JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyProvider.class);
    private static final long DEFAULT_CACHE_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

    private final String jwksUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, ECPublicKey> keyCache = new ConcurrentHashMap<>();
    private final long cacheTtlMs;
    private volatile long lastFetchTime = 0;

    public JwksKeyProvider(String jwksUrl) {
        this(jwksUrl, DEFAULT_CACHE_TTL_MS);
    }

    public JwksKeyProvider(String jwksUrl, long cacheTtlMs) {
        this.jwksUrl = jwksUrl;
        this.cacheTtlMs = cacheTtlMs;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Get EC public key by key ID (kid).
     * Fetches from JWKS endpoint if not cached or cache expired.
     *
     * @param kid Key ID from JWT header
     * @return ECPublicKey for signature verification, or null if not found
     */
    public ECPublicKey getKey(String kid) {
        if (kid == null) {
            return null;
        }

        // Check cache
        ECPublicKey cached = keyCache.get(kid);
        if (cached != null && !isCacheExpired()) {
            return cached;
        }

        // Fetch fresh keys
        try {
            refreshKeys();
        } catch (Exception e) {
            log.error("Failed to fetch JWKS keys from {}: {}", jwksUrl, e.getMessage());
            // Return stale cached key if available
            return keyCache.get(kid);
        }

        return keyCache.get(kid);
    }

    /**
     * Force refresh of JWKS keys from endpoint.
     */
    public synchronized void refreshKeys() throws IOException, InterruptedException {
        if (!isCacheExpired() && !keyCache.isEmpty()) {
            return; // Another thread already refreshed
        }

        log.info("Fetching JWKS keys from {}", jwksUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(jwksUrl))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("JWKS endpoint returned HTTP " + response.statusCode());
        }

        JsonNode jwks = objectMapper.readTree(response.body());
        JsonNode keys = jwks.get("keys");

        if (keys == null || !keys.isArray()) {
            throw new IOException("Invalid JWKS response: missing 'keys' array");
        }

        int loaded = 0;
        for (JsonNode keyNode : keys) {
            String kty = keyNode.has("kty") ? keyNode.get("kty").asText() : "";
            String crv = keyNode.has("crv") ? keyNode.get("crv").asText() : "";
            String keyKid = keyNode.has("kid") ? keyNode.get("kid").asText() : "";

            if ("EC".equals(kty) && "P-256".equals(crv) && !keyKid.isBlank()) {
                try {
                    ECPublicKey ecKey = buildEcPublicKey(keyNode);
                    keyCache.put(keyKid, ecKey);
                    loaded++;
                } catch (Exception e) {
                    log.warn("Failed to parse EC key kid={}: {}", keyKid, e.getMessage());
                }
            }
        }

        lastFetchTime = System.currentTimeMillis();
        log.info("JWKS keys loaded: {} EC P-256 keys cached", loaded);
    }

    private boolean isCacheExpired() {
        return System.currentTimeMillis() - lastFetchTime > cacheTtlMs;
    }

    private ECPublicKey buildEcPublicKey(JsonNode keyNode) throws Exception {
        String x = keyNode.get("x").asText();
        String y = keyNode.get("y").asText();

        byte[] xBytes = Base64.getUrlDecoder().decode(x.getBytes(StandardCharsets.UTF_8));
        byte[] yBytes = Base64.getUrlDecoder().decode(y.getBytes(StandardCharsets.UTF_8));

        BigInteger xInt = new BigInteger(1, xBytes);
        BigInteger yInt = new BigInteger(1, yBytes);

        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("P-256");
        ECPoint ecPoint = spec.getCurve().createPoint(xInt, yInt);
        ECPublicKeySpec pubKeySpec = new ECPublicKeySpec(ecPoint, spec);

        KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
        return (ECPublicKey) keyFactory.generatePublic(pubKeySpec);
    }

    /**
     * Clear all cached keys. Useful for testing.
     */
    public void clearCache() {
        keyCache.clear();
        lastFetchTime = 0;
    }

    /**
     * @return number of keys currently cached
     */
    public int getCachedKeyCount() {
        return keyCache.size();
    }
}
