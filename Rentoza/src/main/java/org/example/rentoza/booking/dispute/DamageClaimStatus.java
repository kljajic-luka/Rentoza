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
    ARCHIVED,
    
    // ========== CHECK-IN DISPUTE STATUSES (VAL-004) ==========
    
    /**
     * Guest has disputed pre-existing damage at check-in.
     * Awaiting admin review. Booking is in CHECK_IN_DISPUTE status.
     */
    CHECK_IN_DISPUTE_PENDING,
    
    /**
     * Admin resolved: Damage noted, trip proceeds.
     * Guest liability waived for documented damage.
     */
    CHECK_IN_RESOLVED_PROCEED,
    
    /**
     * Admin resolved: Booking cancelled.
     * Full refund issued to guest due to undisclosed damage.
     */
    CHECK_IN_RESOLVED_CANCEL,
    
    /**
     * Guest withdrew their check-in dispute.
     * Either accepted condition or self-cancelled booking.
     */
    CHECK_IN_GUEST_WITHDREW,
    
    // ========== CHECKOUT DAMAGE DISPUTE STATUSES (VAL-010) ==========
    
    /**
     * Host reported damage at checkout, awaiting guest response (VAL-010).
     * Guest has 7 days to accept or dispute the claim.
     * Deposit is held until resolution.
     */
    CHECKOUT_PENDING,
    
    /**
     * Guest accepted the damage claim at checkout (VAL-010).
     * Deposit will be captured to cover damage charges.
     */
    CHECKOUT_GUEST_ACCEPTED,
    
    /**
     * Guest disputed the damage claim at checkout (VAL-010).
     * Escalated to admin for resolution.
     */
    CHECKOUT_GUEST_DISPUTED,
    
    /**
     * Admin resolved checkout damage dispute - host claim approved (VAL-010).
     * Deposit captured for damage payment.
     */
    CHECKOUT_ADMIN_APPROVED,
    
    /**
     * Admin resolved checkout damage dispute - host claim rejected (VAL-010).
     * Deposit released back to guest.
     */
    CHECKOUT_ADMIN_REJECTED,
    
    /**
     * Checkout dispute timed out (7 days) - auto-escalated to admin (VAL-010).
     */
    CHECKOUT_TIMEOUT_ESCALATED;
    
    /**
      * Check if the claim is still open (awaiting action).
      */
    public boolean isOpen() {
        return this == PENDING || this == DISPUTED || this == CHECK_IN_DISPUTE_PENDING ||
               this == CHECKOUT_PENDING || this == CHECKOUT_GUEST_DISPUTED ||
               this == CHECKOUT_TIMEOUT_ESCALATED;
    }
    
    /**
     * Check if the claim was approved (any method).
     */
    public boolean isApproved() {
        return this == ACCEPTED_BY_GUEST || this == AUTO_APPROVED || this == ADMIN_APPROVED ||
               this == CHECKOUT_GUEST_ACCEPTED || this == CHECKOUT_ADMIN_APPROVED;
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
        return this == PAID || this == ADMIN_REJECTED || this == CANCELLED ||
               this == CHECK_IN_RESOLVED_PROCEED || this == CHECK_IN_RESOLVED_CANCEL ||
               this == CHECK_IN_GUEST_WITHDREW ||
               this == CHECKOUT_GUEST_ACCEPTED || this == CHECKOUT_ADMIN_APPROVED ||
               this == CHECKOUT_ADMIN_REJECTED;
    }
    
    /**
     * Check if this is a check-in stage dispute.
     */
    public boolean isCheckInDispute() {
        return this == CHECK_IN_DISPUTE_PENDING || this == CHECK_IN_RESOLVED_PROCEED ||
               this == CHECK_IN_RESOLVED_CANCEL || this == CHECK_IN_GUEST_WITHDREW;
    }
    
    /**
     * Check if this is a checkout damage dispute (VAL-010).
     */
    public boolean isCheckoutDispute() {
        return this == CHECKOUT_PENDING || this == CHECKOUT_GUEST_ACCEPTED ||
               this == CHECKOUT_GUEST_DISPUTED || this == CHECKOUT_ADMIN_APPROVED ||
               this == CHECKOUT_ADMIN_REJECTED || this == CHECKOUT_TIMEOUT_ESCALATED;
    }
    
    /**
     * Check if deposit should be held for this status.
     */
    public boolean requiresDepositHold() {
        return this == CHECKOUT_PENDING || this == CHECKOUT_GUEST_DISPUTED ||
               this == CHECKOUT_TIMEOUT_ESCALATED;
    }
}


