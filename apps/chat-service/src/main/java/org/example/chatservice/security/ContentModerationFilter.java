package org.example.chatservice.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Content moderation filter for messaging — Turo-grade implementation.
 * 
 * Protects platform by blocking/flagging PII that could enable off-platform transactions:
 * - Phone numbers (all formats including obfuscated) - BLOCKED
 * - Email addresses - BLOCKED
 * - Off-platform payment app mentions (venmo, paypal, zelle, etc.) - BLOCKED
 * - Contact sharing obfuscation (e.g., "call me", "text me", "whatsapp") - BLOCKED
 * - External URLs - FLAGGED (except allowlisted map domains)
 * 
 * Allowlisted domains (allowed without flagging):
 * - maps.google.com, google.com/maps, goo.gl/maps
 * - maps.apple.com, waze.com, openstreetmap.org
 */
@Component
@Slf4j
public class ContentModerationFilter {

    // Phone number patterns - covers US, EU, international formats
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b" +       // US 10-digit: 555-555-0123, 555.555.0123, 5555550123
            "|\\b\\d{3}[-.]\\d{4}\\b" +                    // US 7-digit local: 555-0123, 555.0123
            "|\\+\\d{1,3}(?:[\\s.-]?\\d){7,}" +            // International with spaces/dashes: +1 555 555 0123, +44 20 7946 0958
            "|\\(\\d{3}\\)\\s?\\d{3}[-.]?\\d{4}" +       // US parenthetical: (555) 555-0123
            "|\\b0\\d{2}[\\s/-]?\\d{3}[\\s/-]?\\d{3,4}\\b" +  // Serbian/EU local: 011/123-456, 064 123 4567
            "|\\+\\d{1,3}\\s?\\(\\d+\\)\\s?\\d+",        // +1 (555) 5550123
            Pattern.CASE_INSENSITIVE
    );

    // Standard email pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[\\w.+\\-]+@[\\w.\\-]+\\.[a-zA-Z]{2,}"
    );

    // URL pattern including www prefixes
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[^\\s]+|www\\.[^\\s]+)",
            Pattern.CASE_INSENSITIVE
    );

    // Contact sharing obfuscation patterns
    private static final Pattern CONTACT_OBFUSCATION_PATTERN = Pattern.compile(
            "(?:call|text|pozovi|poruka|javi)\\s+(?:me|mi|se).*\\d{4,}" +
            "|(?:whatsapp|viber|telegram|signal)\\s*[:\\-]?\\s*\\+?\\d{4,}" +
            "|(?:call|text|pozovi|poruka)\\s+(?:me|mi|se).*(?:five|six|seven|eight|nine|zero|jedan|dva|tri|četiri|pet|šest|sedam|osam|devet|nula){3,}",
            Pattern.CASE_INSENSITIVE
    );

    // Off-platform payment keywords
    private static final List<String> PAYMENT_KEYWORDS = List.of(
            "venmo", "paypal", "zelle", "cashapp", "cash app",
            "gotovinski", "keš", "kes", "transfer", "western union",
            "revolut", "wise", "bitcoin", "crypto", "btc", "eth",
            "plati van", "uplati na", "direktno plaćanje", "direktno placanje"
    );

    // Allowlisted URL domains (map services for pickup/dropoff locations)
    private static final Set<String> ALLOWED_URL_DOMAINS = Set.of(
            "maps.google.com",
            "google.com/maps",
            "goo.gl/maps",
            "maps.apple.com",
            "waze.com",
            "openstreetmap.org"
    );

    /**
     * Validate message content for policy violations.
     * 
     * @param content The message content to validate
     * @return ContentModerationResult with approval status, flags, and reason
     */
    public ContentModerationResult validateMessage(String content) {
        if (content == null || content.isBlank()) {
            return ContentModerationResult.approved();
        }

        String trimmedContent = content.trim();
        List<String> violations = new ArrayList<>();
        List<String> flags = new ArrayList<>();

        // 1. Check for phone numbers (BLOCKED - enables off-platform contact)
        if (PHONE_PATTERN.matcher(trimmedContent).find()) {
            violations.add("phone numbers");
            log.debug("[Moderation] Phone number detected - BLOCKED");
        }

        // 2. Check for email addresses (BLOCKED - prevents off-platform transactions)
        if (EMAIL_PATTERN.matcher(trimmedContent).find()) {
            violations.add("email addresses");
            log.debug("[Moderation] Email address detected - BLOCKED");
        }

        // 3. Check for contact sharing obfuscation (BLOCKED)
        if (CONTACT_OBFUSCATION_PATTERN.matcher(trimmedContent).find()) {
            violations.add("contact sharing attempts");
            log.debug("[Moderation] Contact obfuscation detected - BLOCKED");
        }

        // 4. Check for off-platform payment app mentions (BLOCKED)
        String lowerContent = trimmedContent.toLowerCase();
        for (String keyword : PAYMENT_KEYWORDS) {
            if (lowerContent.contains(keyword)) {
                violations.add("off-platform payment");
                log.debug("[Moderation] Payment keyword '{}' detected - BLOCKED", keyword);
                break;
            }
        }

        // 5. Check for URLs (ALLOWED if maps, FLAGGED otherwise for admin review)
        Matcher urlMatcher = URL_PATTERN.matcher(trimmedContent);
        while (urlMatcher.find()) {
            String url = urlMatcher.group().toLowerCase();
            if (!isAllowedUrl(url)) {
                flags.add("external link");
                log.debug("[Moderation] Non-allowlisted URL detected - FLAGGED: {}", maskUrl(url));
            } else {
                log.debug("[Moderation] Allowlisted URL allowed: {}", maskUrl(url));
            }
        }

        // If blocked violations exist, reject
        if (!violations.isEmpty()) {
            String reason = String.format(
                    "Message contains %s which are not allowed for safety. Please keep all communication on the platform.",
                    String.join(", ", violations)
            );
            log.info("[Moderation] Message blocked: {}", String.join(", ", violations));
            return ContentModerationResult.rejected(reason, violations, flags);
        }

        // If only flags exist, approve but flag for admin review
        if (!flags.isEmpty()) {
            log.info("[Moderation] Message approved with flags: {}", String.join(", ", flags));
            return ContentModerationResult.approvedWithFlags(flags);
        }

        return ContentModerationResult.approved();
    }

    /**
     * Check if URL is in the allowlist (map services).
     * 
     * Uses proper URI parsing to prevent bypasses like
     * "evil.com?q=maps.google.com" which would pass a substring check.
     */
    private boolean isAllowedUrl(String url) {
        try {
            String normalized = url.toLowerCase().trim();
            // Ensure scheme for proper URI parsing
            if (normalized.startsWith("www.")) {
                normalized = "https://" + normalized;
            }
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                return false;
            }
            java.net.URI uri = java.net.URI.create(normalized);
            String host = uri.getHost();
            if (host == null) return false;
            String path = uri.getPath() != null ? uri.getPath() : "";

            // Exact host or subdomain matching + optional path prefix
            return hostMatches(host, "maps.google.com")
                    || (hostMatches(host, "google.com") && path.startsWith("/maps"))
                    || (hostMatches(host, "goo.gl") && path.startsWith("/maps"))
                    || hostMatches(host, "maps.apple.com")
                    || hostMatches(host, "waze.com")
                    || hostMatches(host, "openstreetmap.org");
        } catch (Exception e) {
            return false; // Malformed URL = not allowed
        }
    }

    /** Check if host exactly matches or is a subdomain of the given domain. */
    private boolean hostMatches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /**
     * Mask URL for logging (security).
     */
    private String maskUrl(String url) {
        if (url.length() > 30) {
            return url.substring(0, 30) + "...";
        }
        return url;
    }

    /**
     * Result of content moderation check.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentModerationResult {
        private boolean approved;
        private String reason;
        private List<String> violations;
        private List<String> flags;  // For admin review queue

        public static ContentModerationResult approved() {
            return ContentModerationResult.builder()
                    .approved(true)
                    .violations(new ArrayList<>())
                    .flags(new ArrayList<>())
                    .build();
        }

        public static ContentModerationResult approvedWithFlags(List<String> flags) {
            return ContentModerationResult.builder()
                    .approved(true)
                    .violations(new ArrayList<>())
                    .flags(flags)
                    .build();
        }

        public static ContentModerationResult rejected(String reason, List<String> violations) {
            return rejected(reason, violations, new ArrayList<>());
        }

        public static ContentModerationResult rejected(String reason, List<String> violations, List<String> flags) {
            return ContentModerationResult.builder()
                    .approved(false)
                    .reason(reason)
                    .violations(violations)
                    .flags(flags)
                    .build();
        }

        public boolean isApproved() {
            return approved;
        }

        public boolean hasFlags() {
            return flags != null && !flags.isEmpty();
        }
    }
}

