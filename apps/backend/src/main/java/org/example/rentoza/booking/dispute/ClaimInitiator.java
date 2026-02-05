package org.example.rentoza.booking.dispute;

/**
 * Identifies who initiated a damage claim or dispute.
 * 
 * <p>Uses consistent role naming with the rest of the app (USER, OWNER, ADMIN).
 * 
 * @since Phase 4 - Guest Dispute Capability
 */
public enum ClaimInitiator {
    
    /**
     * Owner (car owner/host) filed the claim.
     * Typical for checkout damage claims.
     */
    OWNER,
    
    /**
     * User (renter/guest) filed the claim.
     * Typical for check-in disputes (undisclosed damage) or counter-claims.
     */
    USER,
    
    /**
     * Admin filed the claim on behalf of a party.
     * Used for escalations or system-initiated claims.
     */
    ADMIN
}
