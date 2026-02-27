package org.example.chatservice.security;

import org.example.chatservice.security.ContentModerationFilter.ContentModerationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ContentModerationFilter.
 * 
 * Tests Option A implementation:
 * - Phone numbers (BLOCKED)
 * - Email addresses (BLOCKED)
 * - External URLs (FLAGGED, except allowlisted map domains)
 * - Contact sharing obfuscation (BLOCKED)
 */
class ContentModerationFilterTest {

    private ContentModerationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ContentModerationFilter();
    }

    @Nested
    @DisplayName("Valid messages")
    class ValidMessages {

        @Test
        @DisplayName("Should approve normal conversation message")
        void normalMessage() {
            ContentModerationResult result = filter.validateMessage("Hi! I'm excited to rent your car. What time should I pick it up?");
            
            assertThat(result.isApproved()).isTrue();
            assertThat(result.getReason()).isNull();
            assertThat(result.hasFlags()).isFalse();
        }

        @Test
        @DisplayName("Should approve empty message")
        void emptyMessage() {
            ContentModerationResult result = filter.validateMessage("");
            
            assertThat(result.isApproved()).isTrue();
        }

        @Test
        @DisplayName("Should approve null message")
        void nullMessage() {
            ContentModerationResult result = filter.validateMessage(null);
            
            assertThat(result.isApproved()).isTrue();
        }

        @Test
        @DisplayName("Should approve message with short numbers (not phone)")
        void shortNumbers() {
            ContentModerationResult result = filter.validateMessage("I'll be there in 5 minutes");
            
            assertThat(result.isApproved()).isTrue();
            assertThat(result.hasFlags()).isFalse();
        }

        @Test
        @DisplayName("Should approve message with booking reference")
        void bookingReference() {
            ContentModerationResult result = filter.validateMessage("My booking is REF-12345");
            
            assertThat(result.isApproved()).isTrue();
        }
    }

    @Nested
    @DisplayName("Phone number blocking")
    class PhoneNumberBlocking {

        @ParameterizedTest
        @ValueSource(strings = {
            "Call me at 555-0123",
            "My number is (555) 555-0123",
            "Text me on +1 555 555 0123",
            "Reach me at 555.555.0123",
            "My cell is 5555550123",
            "+44 20 7946 0958"
        })
        @DisplayName("Should block various phone number formats")
        void variousFormats(String message) {
            ContentModerationResult result = filter.validateMessage(message);
            
            assertThat(result.isApproved()).isFalse();
            assertThat(result.getReason()).contains("phone numbers");
            assertThat(result.getViolations()).contains("phone numbers");
        }
    }

    @Nested
    @DisplayName("Email address blocking")
    class EmailBlocking {

        @ParameterizedTest
        @ValueSource(strings = {
            "Email me at john@example.com",
            "My email is john.doe@company.co.uk",
            "Contact: test+alias@gmail.com",
            "Send to user_name@domain.org"
        })
        @DisplayName("Should block various email formats")
        void variousFormats(String message) {
            ContentModerationResult result = filter.validateMessage(message);
            
            assertThat(result.isApproved()).isFalse();
            assertThat(result.getReason()).contains("email addresses");
            assertThat(result.getViolations()).contains("email addresses");
        }
    }

    @Nested
    @DisplayName("URL handling (Option A)")
    class UrlHandling {

        @ParameterizedTest
        @ValueSource(strings = {
            "Check out https://example.com",
            "Visit http://mysite.org/page",
            "Go to www.example.com",
            "Link: https://bit.ly/abc123"
        })
        @DisplayName("Should flag (not block) non-allowlisted URLs")
        void flagsNonAllowlistedUrls(String message) {
            ContentModerationResult result = filter.validateMessage(message);
            
            // URLs are now FLAGGED, not blocked
            assertThat(result.isApproved()).isTrue();
            assertThat(result.hasFlags()).isTrue();
            assertThat(result.getFlags()).contains("external link");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "Meet me here: https://maps.google.com/place/123",
            "Location: https://google.com/maps?q=abcd",
            "Go to https://goo.gl/maps/xyz123",
            "Apple Maps: https://maps.apple.com/place/123",
            "Waze: https://waze.com/ul/123",
            "https://openstreetmap.org/node/123"
        })
        @DisplayName("Should allow map service URLs without flagging")
        void allowsMapUrls(String message) {
            ContentModerationResult result = filter.validateMessage(message);
            
            assertThat(result.isApproved()).isTrue();
            assertThat(result.hasFlags()).isFalse();
        }
    }

    @Nested
    @DisplayName("Obfuscation detection")
    class ObfuscationDetection {

        @Test
        @DisplayName("Should block 'call me' with numbers")
        void callMeWithNumbers() {
            ContentModerationResult result = filter.validateMessage("call me at five five five 12345");
            
            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("contact sharing attempts");
        }

        @Test
        @DisplayName("Should block 'text me' with numbers")
        void textMeWithNumbers() {
            ContentModerationResult result = filter.validateMessage("Text me! 12345678");
            
            assertThat(result.isApproved()).isFalse();
        }

        @Test
        @DisplayName("Should block WhatsApp mention with numbers")
        void whatsappWithNumbers() {
            ContentModerationResult result = filter.validateMessage("whatsapp 12345678");
            
            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("contact sharing attempts");
        }
    }

    @Nested
    @DisplayName("Multiple violations")
    class MultipleViolations {

        @Test
        @DisplayName("Should report all violations in message with phone and email")
        void phoneAndEmail() {
            ContentModerationResult result = filter.validateMessage(
                "Call me at 555-0123 or email john@example.com"
            );
            
            assertThat(result.isApproved()).isFalse();
            // "Call me at ...digits" also triggers obfuscation detection, so ≥2 violations
            assertThat(result.getViolations())
                .hasSizeGreaterThanOrEqualTo(2)
                .contains("phone numbers", "email addresses");
        }

        @Test
        @DisplayName("Should block phone even if message also has flagged URL")
        void phoneWithFlaggedUrl() {
            ContentModerationResult result = filter.validateMessage(
                "Call 555-0123 or visit https://example.com"
            );

            // Phone is a blocker, URL would be a flag
            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("phone numbers");
            // Flags should also be present
            assertThat(result.getFlags()).contains("external link");
        }
    }

    @Nested
    @DisplayName("Unicode normalization bypass prevention")
    class UnicodeNormalizationBypass {

        @Test
        @DisplayName("Should block fullwidth digits in phone numbers")
        void fullwidthDigitsInPhoneNumber() {
            // \uFF15 = fullwidth 5, \uFF10 = fullwidth 0, \uFF11 = fullwidth 1,
            // \uFF12 = fullwidth 2, \uFF13 = fullwidth 3
            ContentModerationResult result = filter.validateMessage(
                "Call me at \uFF15\uFF15\uFF15-\uFF10\uFF11\uFF12\uFF13"
            );

            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("phone numbers");
        }

        @Test
        @DisplayName("Should block fullwidth digits in international phone numbers")
        void fullwidthDigitsInInternationalPhone() {
            // +1 with fullwidth digits for the rest
            ContentModerationResult result = filter.validateMessage(
                "Text me on +\uFF11 \uFF15\uFF15\uFF15 \uFF15\uFF15\uFF15 \uFF10\uFF11\uFF12\uFF13"
            );

            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("phone numbers");
        }

        @Test
        @DisplayName("Should block Cyrillic homoglyphs in payment keywords")
        void cyrillicHomoglyphsInPaymentKeyword() {
            // \u0440 = Cyrillic p, \u0430 = Cyrillic a, \u0443 = Cyrillic y
            // "\u0440\u0430\u0443\u0440al" visually looks like "paypal"
            ContentModerationResult result = filter.validateMessage(
                "Send money via \u0440\u0430\u0443\u0440al"
            );

            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("off-platform payment");
        }

        @Test
        @DisplayName("Should block Cyrillic homoglyphs in venmo keyword")
        void cyrillicHomoglyphsInVenmo() {
            // \u0435 = Cyrillic e, \u043E = Cyrillic o
            // "v\u0435nm\u043E" visually looks like "venmo"
            ContentModerationResult result = filter.validateMessage(
                "Just use v\u0435nm\u043E instead"
            );

            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("off-platform payment");
        }

        @Test
        @DisplayName("Should block zero-width characters inserted in payment keywords")
        void zeroWidthCharactersInPaymentKeyword() {
            // Zero-width space (\u200B) inserted inside "paypal"
            ContentModerationResult result = filter.validateMessage(
                "Let's use pay\u200Bpal to settle up"
            );

            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("off-platform payment");
        }

        @Test
        @DisplayName("Should block zero-width joiners inserted in email addresses")
        void zeroWidthJoinerInEmail() {
            // Zero-width joiner (\u200D) inserted inside the email
            ContentModerationResult result = filter.validateMessage(
                "Email me at john\u200D@example.com"
            );

            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("email addresses");
        }

        @Test
        @DisplayName("Should block dot-separated characters spelling payment keywords")
        void dotSeparatedPaymentKeyword() {
            ContentModerationResult result = filter.validateMessage(
                "Send it through P.a.y.p.a.l please"
            );

            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("off-platform payment");
        }

        @Test
        @DisplayName("Should block dot-separated characters spelling venmo")
        void dotSeparatedVenmo() {
            ContentModerationResult result = filter.validateMessage(
                "Use V.e.n.m.o to pay me"
            );

            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("off-platform payment");
        }

        @Test
        @DisplayName("Should block combined Cyrillic homoglyphs and zero-width characters")
        void combinedCyrillicAndZeroWidth() {
            // Cyrillic \u0440 for p, zero-width space in the middle
            ContentModerationResult result = filter.validateMessage(
                "Use \u0440ay\u200Bpal for payment"
            );

            assertThat(result.isApproved()).isFalse();
            assertThat(result.getViolations()).contains("off-platform payment");
        }
    }

    @Nested
    @DisplayName("normalizeForModeration direct tests")
    class NormalizeForModerationDirect {

        @Test
        @DisplayName("Should NFKC normalize fullwidth digits to ASCII")
        void nfkcNormalizesFullwidthDigits() {
            // \uFF15\uFF15\uFF15 = fullwidth "555"
            String result = filter.normalizeForModeration("\uFF15\uFF15\uFF15");

            assertThat(result).isEqualTo("555");
        }

        @Test
        @DisplayName("Should NFKC normalize fullwidth letters to ASCII")
        void nfkcNormalizesFullwidthLetters() {
            // \uFF28\uFF45\uFF4C\uFF4C\uFF4F = fullwidth "Hello"
            String result = filter.normalizeForModeration("\uFF28\uFF45\uFF4C\uFF4C\uFF4F");

            assertThat(result).isEqualTo("Hello");
        }

        @Test
        @DisplayName("Should replace Cyrillic homoglyphs with ASCII equivalents")
        void replaceCyrillicHomoglyphs() {
            // \u0440 = Cyrillic p, \u0430 = Cyrillic a, \u0443 = Cyrillic y
            String result = filter.normalizeForModeration("\u0440\u0430\u0443");

            assertThat(result).isEqualTo("pay");
        }

        @Test
        @DisplayName("Should replace uppercase Cyrillic homoglyphs with ASCII equivalents")
        void replaceUppercaseCyrillicHomoglyphs() {
            // \u0420 = Cyrillic P, \u0410 = Cyrillic A, \u0422 = Cyrillic T
            String result = filter.normalizeForModeration("\u0420\u0410\u0422");

            assertThat(result).isEqualTo("PAT");
        }

        @Test
        @DisplayName("Should strip zero-width space characters")
        void stripZeroWidthSpace() {
            String result = filter.normalizeForModeration("pay\u200Bpal");

            assertThat(result).isEqualTo("paypal");
        }

        @Test
        @DisplayName("Should strip zero-width joiner characters")
        void stripZeroWidthJoiner() {
            String result = filter.normalizeForModeration("ven\u200Dmo");

            assertThat(result).isEqualTo("venmo");
        }

        @Test
        @DisplayName("Should strip zero-width non-joiner characters")
        void stripZeroWidthNonJoiner() {
            String result = filter.normalizeForModeration("zel\u200Cle");

            assertThat(result).isEqualTo("zelle");
        }

        @Test
        @DisplayName("Should strip word joiner characters")
        void stripWordJoiner() {
            String result = filter.normalizeForModeration("bit\u2060coin");

            assertThat(result).isEqualTo("bitcoin");
        }

        @Test
        @DisplayName("Should strip soft hyphen characters")
        void stripSoftHyphen() {
            String result = filter.normalizeForModeration("cash\u00ADapp");

            assertThat(result).isEqualTo("cashapp");
        }

        @Test
        @DisplayName("Should collapse dot-separated single characters")
        void collapseDotSeparatedChars() {
            String result = filter.normalizeForModeration("P.a.y.p.a.l");

            assertThat(result).isEqualTo("Paypal");
        }

        @Test
        @DisplayName("Should handle null input gracefully")
        void handleNullInput() {
            String result = filter.normalizeForModeration(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should handle combined normalizations in one pass")
        void combinedNormalization() {
            // Cyrillic \u0440 for p, zero-width space, fullwidth \uFF15 for 5
            String result = filter.normalizeForModeration("\u0440ay\u200Bpal \uFF15\uFF15\uFF15");

            assertThat(result).isEqualTo("paypal 555");
        }
    }
}

