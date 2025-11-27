package org.example.rentoza.config.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logback converter that masks PII (Personally Identifiable Information) in log messages.
 * 
 * GDPR Compliance:
 * - Masks email addresses to prevent PII exposure in logs
 * - Pattern: john.doe@gmail.com → j***@gmail.com
 * - Works on all appenders (console, file, async)
 * 
 * Security:
 * - Email regex handles common patterns (RFC 5322 subset)
 * - Preserves first character and domain for debugging context
 * - Thread-safe (stateless, uses compiled pattern)
 * 
 * Usage in logback-spring.xml:
 * 1. Register converter: <conversionRule conversionWord="piiMsg" class="...PiiMaskingConverter"/>
 * 2. Use in pattern: %piiMsg (replaces %msg, NOT %piiMsg(%msg))
 * 
 * IMPORTANT: This is a ClassicConverter, NOT a CompositeConverter.
 * Use %piiMsg directly, not %piiMsg(%msg).
 * 
 * Example:
 * - Input:  "User login failed for user@example.com"
 * - Output: "User login failed for u***@example.com"
 * 
 * @author Rentoza Security Team
 * @since Phase 2.3 - PII Log Masking
 */
public class PiiMaskingConverter extends ClassicConverter {

    /**
     * Email pattern - RFC 5322 simplified subset.
     * Captures: local-part @ domain
     * 
     * Group 1: First character of local part
     * Group 2: Rest of local part (to be masked)
     * Group 3: Domain (preserved for debugging)
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "([a-zA-Z0-9])([a-zA-Z0-9._%+-]*)@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Phone number pattern - Serbian format and international.
     * Masks all but last 4 digits.
     * 
     * Examples:
     * - +381 60 123 4567 → +381 ** *** 4567
     * - 060/123-4567 → ***-4567
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(\\+?\\d{1,4}[\\s.-]?)?(\\(?\\d{2,3}\\)?[\\s.-]?)(\\d{3}[\\s.-]?)(\\d{4})"
    );

    /**
     * Credit card pattern - 16 digits with optional separators.
     * Masks all but last 4 digits.
     * 
     * Examples:
     * - 4111-1111-1111-1234 → ****-****-****-1234
     * - 4111111111111234 → ************1234
     */
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
            "(\\d{4}[\\s.-]?)(\\d{4}[\\s.-]?)(\\d{4}[\\s.-]?)(\\d{4})"
    );

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null || message.isEmpty()) {
            return message;
        }
        
        return maskPii(message);
    }

    /**
     * Mask all PII patterns in the input string.
     * Order matters: process from most specific to least specific.
     * 
     * @param input Original log message
     * @return Message with PII masked
     */
    public String maskPii(String input) {
        if (input == null) {
            return null;
        }

        String result = input;
        
        // 1. Mask emails (most common PII in logs)
        result = maskEmails(result);
        
        // 2. Mask credit cards (if any payment data leaks)
        result = maskCreditCards(result);
        
        // 3. Mask phone numbers
        result = maskPhoneNumbers(result);
        
        return result;
    }

    /**
     * Mask email addresses.
     * Pattern: john.doe@gmail.com → j***@gmail.com
     * 
     * Preserves:
     * - First character of local part (for debugging context)
     * - Full domain (for understanding which service)
     */
    private String maskEmails(String input) {
        Matcher matcher = EMAIL_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        
        while (matcher.find()) {
            // Group 1: First char, Group 3: Domain
            String replacement = matcher.group(1) + "***@" + matcher.group(3);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }

    /**
     * Mask credit card numbers.
     * Pattern: 4111-1111-1111-1234 → ****-****-****-1234
     * 
     * Preserves: Last 4 digits (standard PCI compliance)
     */
    private String maskCreditCards(String input) {
        Matcher matcher = CREDIT_CARD_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        
        while (matcher.find()) {
            // Keep separator style, mask first 12 digits
            String separator = extractSeparator(matcher.group(1));
            String replacement = "****" + separator + "****" + separator + "****" + separator + matcher.group(4);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }

    /**
     * Mask phone numbers.
     * Pattern: +381 60 123 4567 → +381 ** *** 4567
     * 
     * Preserves: Country code (if present) and last 4 digits
     */
    private String maskPhoneNumbers(String input) {
        Matcher matcher = PHONE_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        
        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder();
            
            // Keep country code if present
            if (matcher.group(1) != null) {
                replacement.append(matcher.group(1));
            }
            
            // Mask area code and first part of number
            replacement.append("**").append(" *** ");
            
            // Keep last 4 digits
            replacement.append(matcher.group(4));
            
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }

    /**
     * Extract separator character from a credit card group.
     */
    private String extractSeparator(String group) {
        if (group.contains("-")) return "-";
        if (group.contains(" ")) return " ";
        if (group.contains(".")) return ".";
        return "";
    }
}
