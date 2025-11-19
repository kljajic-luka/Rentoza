package org.example.rentoza.notification.channel;

import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.notification.Notification;
import org.example.rentoza.notification.mail.MailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Email channel for notification delivery.
 * Delegates to MailService for async email sending.
 *
 * Can be disabled via configuration property.
 * MailService is optional - if not available, channel will be disabled.
 */
@Component
@Slf4j
public class EmailNotificationChannel implements NotificationChannel {

    private final Optional<MailService> mailService;

    @Value("${notifications.email.enabled:false}")
    private boolean enabled;

    @Autowired
    public EmailNotificationChannel(@Autowired(required = false) MailService mailService) {
        this.mailService = Optional.ofNullable(mailService);
    }

    @Override
    public void send(Notification notification) {
        if (!isEnabled()) {
            log.debug("Email notifications disabled, skipping notification {}", notification.getId());
            return;
        }

        mailService.ifPresent(service -> {
            try {
                String recipientEmail = notification.getRecipient().getEmail();
                String subject = getSubjectForType(notification.getType().name());

                // Delegate to MailService for async sending
                service.sendNotificationEmail(
                        recipientEmail,
                        subject,
                        notification.getMessage(),
                        notification.getType(),
                        notification.getRelatedEntityId()
                );

                log.debug("Email notification queued for user {} ({})",
                        notification.getRecipient().getId(),
                        recipientEmail);
            } catch (Exception e) {
                log.error("Failed to queue email notification for user {}: {}",
                        notification.getRecipient().getId(),
                        e.getMessage(), e);
            }
        });
    }

    @Override
    public String getChannelName() {
        return "Email";
    }

    @Override
    public boolean isEnabled() {
        return enabled && mailService.isPresent();
    }

    /**
     * Generate email subject based on notification type.
     */
    private String getSubjectForType(String type) {
        return switch (type) {
            case "BOOKING_CONFIRMED" -> "Rezervacija potvrđena - Rentoza";
            case "BOOKING_APPROVED" -> "Rezervacija odobrena - Rentoza";
            case "BOOKING_REQUEST_SENT" -> "Zahtev za rezervaciju poslat - Rentoza";
            case "BOOKING_REQUEST_RECEIVED" -> "Novi zahtev za rezervaciju - Rentoza";
            case "BOOKING_DECLINED" -> "Rezervacija odbijena - Rentoza";
            case "BOOKING_EXPIRED" -> "Rezervacija istekla - Rentoza";
            case "BOOKING_CANCELLED" -> "Rezervacija otkazana - Rentoza";
            case "NEW_MESSAGE" -> "Nova poruka - Rentoza";
            case "REVIEW_RECEIVED" -> "Nova recenzija - Rentoza";
            default -> "Obaveštenje - Rentoza";
        };
    }
}
