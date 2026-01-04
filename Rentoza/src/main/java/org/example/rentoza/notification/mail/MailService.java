package org.example.rentoza.notification.mail;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.notification.NotificationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for sending email notifications asynchronously.
 * Uses JavaMailSender with Thymeleaf templates for HTML emails.
 *
 * Templates are cached by Thymeleaf for performance.
 * Only enabled when notifications.email.enabled=true
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "notifications.email.enabled", havingValue = "true")
public class MailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${spring.mail.from:noreply@rentoza.com}")
    private String fromEmail;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${mail.test.enabled:false}")
    private boolean testModeEnabled;

    @Value("${mail.test.recipient:}")
    private String testRecipient;

    private boolean credentialsConfigured = false;

    /**
     * Validate mail configuration on startup.
     * Logs warnings if credentials are missing but doesn't prevent application startup.
     */
    @PostConstruct
    public void validateConfiguration() {
        if (mailUsername == null || mailUsername.isBlank() ||
            mailPassword == null || mailPassword.isBlank()) {

            log.warn("⚠️ Mail credentials missing — email notifications disabled");
            log.warn("To enable email notifications, set the following environment variables:");
            log.warn("  MAIL_USERNAME=your-email@gmail.com");
            log.warn("  MAIL_PASSWORD=your-app-password");
            log.warn("For Gmail, generate an App Password at: https://myaccount.google.com/apppasswords");
            credentialsConfigured = false;
        } else {
            credentialsConfigured = true;
            log.info("✅ Mail service configured with username: {}", mailUsername);

            if (testModeEnabled) {
                log.info("🧪 Test mode enabled - all emails will be redirected to: {}", testRecipient);
            }
        }
    }

    /**
     * Send notification email asynchronously.
     *
     * @param to Recipient email address
     * @param subject Email subject
     * @param message Notification message
     * @param type Notification type for template selection
     * @param relatedEntityId Optional related entity ID
     * @return CompletableFuture that completes when email is sent
     */
    @Async
    public CompletableFuture<Void> sendNotificationEmail(
            String to,
            String subject,
            String message,
            NotificationType type,
            String relatedEntityId) {

        // Check if credentials are configured
        if (!credentialsConfigured) {
            log.debug("Skipping email send - credentials not configured (recipient: {}, type: {})", to, type);
            return CompletableFuture.completedFuture(null);
        }

        try {
            // Apply test email redirection if enabled
            String originalRecipient = to;
            String finalRecipient = to;
            String finalSubject = subject;

            if (testModeEnabled && testRecipient != null && !testRecipient.isBlank()) {
                finalRecipient = testRecipient;
                finalSubject = "[TEST] " + subject;

                log.info("🧪 [TEST-MODE] Redirecting email originally intended for {} → sent to {} (subject: \"{}\", type: {})",
                        originalRecipient,
                        testRecipient,
                        subject,
                        type);
            }

            String htmlContent = buildEmailContent(message, type, relatedEntityId);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(finalRecipient);
            helper.setSubject(finalSubject);
            helper.setText(htmlContent, true); // true = HTML

            mailSender.send(mimeMessage);

            if (testModeEnabled) {
                log.info("✅ Test email sent successfully to {} (originally intended for {})",
                        finalRecipient, originalRecipient);
            } else {
                log.info("Email notification sent successfully to {} for type {}", finalRecipient, type);
            }

            return CompletableFuture.completedFuture(null);

        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Build HTML email content using Thymeleaf templates.
     */
    private String buildEmailContent(String message, NotificationType type, String relatedEntityId) {
        String templateName = getTemplateForType(type);

        Context context = new Context();
        context.setVariable("message", message);
        context.setVariable("relatedEntityId", relatedEntityId);
        context.setVariable("type", type.name());

        // Add type-specific variables
        addTemplateVariables(context, type, relatedEntityId);

        return templateEngine.process(templateName, context);
    }

    /**
     * Select email template based on notification type.
     * Uses a generic check-in/checkout template for workflow notifications.
     */
    private String getTemplateForType(NotificationType type) {
        return switch (type) {
            // Booking lifecycle
            case BOOKING_CONFIRMED -> "emails/booking-confirmed";
            case BOOKING_APPROVED -> "emails/booking-approved";
            case BOOKING_REQUEST_SENT -> "emails/booking-request-sent";
            case BOOKING_REQUEST_RECEIVED -> "emails/booking-request-received";
            case BOOKING_DECLINED -> "emails/booking-declined";
            case BOOKING_EXPIRED -> "emails/booking-expired";
            case BOOKING_CANCELLED -> "emails/booking-cancelled";
            case REVIEW_RECEIVED -> "emails/review-received";
            case NEW_MESSAGE -> "emails/new-message";
            
            // Renter verification templates
            case LICENSE_VERIFICATION_APPROVED -> "emails/license-approved";
            case LICENSE_VERIFICATION_REJECTED -> "emails/license-rejected";
            case LICENSE_EXPIRING_SOON, LICENSE_EXPIRED -> "emails/license-expiring";
            
            // Check-in workflow templates
            case CHECK_IN_WINDOW_OPENED, CHECK_IN_REMINDER, CHECK_IN_HOST_COMPLETE,
                 CHECK_IN_HOST_BEGUN, CHECK_IN_GUEST_BEGUN, HANDSHAKE_CONFIRMED,
                 TRIP_STARTED, NO_SHOW_HOST, NO_SHOW_GUEST, HOTSPOT_MARKED -> "emails/checkin-notification";
            
            // Checkout workflow templates
            case CHECKOUT_WINDOW_OPENED, CHECKOUT_REMINDER, CHECKOUT_GUEST_COMPLETE,
                 CHECKOUT_GUEST_BEGUN, CHECKOUT_DAMAGE_REPORTED, CHECKOUT_COMPLETE,
                 LATE_RETURN_DETECTED -> "emails/checkout-notification";
            
            // Disputes
            case DISPUTE_RESOLVED -> "emails/dispute-resolved";
            
            default -> "emails/generic-notification";
        };
    }

    /**
     * Add type-specific template variables.
     */
    private void addTemplateVariables(Context context, NotificationType type, String relatedEntityId) {
        Map<String, Object> variables = new HashMap<>();

        switch (type) {
            case BOOKING_CONFIRMED, BOOKING_CANCELLED, BOOKING_APPROVED, 
                 BOOKING_REQUEST_SENT, BOOKING_REQUEST_RECEIVED, 
                 BOOKING_DECLINED, BOOKING_EXPIRED -> {
                variables.put("bookingId", relatedEntityId);
                variables.put("bookingUrl", buildBookingUrl(relatedEntityId));
            }
            case REVIEW_RECEIVED -> {
                variables.put("reviewId", relatedEntityId);
                variables.put("reviewUrl", buildReviewUrl(relatedEntityId));
            }
            case NEW_MESSAGE -> {
                variables.put("chatUrl", buildChatUrl());
            }
            // Check-in workflow variables
            case CHECK_IN_WINDOW_OPENED, CHECK_IN_REMINDER, CHECK_IN_HOST_COMPLETE,
                 CHECK_IN_HOST_BEGUN, CHECK_IN_GUEST_BEGUN, HANDSHAKE_CONFIRMED,
                 TRIP_STARTED, NO_SHOW_HOST, NO_SHOW_GUEST, HOTSPOT_MARKED -> {
                variables.put("bookingId", relatedEntityId);
                variables.put("checkInUrl", buildCheckInUrl(relatedEntityId));
                variables.put("isCheckIn", true);
                variables.put("notificationType", type.name());
                variables.put("actionRequired", isActionRequired(type));
                variables.put("urgencyLevel", getUrgencyLevel(type));
            }
            // Checkout workflow variables
            case CHECKOUT_WINDOW_OPENED, CHECKOUT_REMINDER, CHECKOUT_GUEST_COMPLETE,
                 CHECKOUT_GUEST_BEGUN, CHECKOUT_DAMAGE_REPORTED, CHECKOUT_COMPLETE,
                 LATE_RETURN_DETECTED -> {
                variables.put("bookingId", relatedEntityId);
                variables.put("checkOutUrl", buildCheckOutUrl(relatedEntityId));
                variables.put("isCheckOut", true);
                variables.put("notificationType", type.name());
                variables.put("actionRequired", isActionRequired(type));
                variables.put("urgencyLevel", getUrgencyLevel(type));
            }
            case DISPUTE_RESOLVED -> {
                variables.put("bookingId", relatedEntityId);
                variables.put("bookingUrl", buildBookingUrl(relatedEntityId));
            }
            default -> {
                // Generic notification - no extra variables
            }
        }

        variables.forEach(context::setVariable);
    }

    /**
     * Determine if the notification type requires user action.
     */
    private boolean isActionRequired(NotificationType type) {
        return switch (type) {
            case CHECK_IN_WINDOW_OPENED, CHECK_IN_REMINDER, CHECK_IN_HOST_COMPLETE,
                 CHECKOUT_WINDOW_OPENED, CHECKOUT_REMINDER, CHECKOUT_GUEST_COMPLETE,
                 NO_SHOW_HOST, NO_SHOW_GUEST, CHECKOUT_DAMAGE_REPORTED, LATE_RETURN_DETECTED -> true;
            default -> false;
        };
    }

    /**
     * Get urgency level for email styling: HIGH, MEDIUM, LOW.
     */
    private String getUrgencyLevel(NotificationType type) {
        return switch (type) {
            case NO_SHOW_HOST, NO_SHOW_GUEST, CHECKOUT_DAMAGE_REPORTED, 
                 LATE_RETURN_DETECTED -> "HIGH";
            case CHECK_IN_REMINDER, CHECKOUT_REMINDER, CHECK_IN_WINDOW_OPENED,
                 CHECK_IN_HOST_COMPLETE, CHECKOUT_GUEST_COMPLETE -> "MEDIUM";
            default -> "LOW";
        };
    }

    /**
     * Build deep link URLs for email CTAs.
     */
    private String buildBookingUrl(String bookingId) {
        return "https://rentoza.com/bookings/" + bookingId;
    }

    private String buildCheckInUrl(String bookingId) {
        return "https://rentoza.com/bookings/" + bookingId + "/check-in";
    }

    private String buildCheckOutUrl(String bookingId) {
        return "https://rentoza.com/bookings/" + bookingId + "/check-out";
    }

    private String buildReviewUrl(String reviewId) {
        return "https://rentoza.com/reviews/" + reviewId;
    }

    private String buildChatUrl() {
        return "https://rentoza.com/chat";
    }
}
