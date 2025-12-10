package org.example.rentoza.booking.dispute;

/**
 * Status of a damage claim during checkout.
 * 
 * <h2>State Flow</h2>
 * <pre>
 * PENDING ─┬─[Guest accepts]──────────► ACCEPTED_BY_GUEST
 *          ├─[Guest disputes]─────────► DISPUTED
 *          └─[Auto-expire 72h]────────► AUTO_APPROVED
 * 
 * DISPUTED ─┬─[Admin approves]────────► ADMIN_APPROVED
 *           └─[Admin rejects]─────────► ADMIN_REJECTED
 * 
 * ACCEPTED_BY_GUEST ──[Payment]───────► PAID
 * ADMIN_APPROVED ─────[Payment]───────► PAID
 * </pre>
 */
public enum DamageClaimStatus {
    
    /**
     * Initial state - host has submitted claim, awaiting guest response.
     */
    PENDING,
    
    /**
     * Guest has accepted the damage claim.
     */
    ACCEPTED_BY_GUEST,
    
    /**
     * Guest has disputed the damage claim.
     * Requires admin review.
     */
    DISPUTED,
    
    /**
     * Claim was auto-approved due to guest non-response (72h timeout).
     */
    AUTO_APPROVED,
    
    /**
     * Claim has been escalated to senior admin review.
     */
    ESCALATED,
    
    /**
     * Admin reviewed and approved the claim.
     */
    ADMIN_APPROVED,
    
    /**
     * Admin reviewed and rejected the claim.
     */
    ADMIN_REJECTED,
    
    /**
     * Damage payment has been processed.
     */
    PAID,
    
    /**
      * Claim was cancelled by the host.
      */
    CANCELLED,
    
    /**
      * Claim requires manual review by senior admin.
      */
    REQUIRES_MANUAL_REVIEW,
    
    /**
      * Claim has been archived (historical record).
      */
    ARCHIVED;
    
    /**
      * Check if the claim is still open (awaiting action).
      */
    public boolean isOpen() {
        return this == PENDING || this == DISPUTED;
    }
    
    /**
     * Check if the claim was approved (any method).
     */
    public boolean isApproved() {
        return this == ACCEPTED_BY_GUEST || this == AUTO_APPROVED || this == ADMIN_APPROVED;
    }
    
    /**
     * Check if the claim requires guest payment.
     */
    public boolean requiresPayment() {
        return isApproved() && this != PAID;
    }
    
    /**
     * Check if the claim is resolved (no further action needed).
     */
    public boolean isResolved() {
        return this == PAID || this == ADMIN_REJECTED || this == CANCELLED;
    }
}


