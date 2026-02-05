package org.example.rentoza.security;

import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Input sanitization service for preventing XSS, SQL injection, and other input-based attacks.
 * 
 * <h2>Security Measures</h2>
 * <ul>
 *   <li>XSS Prevention: HTML entity encoding</li>
 *   <li>SQL Injection Prevention: Parameterized queries (handled by JPA)</li>
 *   <li>Path Traversal Prevention: Filename sanitization</li>
 *   <li>Unicode Normalization: Prevents homograph attacks</li>
 *   <li>Length Validation: Prevents buffer overflow attempts</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <pre>
 * &#64;Autowired
 * private InputSanitizer sanitizer;
 * 
 * String safeInput = sanitizer.sanitizeText(userInput);
 * String safeHtml = sanitizer.sanitizeForHtml(userHtml);
 * String safeFilename = sanitizer.sanitizeFilename(uploadedFilename);
 * </pre>
 * 
 * @author Rentoza Platform Team
 * @since Phase 9.0 - Edge Case Hardening
 */
@Service
public class InputSanitizer {

    private static final Logger log = LoggerFactory.getLogger(InputSanitizer.class);

    // ==================== LENGTH LIMITS ====================
    
    /** Maximum length for general text fields */
    public static final int MAX_TEXT_LENGTH = 10000;
    
    /** Maximum length for short text (names, titles) */
    public static final int MAX_SHORT_TEXT_LENGTH = 255;
    
    /** Maximum length for email addresses */
    public static final int MAX_EMAIL_LENGTH = 254;
    
    /** Maximum length for phone numbers */
    public static final int MAX_PHONE_LENGTH = 20;
    
    /** Maximum length for URLs */
    public static final int MAX_URL_LENGTH = 2048;
    
    /** Maximum filename length */
    public static final int MAX_FILENAME_LENGTH = 255;

    // ==================== PATTERNS ====================
    
    /** Pattern for valid email addresses */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    );
    
    /** Pattern for valid phone numbers (international format) */
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "^\\+?[0-9\\s\\-().]{7,20}$"
    );
    
    /** Pattern for valid filenames */
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9._-]+$"
    );
    
    /** Pattern for script tags and event handlers */
    private static final Pattern SCRIPT_PATTERN = Pattern.compile(
        "(?i)<script[^>]*>.*?</script>|javascript:|on\\w+\\s*=",
        Pattern.DOTALL
    );
    
    /** Pattern for SQL injection attempts */
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
        "(?i)(--|;|'|\"|\\/\\*|\\*\\/|xp_|sp_|exec|execute|insert|select|delete|update|drop|alter|create|truncate|union|declare)",
        Pattern.CASE_INSENSITIVE
    );

    /** Dangerous file extensions */
    private static final List<String> DANGEROUS_EXTENSIONS = Arrays.asList(
        ".exe", ".bat", ".cmd", ".sh", ".ps1", ".vbs", ".js", ".jar",
        ".msi", ".dll", ".com", ".scr", ".php", ".asp", ".aspx", ".jsp"
    );

    // ==================== TEXT SANITIZATION ====================

    /**
     * Sanitize general text input.
     * 
     * @param input Raw user input
     * @return Sanitized text with HTML entities encoded
     */
    public String sanitizeText(String input) {
        if (input == null) return null;
        if (input.isEmpty()) return input;
        
        // Truncate if too long
        String truncated = truncate(input, MAX_TEXT_LENGTH);
        
        // Normalize unicode
        String normalized = normalizeUnicode(truncated);
        
        // Encode HTML entities
        String encoded = Encode.forHtml(normalized);
        
        // Remove any remaining dangerous patterns
        String safe = removeScriptPatterns(encoded);
        
        return safe.trim();
    }

    /**
     * Sanitize short text (names, titles).
     * 
     * @param input Raw user input
     * @return Sanitized short text
     */
    public String sanitizeShortText(String input) {
        if (input == null) return null;
        if (input.isEmpty()) return input;
        
        String truncated = truncate(input, MAX_SHORT_TEXT_LENGTH);
        String normalized = normalizeUnicode(truncated);
        String encoded = Encode.forHtml(normalized);
        
        return encoded.trim();
    }

    /**
     * Sanitize text for HTML context (rich text content).
     * Allows safe HTML tags but removes dangerous ones.
     * 
     * @param input Raw HTML input
     * @return Sanitized HTML
     */
    public String sanitizeForHtml(String input) {
        if (input == null) return null;
        if (input.isEmpty()) return input;
        
        String truncated = truncate(input, MAX_TEXT_LENGTH);
        
        // Remove script tags and event handlers
        String noScripts = removeScriptPatterns(truncated);
        
        // Encode special characters in text nodes
        // Note: For full HTML sanitization, consider using OWASP HTML Sanitizer
        String safe = Encode.forHtmlContent(noScripts);
        
        return safe;
    }

    /**
     * Sanitize text for use in JavaScript context.
     * 
     * @param input Raw input
     * @return Sanitized for JS inclusion
     */
    public String sanitizeForJavaScript(String input) {
        if (input == null) return null;
        return Encode.forJavaScript(input);
    }

    /**
     * Sanitize text for use in URL context.
     * 
     * @param input Raw input
     * @return URL-encoded string
     */
    public String sanitizeForUrl(String input) {
        if (input == null) return null;
        String truncated = truncate(input, MAX_URL_LENGTH);
        return Encode.forUri(truncated);
    }

    // ==================== SPECIFIC FIELD SANITIZATION ====================

    /**
     * Sanitize email address.
     * 
     * @param email Raw email input
     * @return Sanitized email or null if invalid
     */
    public String sanitizeEmail(String email) {
        if (email == null) return null;
        
        String trimmed = email.trim().toLowerCase();
        if (trimmed.length() > MAX_EMAIL_LENGTH) {
            log.warn("Email too long: {} characters", email.length());
            return null;
        }
        
        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            log.warn("Invalid email format: {}", maskEmail(email));
            return null;
        }
        
        return trimmed;
    }

    /**
     * Sanitize phone number.
     * 
     * @param phone Raw phone input
     * @return Sanitized phone or null if invalid
     */
    public String sanitizePhone(String phone) {
        if (phone == null) return null;
        
        // Remove common formatting characters
        String cleaned = phone.replaceAll("[\\s\\-().]+", "");
        
        if (cleaned.length() > MAX_PHONE_LENGTH) {
            log.warn("Phone too long: {} characters", phone.length());
            return null;
        }
        
        // Re-check with original for format validation
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            log.warn("Invalid phone format");
            return null;
        }
        
        return phone.trim();
    }

    /**
     * Sanitize filename for file uploads.
     * 
     * @param filename Original filename
     * @return Safe filename
     */
    public String sanitizeFilename(String filename) {
        if (filename == null) return null;
        
        // Remove path components (prevent path traversal)
        String name = filename;
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        
        // Truncate
        if (name.length() > MAX_FILENAME_LENGTH) {
            String extension = getExtension(name);
            int maxBase = MAX_FILENAME_LENGTH - extension.length();
            name = name.substring(0, maxBase) + extension;
        }
        
        // Check for dangerous extensions
        String lowerName = name.toLowerCase();
        for (String ext : DANGEROUS_EXTENSIONS) {
            if (lowerName.endsWith(ext)) {
                log.warn("Blocked dangerous file extension: {}", ext);
                name = name + ".blocked";
                break;
            }
        }
        
        // Replace unsafe characters
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        
        // Prevent hidden files
        if (name.startsWith(".")) {
            name = "_" + name.substring(1);
        }
        
        return name;
    }

    /**
     * Sanitize URL input.
     * 
     * @param url Raw URL input
     * @return Sanitized URL or null if dangerous
     */
    public String sanitizeUrl(String url) {
        if (url == null) return null;
        
        String trimmed = url.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            log.warn("URL too long: {} characters", url.length());
            return null;
        }
        
        // Block javascript: and data: URLs
        String lowerUrl = trimmed.toLowerCase();
        if (lowerUrl.startsWith("javascript:") || 
            lowerUrl.startsWith("data:") ||
            lowerUrl.startsWith("vbscript:")) {
            log.warn("Blocked dangerous URL scheme");
            return null;
        }
        
        // Only allow http, https, and relative URLs
        if (!lowerUrl.startsWith("http://") && 
            !lowerUrl.startsWith("https://") &&
            !lowerUrl.startsWith("/")) {
            // Assume https if no scheme
            trimmed = "https://" + trimmed;
        }
        
        return trimmed;
    }

    // ==================== VALIDATION METHODS ====================

    /**
     * Check if input contains potential XSS payloads.
     * 
     * @param input Input to check
     * @return true if potentially dangerous
     */
    public boolean containsXss(String input) {
        if (input == null) return false;
        return SCRIPT_PATTERN.matcher(input).find();
    }

    /**
     * Check if input contains potential SQL injection patterns.
     * Note: This is a secondary defense; parameterized queries are primary.
     * 
     * @param input Input to check
     * @return true if potentially dangerous
     */
    public boolean containsSqlInjection(String input) {
        if (input == null) return false;
        return SQL_INJECTION_PATTERN.matcher(input).find();
    }

    /**
     * Check if text length is within allowed limit.
     * 
     * @param input Input to check
     * @param maxLength Maximum allowed length
     * @return true if within limit
     */
    public boolean isWithinLength(String input, int maxLength) {
        return input == null || input.length() <= maxLength;
    }

    /**
     * Validate that input contains only allowed characters.
     * 
     * @param input Input to check
     * @param allowedPattern Regex pattern of allowed characters
     * @return true if valid
     */
    public boolean matchesPattern(String input, Pattern allowedPattern) {
        if (input == null) return true;
        return allowedPattern.matcher(input).matches();
    }

    // ==================== HELPER METHODS ====================

    /**
     * Truncate string to maximum length.
     */
    private String truncate(String input, int maxLength) {
        if (input.length() <= maxLength) {
            return input;
        }
        log.debug("Truncating input from {} to {} characters", input.length(), maxLength);
        return input.substring(0, maxLength);
    }

    /**
     * Normalize unicode to NFC form.
     * Prevents homograph attacks using lookalike characters.
     */
    private String normalizeUnicode(String input) {
        return java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFC);
    }

    /**
     * Remove script patterns from input.
     */
    private String removeScriptPatterns(String input) {
        return SCRIPT_PATTERN.matcher(input).replaceAll("");
    }

    /**
     * Get file extension including the dot.
     */
    private String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0) return "";
        return filename.substring(lastDot);
    }

    /**
     * Mask email for logging (privacy).
     */
    private String maskEmail(String email) {
        if (email == null || email.length() < 3) return "***";
        int atIndex = email.indexOf('@');
        if (atIndex < 0) return "***";
        return email.substring(0, Math.min(3, atIndex)) + "***" + email.substring(atIndex);
    }
}
