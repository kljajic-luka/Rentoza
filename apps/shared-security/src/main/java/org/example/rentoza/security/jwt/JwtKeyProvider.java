package org.example.rentoza.security.jwt;

import java.security.interfaces.ECPublicKey;

/**
 * SECURITY (M-11): Functional interface for JWT key lookup.
 * Allows services to provide their own key management (caching, retry, etc.)
 * while sharing the centralized validation logic in {@link SupabaseTokenValidator}.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link JwksKeyProvider} — standalone JWKS key fetcher (plain Java HttpClient)</li>
 *   <li>Backend lambda — delegates to Resilience4j-backed key cache</li>
 *   <li>Chat-service lambda — delegates to WebClient-backed key cache</li>
 * </ul>
 */
@FunctionalInterface
public interface JwtKeyProvider {
    /**
     * Look up an EC public key by key ID (kid) from JWT header.
     *
     * @param kid Key ID from the JWT header
     * @return ECPublicKey for ES256 signature verification, or null if not found
     */
    ECPublicKey getKey(String kid);
}
