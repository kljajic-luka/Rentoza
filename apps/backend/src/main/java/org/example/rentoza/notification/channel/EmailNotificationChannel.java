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
     * Enterprise-grade subjects with clear action items and context.
     */
    private String getSubjectForType(String type) {
        return switch (type) {
            // Booking lifecycle
            case "BOOKING_CONFIRMED" -> "Rezervacija potvrđena - Rentoza";
            case "BOOKING_APPROVED" -> "Rezervacija odobrena - Rentoza";
            case "BOOKING_REQUEST_SENT" -> "Zahtev za rezervaciju poslat - Rentoza";
            case "BOOKING_REQUEST_RECEIVED" -> "Novi zahtev za rezervaciju - Rentoza";
            case "BOOKING_DECLINED" -> "Rezervacija odbijena - Rentoza";
            case "BOOKING_EXPIRED" -> "Rezervacija istekla - Rentoza";
            case "BOOKING_APPROVAL_REMINDER" -> "⏰ Podsetnik: zahtev uskoro ističe - Rentoza";
            case "BOOKING_CANCELLED" -> "Rezervacija otkazana - Rentoza";
            case "NEW_MESSAGE" -> "Nova poruka - Rentoza";
            case "REVIEW_RECEIVED" -> "Nova recenzija - Rentoza";
            
            // Renter verification
            case "LICENSE_VERIFICATION_APPROVED" -> "Vozačka dozvola verifikovana - Rentoza";
            case "LICENSE_VERIFICATION_REJECTED" -> "Vozačka dozvola - potrebna ponovna provera - Rentoza";
            case "LICENSE_EXPIRING_SOON" -> "Vozačka dozvola ističe uskoro - Rentoza";
            case "LICENSE_EXPIRED" -> "Vozačka dozvola je istekla - Rentoza";
            
            // Check-in workflow (Host actions)
            case "CHECK_IN_WINDOW_OPENED" -> "⏰ Prijem vozila otvoren - potrebna akcija - Rentoza";
            case "CHECK_IN_REMINDER" -> "⚠️ Podsetnik: Završite prijem vozila - Rentoza";
            case "CHECK_IN_HOST_COMPLETE" -> "✅ Domaćin završio prijem - vaš red - Rentoza";
            case "CHECK_IN_HOST_BEGUN" -> "🚗 Domaćin je započeo prijem vozila - Rentoza";
            case "CHECK_IN_GUEST_BEGUN" -> "👤 Gost je započeo pregled vozila - Rentoza";
            
            // Handshake & Trip start
            case "HANDSHAKE_CONFIRMED" -> "🤝 Primopredaja potvrđena - Rentoza";
            case "TRIP_STARTED" -> "🚀 Putovanje započeto - srećan put! - Rentoza";
            
            // No-show scenarios
            case "NO_SHOW_HOST" -> "❌ Domaćin nije izvršio prijem - sledeći koraci - Rentoza";
            case "NO_SHOW_GUEST" -> "❌ Gost nije preuzeo vozilo - sledeći koraci - Rentoza";
            case "HOTSPOT_MARKED" -> "📍 Gost je označio oštećenje na vozilu - Rentoza";
            
            // Checkout workflow
            case "CHECKOUT_WINDOW_OPENED" -> "⏰ Vraćanje vozila - priprema za povratak - Rentoza";
            case "CHECKOUT_REMINDER" -> "⚠️ Podsetnik: Vreme za vraćanje vozila - Rentoza";
            case "CHECKOUT_GUEST_COMPLETE" -> "✅ Gost završio povratak - potrebna potvrda - Rentoza";
            case "CHECKOUT_GUEST_BEGUN" -> "🔄 Gost je započeo vraćanje vozila - Rentoza";
            case "CHECKOUT_DAMAGE_REPORTED" -> "⚠️ Prijavljeno novo oštećenje na vozilu - Rentoza";
            case "CHECKOUT_COMPLETE" -> "✅ Vozilo uspešno vraćeno - Rentoza";
            case "LATE_RETURN_DETECTED" -> "⚠️ Kašnjenje sa povratkom vozila - Rentoza";
            
            // Disputes
            case "DISPUTE_RESOLVED" -> "📋 Spor je rešen - Rentoza";
            
            default -> "Obaveštenje - Rentoza";
        };
    }
}
