package org.example.rentoza.security.validation;

import java.util.regex.Pattern;

/**
 * Input sanitization utilities for user-provided data.
 *
 * <p>Turo standard: server-side XSS prevention for name fields and other inputs.
 * Angular sanitizes on the frontend, but server-side is defense-in-depth.
 *
 * @since Phase 3 - Security Hardening
 */
public final class InputSanitizer {

    private InputSanitizer() {
    }

    /**
     * Pattern for valid name characters:
     * letters (including accented/international), spaces, hyphens, apostrophes.
     * Rejects HTML tags, scripts, control characters, etc.
     */
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "^[\\p{L}\\p{M}' \\-]+$"
    );

    /**
     * HTML tag detection (simple but effective for name fields).
     */
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");

    /**
     * Common XSS patterns.
     */
    private static final Pattern XSS_PATTERN = Pattern.compile(
            "(?i)(javascript:|data:|vbscript:|on\\w+=|<script|</script|<img|<svg|<iframe)"
    );

    /**
     * Validate and sanitize a name field.
     *
     * @param name Raw name input
     * @return Sanitized name, or null if input is malicious
     * @throws IllegalArgumentException if name contains dangerous content
     */
    public static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }

        // Strip leading/trailing whitespace
        String trimmed = name.trim();

        // Reject HTML tags
        if (HTML_TAG_PATTERN.matcher(trimmed).find()) {
            throw new IllegalArgumentException("Name contains invalid characters");
        }

        // Reject XSS patterns  
        if (XSS_PATTERN.matcher(trimmed).find()) {
            throw new IllegalArgumentException("Name contains invalid characters");
        }

        // Validate against name pattern
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Name contains invalid characters. Only letters, spaces, hyphens, and apostrophes are allowed.");
        }

        // Normalize whitespace
        trimmed = trimmed.replaceAll("\\s+", " ");

        return trimmed;
    }

    /**
     * Sanitize general text input (bio, descriptions, etc.).
     * Strips HTML tags but allows more character types than names.
     *
     * @param input Raw input
     * @return Sanitized input
     */
    public static String sanitizeText(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        // Strip HTML tags
        String sanitized = HTML_TAG_PATTERN.matcher(input).replaceAll("");

        // Strip XSS patterns
        sanitized = XSS_PATTERN.matcher(sanitized).replaceAll("");

        return sanitized.trim();
    }
}
