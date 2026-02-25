package org.example.rentoza.config.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for W4: PII masking including Bearer token masking and static mask() accessor.
 */
class PiiMaskingConverterTest {

    @Test
    @DisplayName("W4: Email is masked (first char + domain preserved)")
    void emailIsMasked() {
        String result = PiiMaskingConverter.mask("User login failed for john.doe@gmail.com");
        assertThat(result).isEqualTo("User login failed for j***@gmail.com");
    }

    @Test
    @DisplayName("W4: Credit card is masked (last 4 preserved)")
    void creditCardIsMasked() {
        String result = PiiMaskingConverter.mask("Card: 4111-1111-1111-1234");
        assertThat(result).isEqualTo("Card: ****-****-****-1234");
    }

    @Test
    @DisplayName("W4: Bearer token is masked (first 10 chars preserved)")
    void bearerTokenIsMasked() {
        String result = PiiMaskingConverter.mask(
                "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0");
        assertThat(result).startsWith("Authorization: Bearer eyJhbGciOi[REDACTED]");
        assertThat(result).doesNotContain("eyJzdWIiOiIxMjM0NTY3ODkwIn0");
    }

    @Test
    @DisplayName("W4: Short tokens (< 10 chars after Bearer) are not masked")
    void shortTokenNotMasked() {
        String result = PiiMaskingConverter.mask("Bearer abc123");
        assertThat(result).isEqualTo("Bearer abc123");
    }

    @Test
    @DisplayName("W4: Null input returns null")
    void nullInputReturnsNull() {
        assertThat(PiiMaskingConverter.mask(null)).isNull();
    }

    @Test
    @DisplayName("W4: Message without PII passes through unchanged")
    void noPiiPassesThrough() {
        String input = "Booking 42 created successfully for car 7";
        assertThat(PiiMaskingConverter.mask(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("W4: Multiple PII types in one message are all masked")
    void multiplePiiTypesMasked() {
        String input = "User john@test.com paid with 4111-1111-1111-9999 via Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload";
        String result = PiiMaskingConverter.mask(input);
        assertThat(result).contains("j***@test.com");
        assertThat(result).contains("****-****-****-9999");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("john@test.com");
        assertThat(result).doesNotContain("4111-1111-1111");
    }
}
