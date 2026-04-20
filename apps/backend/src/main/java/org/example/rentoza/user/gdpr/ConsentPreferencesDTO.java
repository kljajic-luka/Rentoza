package org.example.rentoza.user.gdpr;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * DTO for user consent preferences.
 */
@Data
public class ConsentPreferencesDTO {
    private Long userId;
    
    /**
     * Consent to receive marketing emails and newsletters.
     */
    private boolean marketingEmails;
    
    /**
     * Consent to receive SMS notifications (booking reminders, promotions).
     */
    private boolean smsNotifications;
    
    /**
     * Consent to analytics tracking for service improvement.
     */
    private boolean analyticsTracking;
    
    /**
     * Consent to share data with third parties (insurance partners, etc.).
     */
    private boolean thirdPartySharing;
    
    private LocalDateTime lastUpdated;
}
