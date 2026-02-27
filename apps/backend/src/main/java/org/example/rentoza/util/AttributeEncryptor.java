package org.example.rentoza.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.Key;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM JPA Attribute Converter for PII fields (JMBG, PIB, bank account, driver license).
 *
 * <h2>GAP-2 Remediation</h2>
 * <p>Migrated from AES/ECB (deterministic, no authentication) to AES/GCM/NoPadding
 * (authenticated encryption with random IV). ECB is considered broken for structured
 * data by NIST and OWASP — identical plaintext produced identical ciphertext, enabling
 * frequency analysis attacks on PII with common prefixes (e.g., JMBG regional codes).
 *
 * <h2>Cryptographic Design</h2>
 * <ul>
 *   <li><b>Algorithm:</b> AES-128-GCM (authenticated encryption)</li>
 *   <li><b>IV:</b> 12-byte random nonce per encryption (NIST SP 800-38D recommended)</li>
 *   <li><b>Tag:</b> 128-bit authentication tag (integrity + authenticity)</li>
 *   <li><b>Key:</b> First 16 bytes of PII_ENCRYPTION_KEY env var</li>
 * </ul>
 *
 * <h2>Storage Format</h2>
 * <pre>
 * New (GCM): "GCM$" + Base64([12-byte IV][ciphertext + GCM tag])
 * Old (ECB): Base64([ciphertext])  — detected by absence of "GCM$" prefix
 * </pre>
 *
 * <h2>Migration Strategy</h2>
 * <p>Backward-compatible: reads both old ECB and new GCM formats. All new writes
 * use GCM. Existing ECB data is transparently re-encrypted to GCM on the next
 * entity save. A batch migration job can accelerate this by reading and re-saving
 * all affected entities.
 *
 * @see org.example.rentoza.user.User — fields: jmbg, pib, bankAccountNumber, driverLicenseNumber
 */
@Component
@Converter
@Slf4j
public class AttributeEncryptor implements AttributeConverter<String, String> {

    private static final String AES = "AES";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String AES_ECB = "AES"; // Java default: AES/ECB/PKCS5Padding
    private static final String GCM_PREFIX = "GCM$";
    private static final int GCM_IV_LENGTH = 12;   // 96 bits (NIST recommended)
    private static final int GCM_TAG_BITS = 128;    // 128-bit authentication tag

    private final Key key;
    private final SecureRandom secureRandom;

    public AttributeEncryptor() {
        String encryptionKey = System.getenv("PII_ENCRYPTION_KEY");
        if (encryptionKey == null || encryptionKey.isEmpty()) {
            throw new IllegalStateException(
                "PII_ENCRYPTION_KEY environment variable is required but not set. " +
                "Make sure .env.local is loaded by your IDE and contains: " +
                "PII_ENCRYPTION_KEY=SecretKeyToEncryptPIIData1234567"
            );
        }
        // Use first 16 bytes for AES-128
        this.key = new SecretKeySpec(encryptionKey.getBytes(), 0, 16, AES);
        this.secureRandom = new SecureRandom();
    }

    /**
     * Package-private constructor for unit testing without environment variable dependency.
     */
    AttributeEncryptor(String encryptionKey) {
        this.key = new SecretKeySpec(encryptionKey.getBytes(), 0, 16, AES);
        this.secureRandom = new SecureRandom();
    }

    /**
     * Encrypt using AES-GCM with random IV.
     * Output format: "GCM$" + Base64([IV(12)][ciphertext+tag])
     */
    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);

            byte[] cipherText = cipher.doFinal(attribute.getBytes());

            // Assemble: [IV][ciphertext+tag]
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);

            return GCM_PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Error encrypting PII attribute with AES-GCM", e);
        }
    }

    /**
     * Decrypt — supports both GCM (new) and ECB (legacy) formats.
     * Legacy ECB data is detected by the absence of the "GCM$" prefix.
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;

        if (dbData.startsWith(GCM_PREFIX)) {
            return decryptGcm(dbData);
        }

        // Legacy path: attempt ECB decryption for pre-migration data
        return decryptLegacyEcb(dbData);
    }

    /**
     * Decrypt AES-GCM formatted data.
     */
    private String decryptGcm(String dbData) {
        try {
            byte[] decoded = Base64.getDecoder().decode(dbData.substring(GCM_PREFIX.length()));

            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);

            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);

            return new String(cipher.doFinal(cipherText));
        } catch (Exception e) {
            log.error("[AttributeEncryptor] GCM decryption failed — data may be corrupted or tampered", e);
            throw new IllegalStateException("Failed to decrypt PII attribute (GCM)", e);
        }
    }

    /**
     * Decrypt legacy AES-ECB data (pre-migration).
     * Falls back to returning raw data if ECB decryption also fails
     * (handles pre-encryption plaintext rows from initial deployment).
     */
    private String decryptLegacyEcb(String dbData) {
        try {
            Cipher cipher = Cipher.getInstance(AES_ECB);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return new String(cipher.doFinal(Base64.getDecoder().decode(dbData)));
        } catch (Exception e) {
            // Final fallback: data may be unencrypted plaintext from before encryption was enabled.
            // Return as-is so the entity can be loaded and re-saved with GCM encryption.
            log.warn("[AttributeEncryptor] ECB decryption failed, treating as legacy plaintext. " +
                    "This data will be re-encrypted with AES-GCM on next save.");
            return dbData;
        }
    }
}
