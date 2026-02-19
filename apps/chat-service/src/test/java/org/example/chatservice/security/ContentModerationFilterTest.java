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
}

