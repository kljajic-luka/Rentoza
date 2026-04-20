package org.example.rentoza.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Cryptographic hashing utility for PII uniqueness checks (JMBG, PIB, driver license).
 *
 * <h2>Security Design (C-1 Remediation)</h2>
 * <p>Uses HMAC-SHA256 with a server-side secret pepper instead of unsalted SHA-256.
 * This makes brute-force recovery of low-entropy identifiers (e.g., 13-digit JMBG,
 * 9-digit PIB) computationally infeasible without possession of the pepper key.
 *
 * <p><b>Threat model:</b> An attacker with read access to the database (SQL injection,
 * backup leak, insider threat) cannot recover the original identifier values because:
 * <ul>
 *   <li>HMAC requires the secret pepper key to compute candidate hashes</li>
 *   <li>Without the pepper, brute-force complexity is effectively unbounded</li>
 *   <li>The pepper is stored outside the database (environment variable)</li>
 * </ul>
 *
 * <p><b>Migration:</b> Existing SHA-256 hashes must be re-hashed with HMAC after this
 * change is deployed. Run a batch migration to decrypt PII values and re-hash them.
 *
 * @see org.example.rentoza.user.ProfileCompletionService
 * @see org.example.rentoza.user.OwnerVerificationService
 */
@Component
@Slf4j
public class HashUtil {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_PEPPER_LENGTH = 32;

    private final SecretKeySpec pepperKey;

    /**
     * Constructs HashUtil with pepper from PII_HASH_PEPPER environment variable.
     *
     * @throws IllegalStateException if pepper is not set or too short
     */
    public HashUtil() {
        String pepper = System.getenv("PII_HASH_PEPPER");
        if (pepper == null || pepper.isBlank()) {
            // Fallback to PII_ENCRYPTION_KEY for backward compatibility during migration
            pepper = System.getenv("PII_ENCRYPTION_KEY");
            if (pepper == null || pepper.isBlank()) {
                throw new IllegalStateException(
                    "PII_HASH_PEPPER environment variable is required but not set. " +
                    "This secret is used as an HMAC pepper for hashing sensitive identifiers (JMBG, PIB). " +
                    "Generate a 32+ character random secret and set it as PII_HASH_PEPPER."
                );
            }
            log.warn("[HashUtil] PII_HASH_PEPPER not set, falling back to PII_ENCRYPTION_KEY. " +
                     "Set a dedicated PII_HASH_PEPPER for production.");
        }

        if (pepper.length() < MIN_PEPPER_LENGTH) {
            log.warn("[HashUtil] PII_HASH_PEPPER is shorter than {} characters. " +
                     "Use a 32+ character secret for production security.", MIN_PEPPER_LENGTH);
        }

        this.pepperKey = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        log.info("[HashUtil] Initialized with HMAC-SHA256 pepper (length={})", pepper.length());
    }

    /**
     * Package-private constructor for unit testing without environment variable dependency.
     *
     * @param pepper The HMAC pepper key string
     */
    HashUtil(String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalArgumentException("HMAC pepper must not be null or blank");
        }
        this.pepperKey = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /**
     * Creates an HMAC-SHA256 keyed hash of the input string.
     *
     * <p>Used for creating searchable uniqueness indexes of encrypted PII data
     * (JMBG, PIB, driver license numbers). The HMAC pepper prevents brute-force
     * recovery even for low-entropy inputs like 9-digit PIB numbers.
     *
     * @param input Plain text input (e.g., JMBG, PIB)
     * @return Base64-encoded HMAC-SHA256 hash, or null if input is null
     * @throws IllegalStateException if HMAC computation fails
     */
    public String hash(String input) {
        if (input == null) return null;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(pepperKey);
            byte[] hmacBytes = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }
}
