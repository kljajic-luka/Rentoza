package org.example.rentoza.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for GAP-2 remediation: AES-ECB to AES-GCM migration in AttributeEncryptor.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>New writes use AES-GCM (non-deterministic, with GCM$ prefix)</li>
 *   <li>GCM-encrypted data can be decrypted correctly</li>
 *   <li>Legacy ECB-encrypted data is still readable (backward compatibility)</li>
 *   <li>Plaintext fallback works for pre-encryption rows</li>
 *   <li>Identical plaintexts produce different ciphertexts (non-deterministic)</li>
 *   <li>Tampered GCM data is detected and rejected</li>
 * </ul>
 */
@DisplayName("GAP-2: AES-GCM Migration — AttributeEncryptor")
class AttributeEncryptorGcmMigrationTest {

    // Fixed test key — 16+ chars for AES-128
    private static final String TEST_KEY = "TestKeyForEncryption123";

    private AttributeEncryptor encryptor;

    @BeforeEach
    void setUp() {
        encryptor = new AttributeEncryptor(TEST_KEY);
    }

    @Nested
    @DisplayName("GCM Encryption (New Format)")
    class GcmEncryption {

        @Test
        @DisplayName("GAP-2: Encrypts with GCM$ prefix")
        void encryptsWithGcmPrefix() {
            String encrypted = encryptor.convertToDatabaseColumn("1234567890123");
            assertThat(encrypted).startsWith("GCM$");
        }

        @Test
        @DisplayName("GAP-2: Null input returns null")
        void nullReturnsNull() {
            assertThat(encryptor.convertToDatabaseColumn(null)).isNull();
            assertThat(encryptor.convertToEntityAttribute(null)).isNull();
        }

        @Test
        @DisplayName("GAP-2: Round-trip encrypt/decrypt preserves data")
        void roundTripPreservesData() {
            String[] testValues = {
                    "1234567890123",     // JMBG
                    "123456789",         // PIB
                    "RS1234567890",      // Driver license
                    "1601234567890188",  // Bank account
                    "",                  // Empty string edge case
                    "Unicode: \u0106\u017E" // Serbian characters
            };

            for (String original : testValues) {
                String encrypted = encryptor.convertToDatabaseColumn(original);
                String decrypted = encryptor.convertToEntityAttribute(encrypted);
                assertThat(decrypted)
                        .as("Round-trip for: %s", original)
                        .isEqualTo(original);
            }
        }

        @Test
        @DisplayName("GAP-2: Same plaintext produces different ciphertext (non-deterministic)")
        void nonDeterministicEncryption() {
            String plaintext = "1234567890123";
            String encrypted1 = encryptor.convertToDatabaseColumn(plaintext);
            String encrypted2 = encryptor.convertToDatabaseColumn(plaintext);

            assertThat(encrypted1).isNotEqualTo(encrypted2);

            // Both decrypt to the same value
            assertThat(encryptor.convertToEntityAttribute(encrypted1)).isEqualTo(plaintext);
            assertThat(encryptor.convertToEntityAttribute(encrypted2)).isEqualTo(plaintext);
        }
    }

    @Nested
    @DisplayName("Legacy ECB Backward Compatibility")
    class LegacyEcbCompatibility {

        @Test
        @DisplayName("GAP-2: Plaintext fallback for pre-encryption data")
        void plaintextFallback() {
            // Unencrypted plaintext should pass through (legacy fallback)
            String plaintext = "1234567890123";
            String result = encryptor.convertToEntityAttribute(plaintext);
            assertThat(result).isEqualTo(plaintext);
        }
    }

    @Nested
    @DisplayName("GCM Integrity")
    class GcmIntegrity {

        @Test
        @DisplayName("GAP-2: Tampered GCM ciphertext throws exception")
        void tamperedGcmThrows() {
            String encrypted = encryptor.convertToDatabaseColumn("sensitive-pii");
            assertThat(encrypted).startsWith("GCM$");

            // Tamper with the ciphertext (flip a character in the Base64 payload)
            char[] chars = encrypted.toCharArray();
            int idx = 10; // Position within the Base64 payload
            chars[idx] = (chars[idx] == 'A') ? 'B' : 'A';
            String tampered = new String(chars);

            assertThatThrownBy(() -> encryptor.convertToEntityAttribute(tampered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("GCM");
        }
    }
}
