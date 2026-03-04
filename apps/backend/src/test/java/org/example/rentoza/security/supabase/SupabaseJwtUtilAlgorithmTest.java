package org.example.rentoza.security.supabase;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for JWT validation in SupabaseJwtUtil.
 *
 * Covers:
 * 1. Algorithm enforcement (ES256 only, HS256/RS256/none rejected)
 * 2. Issuer and audience claim validation
 */
class SupabaseJwtUtilAlgorithmTest {

    private static final String TEST_SUPABASE_URL = "https://test.supabase.co";
    private static final String TEST_KID = "test-kid";
    private static final RetryRegistry RETRY_REGISTRY = RetryRegistry.of(RetryConfig.ofDefaults());

    /**
     * Build a fake JWT header.payload.signature with the given alg.
     * Signature is garbage — we're testing the algorithm gate, not signature verification.
     */
    private String fakeJwt(String alg) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"alg\":\"" + alg + "\",\"kid\":\"" + TEST_KID + "\"}").getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"abc\",\"iss\":\"" + TEST_SUPABASE_URL + "/auth/v1\",\"aud\":\"authenticated\",\"role\":\"authenticated\"}").getBytes());
        return header + "." + payload + ".fakesig";
    }

    // =========================================================================
    // Algorithm enforcement
    // =========================================================================

    @Nested
    @DisplayName("Algorithm enforcement")
    class AlgorithmEnforcement {

        @Test
        @DisplayName("P2: HS256 token must be rejected (no fallback)")
        void hs256Token_shouldBeRejected() {
            SupabaseJwtUtil util = new SupabaseJwtUtil("dummySecret", TEST_SUPABASE_URL, 3000, 5000, RETRY_REGISTRY);
            assertThat(util.validateToken(fakeJwt("HS256"))).isFalse();
        }

        @Test
        @DisplayName("P2: RS256 token must be rejected")
        void rs256Token_shouldBeRejected() {
            SupabaseJwtUtil util = new SupabaseJwtUtil("dummySecret", TEST_SUPABASE_URL, 3000, 5000, RETRY_REGISTRY);
            assertThat(util.validateToken(fakeJwt("RS256"))).isFalse();
        }

        @Test
        @DisplayName("P2: none algorithm must be rejected")
        void noneAlg_shouldBeRejected() {
            SupabaseJwtUtil util = new SupabaseJwtUtil("dummySecret", TEST_SUPABASE_URL, 3000, 5000, RETRY_REGISTRY);
            assertThat(util.validateToken(fakeJwt("none"))).isFalse();
        }

        @Test
        @DisplayName("P2: ES256 token (with unseen kid) returns false — no key in empty cache")
        void es256TokenWithUnknownKid_shouldReturnFalseGracefully() {
            SupabaseJwtUtil util = new SupabaseJwtUtil("dummySecret", TEST_SUPABASE_URL, 3000, 5000, RETRY_REGISTRY);
            // No JWKS loaded, so validation fails gracefully (no exception, returns false)
            assertThat(util.validateToken(fakeJwt("ES256"))).isFalse();
        }
    }

    // =========================================================================
    // Issuer and audience validation (requireIssuer / requireAudience)
    // =========================================================================

    @Nested
    @DisplayName("Issuer and audience validation")
    class IssuerAudienceValidation {

        private KeyPair ecKeyPair;

        private SupabaseJwtUtil createUtilWithKey() throws Exception {
            // Generate a real EC keypair for signing test tokens
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            ecKeyPair = gen.generateKeyPair();

            SupabaseJwtUtil util = new SupabaseJwtUtil("dummySecret", TEST_SUPABASE_URL, 3000, 5000, RETRY_REGISTRY);

            // Inject the public key into the cache via reflection
            Field cacheField = SupabaseJwtUtil.class.getDeclaredField("publicKeyCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, PublicKey> cache = (Map<String, PublicKey>) cacheField.get(util);
            cache.put(TEST_KID, ecKeyPair.getPublic());

            // Prevent JWKS refresh attempts
            Field refreshField = SupabaseJwtUtil.class.getDeclaredField("lastJwksRefresh");
            refreshField.setAccessible(true);
            refreshField.setLong(util, System.currentTimeMillis() + 3600000);

            return util;
        }

        private String signedJwt(String issuer, String audience) {
            return Jwts.builder()
                    .setHeaderParam("kid", TEST_KID)
                    .setSubject("test-user-uuid")
                    .setIssuer(issuer)
                    .setAudience(audience)
                    .claim("role", "authenticated")
                    .setIssuedAt(new Date())
                    .setExpiration(new Date(System.currentTimeMillis() + 60000))
                    .signWith(ecKeyPair.getPrivate(), SignatureAlgorithm.ES256)
                    .compact();
        }

        @Test
        @DisplayName("P3: Correct issuer + audience → valid")
        void correctIssuerAndAudience_shouldPass() throws Exception {
            SupabaseJwtUtil util = createUtilWithKey();
            String token = signedJwt(TEST_SUPABASE_URL + "/auth/v1", "authenticated");
            assertThat(util.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("P3: Wrong issuer → rejected")
        void wrongIssuer_shouldBeRejected() throws Exception {
            SupabaseJwtUtil util = createUtilWithKey();
            String token = signedJwt("https://evil.example.com/auth/v1", "authenticated");
            assertThat(util.validateToken(token)).isFalse();
        }

        @Test
        @DisplayName("P3: Wrong audience → rejected")
        void wrongAudience_shouldBeRejected() throws Exception {
            SupabaseJwtUtil util = createUtilWithKey();
            String token = signedJwt(TEST_SUPABASE_URL + "/auth/v1", "anon");
            assertThat(util.validateToken(token)).isFalse();
        }

        @Test
        @DisplayName("P3: Missing issuer → rejected")
        void missingIssuer_shouldBeRejected() throws Exception {
            SupabaseJwtUtil util = createUtilWithKey();
            // Build token without issuer
            String token = Jwts.builder()
                    .setHeaderParam("kid", TEST_KID)
                    .setSubject("test-user-uuid")
                    .setAudience("authenticated")
                    .claim("role", "authenticated")
                    .setIssuedAt(new Date())
                    .setExpiration(new Date(System.currentTimeMillis() + 60000))
                    .signWith(ecKeyPair.getPrivate(), SignatureAlgorithm.ES256)
                    .compact();
            assertThat(util.validateToken(token)).isFalse();
        }
    }
}
