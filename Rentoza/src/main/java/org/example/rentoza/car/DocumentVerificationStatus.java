package org.example.rentoza.car;

/**
 * Document verification status for admin workflow.
 */
public enum DocumentVerificationStatus {
    /**
     * Uploaded by owner, awaiting admin review.
     */
    PENDING,
    
    /**
     * Admin verified document is valid and current.
     */
    VERIFIED,
    
    /**
     * Admin rejected (expired, invalid, wrong document, etc.).
     */
    REJECTED,
    
    /**
     * System auto-detected expiration (Post-MVP cron job).
     */
    EXPIRED_AUTO
}
