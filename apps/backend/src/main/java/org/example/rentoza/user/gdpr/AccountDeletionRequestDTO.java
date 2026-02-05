package org.example.rentoza.user.gdpr;

import lombok.Data;

/**
 * Request DTO for account deletion.
 */
@Data
public class AccountDeletionRequestDTO {
    /**
     * Email address for confirmation (must match account email).
     */
    private String confirmEmail;
    
    /**
     * Optional reason for deletion (for analytics).
     */
    private String reason;
}
