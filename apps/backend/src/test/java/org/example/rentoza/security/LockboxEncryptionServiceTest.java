package org.example.rentoza.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for B4: LockboxEncryptionService fail-fast on missing key in production.
 */
class LockboxEncryptionServiceTest {

    private static String generateValidKey() {
        byte[] keyBytes = new byte[32]; // 256 bits
        new java.security.SecureRandom().nextBytes(keyBytes);
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    @Test
    @DisplayName("B4: Null key in prod profile throws IllegalStateException")
    void givenNullKeyInProdProfile_serviceThrowsIllegalStateException() {
        assertThatThrownBy(() -> new LockboxEncryptionService(null, "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCKBOX_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("B4: Blank key in prod profile throws IllegalStateException")
    void givenBlankKeyInProdProfile_serviceThrowsIllegalStateException() {
        assertThatThrownBy(() -> new LockboxEncryptionService("", "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCKBOX_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("B4: Blank key in 'production' profile (contains 'prod') also throws")
    void givenBlankKeyInProductionProfile_serviceThrowsIllegalStateException() {
        assertThatThrownBy(() -> new LockboxEncryptionService("  ", "production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCKBOX_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("B4: Null key in dev profile starts with encryption disabled")
    void givenNullKeyInDevProfile_serviceStartsWithEncryptionDisabled() {
        LockboxEncryptionService service = new LockboxEncryptionService(null, "dev");
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("B4: Valid key — encrypt then decrypt roundtrip succeeds")
    void givenValidKey_encryptThenDecrypt_roundtripSucceeds() {
        String key = generateValidKey();
        LockboxEncryptionService service = new LockboxEncryptionService(key, "dev");

        assertThat(service.isEnabled()).isTrue();

        byte[] encrypted = service.encrypt("1234");
        String decrypted = service.decrypt(encrypted);
        assertThat(decrypted).isEqualTo("1234");
    }

    @Test
    @DisplayName("B4: Disabled service encrypt returns plaintext bytes")
    void givenDisabledService_encrypt_returnsPlaintextBytes() {
        LockboxEncryptionService service = new LockboxEncryptionService(null, "dev");
        byte[] result = service.encrypt("test");
        assertThat(result).isEqualTo("test".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("B4: Invalid key length throws IllegalStateException")
    void givenInvalidKeyLength_throwsIllegalStateException() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]); // 128-bit, too short
        assertThatThrownBy(() -> new LockboxEncryptionService(shortKey, "dev"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits");
    }
}
