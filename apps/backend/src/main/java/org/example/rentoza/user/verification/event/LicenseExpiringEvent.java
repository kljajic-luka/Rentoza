package org.example.rentoza.user.verification.event;

import lombok.Getter;
import org.example.rentoza.user.User;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDate;

/**
 * Event published when a renter's driver license is expiring within 30 days.
 * 
 * <p>This event triggers:
 * <ul>
 *   <li>Email notification warning the renter</li>
 *   <li>In-app notification with update guidance</li>
 * </ul>
 * 
 * <p>This event is typically published by a scheduled job that scans
 * for licenses expiring soon.
 * 
 * @see VerificationEventListener
 */
@Getter
public class LicenseExpiringEvent extends ApplicationEvent {
    
    private final User user;
    private final LocalDate expiryDate;
    private final int daysUntilExpiry;
    
    /**
     * Create a new license expiring event.
     *
     * @param source Event source (typically the scheduled job)
     * @param user The user whose license is expiring
     * @param expiryDate The expiry date of the license
     * @param daysUntilExpiry Days remaining until expiry
     */
    public LicenseExpiringEvent(
            Object source, 
            User user, 
            LocalDate expiryDate,
            int daysUntilExpiry) {
        super(source);
        this.user = user;
        this.expiryDate = expiryDate;
        this.daysUntilExpiry = daysUntilExpiry;
    }
}
