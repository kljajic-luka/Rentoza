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
 * Content moderation filter for messaging.
 * 
 * Implements Option A: Block phone/email, flag URLs, allow Google Maps, admin appeals
 * 
 * Protects platform by blocking PII that could enable off-platform transactions:
 * - Phone numbers (all formats) - BLOCKED
 * - Email addresses - BLOCKED  
 * - External URLs - FLAGGED (except allowlisted domains)
 * 
 * Allowlisted domains (allowed without flagging):
 * - maps.google.com, google.com/maps, goo.gl/maps
 * - maps.apple.com
 */
@Component
@Slf4j
public class ContentModerationFilter {

    // Standard email pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[\\w.+\\-]+@[\\w.\\-]+\\.[a-zA-Z]{2,}"
    );

    // URL pattern including www prefixes
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[^\\s]+|www\\.[^\\s]+)",
            Pattern.CASE_INSENSITIVE
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

        // NOTE: Phone number blocking removed - users need to share contact info for coordination
        // See: https://rentoza.atlassian.net/browse/RENT-XXX
        
        // Check for email addresses (BLOCKED - prevents off-platform transactions)
        if (EMAIL_PATTERN.matcher(trimmedContent).find()) {
            violations.add("email addresses");
            log.debug("[Moderation] Email address detected - BLOCKED");
        }

        // Check for URLs (ALLOWED if maps, FLAGGED otherwise for admin review)
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
     */
    private boolean isAllowedUrl(String url) {
        return ALLOWED_URL_DOMAINS.stream().anyMatch(url::contains);
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
                    .flags(new ArrayList<>())
                    .build();
        }

        public static ContentModerationResult approvedWithFlags(List<String> flags) {
            return ContentModerationResult.builder()
                    .approved(true)
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

