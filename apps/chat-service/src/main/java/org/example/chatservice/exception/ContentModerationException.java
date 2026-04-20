package org.example.chatservice.exception;

import lombok.Getter;

import java.util.List;

/**
 * Exception thrown when message content violates content moderation policies.
 * 
 * Includes violation type for frontend to show specific user messages.
 */
@Getter
public class ContentModerationException extends RuntimeException {

    /**
     * Types of content moderation violations.
     */
    public enum ViolationType {
        EMAIL_ADDRESS("EMAIL_ADDRESS", "Poruka ne može sadržavati email adrese. Molimo koristite platformu za komunikaciju."),
        EXTERNAL_LINK("EXTERNAL_LINK", "Poruka sadrži nedozvoljene linkove."),
        SPAM("SPAM", "Poruka je označena kao neželjena."),
        INAPPROPRIATE("INAPPROPRIATE", "Poruka sadrži neprikladan sadržaj."),
        OTHER("OTHER", "Poruka nije dozvoljena.");

        private final String code;
        private final String userMessage;

        ViolationType(String code, String userMessage) {
            this.code = code;
            this.userMessage = userMessage;
        }

        public String getCode() {
            return code;
        }

        public String getUserMessage() {
            return userMessage;
        }
    }

    private final ViolationType violationType;
    private final List<String> violations;
    private final String userMessage;

    public ContentModerationException(String message) {
        super(message);
        this.violationType = ViolationType.OTHER;
        this.violations = List.of();
        this.userMessage = ViolationType.OTHER.getUserMessage();
    }

    public ContentModerationException(String message, ViolationType violationType) {
        super(message);
        this.violationType = violationType;
        this.violations = List.of();
        this.userMessage = violationType.getUserMessage();
    }

    public ContentModerationException(String message, ViolationType violationType, List<String> violations) {
        super(message);
        this.violationType = violationType;
        this.violations = violations != null ? violations : List.of();
        this.userMessage = violationType.getUserMessage();
    }

    /**
     * Factory method to create exception from ContentModerationResult.
     */
    public static ContentModerationException fromViolations(String reason, List<String> violations) {
        ViolationType type = ViolationType.OTHER;
        
        if (violations != null && !violations.isEmpty()) {
            String firstViolation = violations.get(0).toLowerCase();
            if (firstViolation.contains("email")) {
                type = ViolationType.EMAIL_ADDRESS;
            } else if (firstViolation.contains("link") || firstViolation.contains("url")) {
                type = ViolationType.EXTERNAL_LINK;
            } else if (firstViolation.contains("spam")) {
                type = ViolationType.SPAM;
            }
        }
        
        return new ContentModerationException(reason, type, violations);
    }
}
