package org.example.rentoza.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM Encryption Service for Lockbox Codes.
 * 
 * <h2>Security Architecture</h2>
 * <p>Lockbox codes grant physical access to vehicles. A database breach
 * without encryption would enable mass vehicle theft.
 * 
 * <h2>Cryptographic Design</h2>
 * <ul>
 *   <li><b>Algorithm:</b> AES-256-GCM (Authenticated Encryption with Associated Data)</li>
 *   <li><b>IV:</b> 12-byte random nonce (prepended to ciphertext)</li>
 *   <li><b>Tag:</b> 128-bit authentication tag</li>
 *   <li><b>Key:</b> 256-bit key from environment variable (NOT hardcoded)</li>
 * </ul>
 * 
 * <h2>Storage Format</h2>
 * <pre>
 * [1 byte: key version][12 bytes: IV][N bytes: ciphertext + GCM tag]
 * </pre>
 * The version byte enables future key rotation without breaking existing data.
 * 
 * <h2>Key Management</h2>
 * <p>The encryption key MUST be provided via environment variable:
 * <pre>
 * # Generate a 256-bit key:
 * openssl rand -base64 32
 * 
 * # Set in environment:
 * export LOCKBOX_ENCRYPTION_KEY="your-base64-key-here"
 * </pre>
 * 
 * @see CheckInService#completeHostCheckIn for encryption usage
 * @see CheckInService#revealLockboxCode for decryption usage
 */
@Service
@Slf4j
public class LockboxEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits (NIST recommended)
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final byte CURRENT_KEY_VERSION = 0x01;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom;
    private final boolean enabled;

    /**
     * Initialize the encryption service with key from environment.
     *
     * @param keyBase64 Base64-encoded 256-bit AES key from application properties
     * @param activeProfile The active Spring profile (used for fail-fast in production)
     * @throws IllegalStateException if key is invalid or missing in production
     */
    public LockboxEncryptionService(
            @Value("${app.security.lockbox-encryption-key:}") String keyBase64,
            @Value("${spring.profiles.active:dev}") String activeProfile) {

        this.secureRandom = new SecureRandom();

        if (keyBase64 == null || keyBase64.isBlank()) {
            if (activeProfile != null && (activeProfile.equals("prod") || activeProfile.contains("prod"))) {
                throw new IllegalStateException(
                    "FATAL: LOCKBOX_ENCRYPTION_KEY must be configured in production. " +
                    "Refusing to start with plaintext lockbox storage. " +
                    "Set the app.security.lockbox-encryption-key environment variable " +
                    "in Cloud Run secrets. Generate with: openssl rand -base64 32"
                );
            }
            log.warn("[DEV ONLY] Lockbox encryption disabled — no key configured. " +
                     "This MUST NOT reach production.");
            this.secretKey = null;
            this.enabled = false;
            return;
        }
        
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            
            if (keyBytes.length != 32) {
                throw new IllegalStateException(
                    "Lockbox encryption key must be 256 bits (32 bytes). Got: " + keyBytes.length);
            }
            
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            this.enabled = true;
            
            log.info("✅ LockboxEncryptionService initialized: AES-256-GCM, key version {}", 
                    CURRENT_KEY_VERSION);
            
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid Base64 lockbox encryption key", e);
        }
    }

    /**
     * Check if encryption is enabled.
     * 
     * @return true if key is configured and service is operational
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Encrypt a lockbox code using AES-256-GCM.
     * 
     * <p>The output format includes a version byte to support future key rotation:
     * <pre>
     * [version: 1 byte][IV: 12 bytes][ciphertext + tag: N bytes]
     * </pre>
     * 
     * @param plainText The lockbox code to encrypt (e.g., "1234")
     * @return Encrypted bytes ready for database storage (VARBINARY column)
     * @throws IllegalStateException if encryption is disabled or fails
     */
    public byte[] encrypt(String plainText) {
        if (!enabled) {
            log.warn("[LOCKBOX] Encryption disabled - storing plain text. NEVER acceptable in production.");
            return plainText.getBytes(StandardCharsets.UTF_8);
        }
        
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException("Lockbox code cannot be null or blank");
        }
        
        try {
            // Generate random 96-bit IV (unique per encryption)
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            // Initialize cipher with GCM parameters
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            // Encrypt (GCM automatically appends authentication tag)
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Assemble output: [version][IV][ciphertext+tag]
            ByteBuffer buffer = ByteBuffer.allocate(1 + iv.length + cipherText.length);
            buffer.put(CURRENT_KEY_VERSION);
            buffer.put(iv);
            buffer.put(cipherText);

            log.debug("[LOCKBOX] Encrypted code: {} bytes → {} bytes", 
                    plainText.length(), buffer.capacity());
            
            return buffer.array();

        } catch (Exception e) {
            log.error("[LOCKBOX] Encryption failed", e);
            throw new IllegalStateException("Failed to encrypt lockbox code", e);
        }
    }

    /**
     * Decrypt a lockbox code.
     * 
     * <p>Reads the version byte to determine which key to use (future-proofing
     * for key rotation). Currently only version 0x01 is supported.
     * 
     * @param encrypted Raw bytes from database (version + IV + ciphertext)
     * @return The original lockbox code
     * @throws IllegalStateException if decryption fails or data is corrupted
     * @throws IllegalArgumentException if key version is unsupported
     */
    public String decrypt(byte[] encrypted) {
        if (!enabled) {
            log.warn("[LOCKBOX] Encryption disabled - treating data as plain text");
            return new String(encrypted, StandardCharsets.UTF_8);
        }
        
        if (encrypted == null || encrypted.length < 1 + GCM_IV_LENGTH + 16) {
            throw new IllegalArgumentException("Invalid encrypted data: too short");
        }
        
        try {
            ByteBuffer buffer = ByteBuffer.wrap(encrypted);
            
            // Read version byte
            byte version = buffer.get();
            if (version != CURRENT_KEY_VERSION) {
                throw new IllegalArgumentException(
                    "Unsupported lockbox encryption version: " + version + 
                    ". Expected: " + CURRENT_KEY_VERSION);
            }
            
            // Extract IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);

            // Extract ciphertext (includes GCM tag)
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            // Initialize cipher for decryption
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            // Decrypt and verify authentication tag
            byte[] plainText = cipher.doFinal(cipherText);
            
            log.debug("[LOCKBOX] Decrypted code successfully");
            
            return new String(plainText, StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("[LOCKBOX] Decryption failed - data may be corrupted or tampered", e);
            throw new IllegalStateException("Failed to decrypt lockbox code", e);
        }
    }

    /**
     * Migrate plain-text lockbox data to encrypted format.
     * 
     * <p>Use this for one-time migration of existing data.
     * 
     * @param plainTextBytes Old plain-text storage format
     * @return Encrypted bytes in new format
     */
    public byte[] migratePlainTextToEncrypted(byte[] plainTextBytes) {
        if (plainTextBytes == null || plainTextBytes.length == 0) {
            return null;
        }
        
        String plainText = new String(plainTextBytes, StandardCharsets.UTF_8);
        return encrypt(plainText);
    }
}
