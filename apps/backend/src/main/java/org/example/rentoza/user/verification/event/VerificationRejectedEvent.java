package org.example.rentoza.user.verification.event;

import lombok.Getter;
import org.example.rentoza.user.User;
import org.springframework.context.ApplicationEvent;

/**
 * Event published when a renter's driver license verification is rejected.
 * 
 * <p>This event triggers:
 * <ul>
 *   <li>Email notification to the renter with rejection reason</li>
 *   <li>In-app notification with resubmission guidance</li>
 *   <li>Push notification (if enabled)</li>
 * </ul>
 * 
 * <p>Listeners handle notification delivery asynchronously to avoid
 * blocking the rejection transaction.
 * 
 * @see VerificationEventListener
 */
@Getter
public class VerificationRejectedEvent extends ApplicationEvent {
    
    private final User user;
    private final String rejectionReason;
    private final String rejectedBy;
    
    /**
     * Create a new verification rejected event.
     *
     * @param source Event source (typically the service publishing the event)
     * @param user The user whose verification was rejected
     * @param rejectionReason Human-readable reason for rejection (shown to user)
     * @param rejectedBy Email/name of the admin who rejected
     */
    public VerificationRejectedEvent(
            Object source, 
            User user, 
            String rejectionReason,
            String rejectedBy) {
        super(source);
        this.user = user;
        this.rejectionReason = rejectionReason;
        this.rejectedBy = rejectedBy;
    }
}
