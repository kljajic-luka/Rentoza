package org.example.rentoza.user.verification.event;

import lombok.Getter;
import org.example.rentoza.user.User;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDate;

/**
 * Event published when a renter's driver license verification is approved.
 * 
 * <p>This event triggers:
 * <ul>
 *   <li>Email notification to the renter</li>
 *   <li>In-app notification</li>
 *   <li>Push notification (if enabled)</li>
 * </ul>
 * 
 * <p>Listeners handle notification delivery asynchronously to avoid
 * blocking the approval transaction.
 * 
 * @see VerificationEventListener
 */
@Getter
public class VerificationApprovedEvent extends ApplicationEvent {
    
    private final User user;
    private final LocalDate licenseExpiryDate;
    private final String verifiedBy;
    private final String adminNotes;
    
    /**
     * Create a new verification approved event.
     *
     * @param source Event source (typically the service publishing the event)
     * @param user The user whose verification was approved
     * @param licenseExpiryDate Expiry date of the verified license
     * @param verifiedBy Email/name of the admin who approved (or "SYSTEM" for auto-approval)
     * @param adminNotes Optional notes from the admin
     */
    public VerificationApprovedEvent(
            Object source, 
            User user, 
            LocalDate licenseExpiryDate,
            String verifiedBy,
            String adminNotes) {
        super(source);
        this.user = user;
        this.licenseExpiryDate = licenseExpiryDate;
        this.verifiedBy = verifiedBy;
        this.adminNotes = adminNotes;
    }
    
    /**
     * Convenience constructor for system auto-approval.
     */
    public static VerificationApprovedEvent autoApproved(Object source, User user, LocalDate expiryDate) {
        return new VerificationApprovedEvent(source, user, expiryDate, "SYSTEM", "Auto-approved based on risk assessment");
    }
}
