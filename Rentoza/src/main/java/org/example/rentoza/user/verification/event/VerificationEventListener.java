package org.example.rentoza.user.verification.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.notification.mail.MailService;
import org.example.rentoza.user.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Async event listeners for renter verification lifecycle events.
 * 
 * <p>Handles:
 * <ul>
 *   <li>{@link VerificationApprovedEvent} - Send approval email and notification</li>
 *   <li>{@link VerificationRejectedEvent} - Send rejection email with reason</li>
 *   <li>{@link LicenseExpiringEvent} - Send expiry warning notification</li>
 * </ul>
 * 
 * <p>All handlers run asynchronously via the {@code notificationExecutor} thread pool,
 * ensuring verification service operations are not blocked by notification delivery.
 * 
 * <p>Email failures are logged but do not propagate - we don't want notification
 * failures to affect the core verification workflow.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class VerificationEventListener {
    
    private final NotificationService notificationService;
    private final TemplateEngine templateEngine;
    
    // MailService is optional - may not be configured in all environments
    @Autowired(required = false)
    private MailService mailService;
    
    private static final DateTimeFormatter DATE_FORMAT_SR = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    
    // ==================== APPROVAL ====================
    
    /**
     * Send approval email and in-app notification to renter.
     * Runs async - doesn't block verification approval API.
     */
    @EventListener
    @Async("notificationExecutor")
    public void onVerificationApproved(VerificationApprovedEvent event) {
        User user = event.getUser();
        log.info("Processing verification approved event: userId={}", user.getId());
        
        try {
            // Send in-app notification
            String message = buildApprovalMessage(event);
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(user.getId())
                .type(NotificationType.LICENSE_VERIFICATION_APPROVED)
                .message(message)
                .relatedEntityId(String.valueOf(user.getId()))
                .build());
            
            // Send email if mail service is configured
            if (mailService != null) {
                sendApprovalEmail(event);
            }
            
            log.info("Verification approved notifications sent: userId={}, email={}", 
                user.getId(), user.getEmail());
                
        } catch (Exception e) {
            // Log but don't throw - notification failure shouldn't affect core workflow
            log.error("Failed to send verification approved notifications: userId={}", 
                user.getId(), e);
        }
    }
    
    private String buildApprovalMessage(VerificationApprovedEvent event) {
        String expiryFormatted = event.getLicenseExpiryDate().format(DATE_FORMAT_SR);
        return String.format(
            "Vaša vozačka dozvola je verifikovana! Možete sada rezervisati vozila. " +
            "Dozvola ističe %s.", 
            expiryFormatted
        );
    }
    
    private void sendApprovalEmail(VerificationApprovedEvent event) {
        User user = event.getUser();
        
        Context context = new Context();
        context.setVariable("firstName", user.getFirstName());
        context.setVariable("expiryDate", event.getLicenseExpiryDate().format(DATE_FORMAT_SR));
        context.setVariable("verifiedBy", event.getVerifiedBy());
        
        String htmlContent = templateEngine.process("emails/license-approved", context);
        
        mailService.sendNotificationEmail(
            user.getEmail(),
            "Vozačka dozvola verifikovana - Rentoza",
            htmlContent,
            NotificationType.LICENSE_VERIFICATION_APPROVED,
            String.valueOf(user.getId())
        );
    }
    
    // ==================== REJECTION ====================
    
    /**
     * Send rejection email and in-app notification to renter.
     * Includes rejection reason and resubmission guidance.
     */
    @EventListener
    @Async("notificationExecutor")
    public void onVerificationRejected(VerificationRejectedEvent event) {
        User user = event.getUser();
        log.info("Processing verification rejected event: userId={}", user.getId());
        
        try {
            // Send in-app notification
            String message = buildRejectionMessage(event);
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(user.getId())
                .type(NotificationType.LICENSE_VERIFICATION_REJECTED)
                .message(message)
                .relatedEntityId(String.valueOf(user.getId()))
                .build());
            
            // Send email if mail service is configured
            if (mailService != null) {
                sendRejectionEmail(event);
            }
            
            log.info("Verification rejected notifications sent: userId={}, reason={}", 
                user.getId(), event.getRejectionReason());
                
        } catch (Exception e) {
            log.error("Failed to send verification rejected notifications: userId={}", 
                user.getId(), e);
        }
    }
    
    private String buildRejectionMessage(VerificationRejectedEvent event) {
        return String.format(
            "Vaša vozačka dozvola nije prihvaćena: %s. " +
            "Molimo ponovo pošaljite ispravne dokumente.", 
            event.getRejectionReason()
        );
    }
    
    private void sendRejectionEmail(VerificationRejectedEvent event) {
        User user = event.getUser();
        
        Context context = new Context();
        context.setVariable("firstName", user.getFirstName());
        context.setVariable("rejectionReason", event.getRejectionReason());
        context.setVariable("rejectedBy", event.getRejectedBy());
        
        String htmlContent = templateEngine.process("emails/license-rejected", context);
        
        mailService.sendNotificationEmail(
            user.getEmail(),
            "Vozačka dozvola - potrebna ponovna provera - Rentoza",
            htmlContent,
            NotificationType.LICENSE_VERIFICATION_REJECTED,
            String.valueOf(user.getId())
        );
    }
    
    // ==================== EXPIRING ====================
    
    /**
     * Send expiry warning notification to renter.
     * Triggered by scheduled job scanning for licenses expiring soon.
     */
    @EventListener
    @Async("notificationExecutor")
    public void onLicenseExpiring(LicenseExpiringEvent event) {
        User user = event.getUser();
        log.info("Processing license expiring event: userId={}, daysUntil={}", 
            user.getId(), event.getDaysUntilExpiry());
        
        try {
            // Send in-app notification
            String message = buildExpiringMessage(event);
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(user.getId())
                .type(NotificationType.LICENSE_EXPIRING_SOON)
                .message(message)
                .relatedEntityId(String.valueOf(user.getId()))
                .build());
            
            // Send email if mail service is configured
            if (mailService != null) {
                sendExpiringEmail(event);
            }
            
            log.info("License expiring notifications sent: userId={}, expiryDate={}", 
                user.getId(), event.getExpiryDate());
                
        } catch (Exception e) {
            log.error("Failed to send license expiring notifications: userId={}", 
                user.getId(), e);
        }
    }
    
    private String buildExpiringMessage(LicenseExpiringEvent event) {
        return String.format(
            "Vaša vozačka dozvola ističe za %d dana (%s). " +
            "Molimo ažurirajte dozvolu pre isteka da biste nastavili sa rezervacijama.", 
            event.getDaysUntilExpiry(),
            event.getExpiryDate().format(DATE_FORMAT_SR)
        );
    }
    
    private void sendExpiringEmail(LicenseExpiringEvent event) {
        User user = event.getUser();
        
        Context context = new Context();
        context.setVariable("firstName", user.getFirstName());
        context.setVariable("expiryDate", event.getExpiryDate().format(DATE_FORMAT_SR));
        context.setVariable("daysUntilExpiry", event.getDaysUntilExpiry());
        
        String htmlContent = templateEngine.process("emails/license-expiring", context);
        
        mailService.sendNotificationEmail(
            user.getEmail(),
            "Vozačka dozvola ističe uskoro - Rentoza",
            htmlContent,
            NotificationType.LICENSE_EXPIRING_SOON,
            String.valueOf(user.getId())
        );
    }
}
