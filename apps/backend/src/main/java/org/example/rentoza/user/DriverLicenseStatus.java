package org.example.rentoza.user;

/**
 * Verification states for renter driver license.
 * 
 * <p>State machine:
 * <pre>
 * NOT_STARTED → PENDING_REVIEW → APPROVED
 *                      ↓
 *                  REJECTED → (re-submit) → PENDING_REVIEW
 *                      
 * APPROVED → EXPIRED (auto, when license expires)
 *     ↓
 * SUSPENDED (admin action for fraud/abuse)
 * </pre>
 * 
 * <p>Booking eligibility requires: {@code APPROVED} status with non-expired license.
 */
public enum DriverLicenseStatus {
    
    /**
     * User hasn't started license verification.
     * No documents uploaded yet.
     */
    NOT_STARTED("Verifikacija nije započeta", false),
    
    /**
     * User submitted license, awaiting automated/manual review.
     * Documents uploaded, processing in progress.
     */
    PENDING_REVIEW("Čeka pregled", false),
    
    /**
     * License verified by admin/system. User can book.
     * Must also check expiry date before allowing booking.
     */
    APPROVED("Verifikovano", true),
    
    /**
     * License rejected (expired, invalid, unreadable, name mismatch).
     * User must re-submit new/corrected documents.
     */
    REJECTED("Odbijeno", false),
    
    /**
     * License was approved but has since expired.
     * Auto-set by system cron job. User must re-verify.
     */
    EXPIRED("Isteklo", false),
    
    /**
     * Admin suspended verification due to fraud/abuse.
     * Requires manual review to lift. User cannot book.
     */
    SUSPENDED("Suspendovano", false);
    
    private final String serbianName;
    private final boolean allowsBooking;
    
    DriverLicenseStatus(String serbianName, boolean allowsBooking) {
        this.serbianName = serbianName;
        this.allowsBooking = allowsBooking;
    }
    
    /**
     * Serbian display name for UI.
     */
    public String getSerbianName() {
        return serbianName;
    }
    
    /**
     * Whether this status allows creating a booking (subject to expiry check).
     */
    public boolean allowsBooking() {
        return allowsBooking;
    }
    
    /**
     * Whether this status requires admin attention.
     */
    public boolean requiresReview() {
        return this == PENDING_REVIEW;
    }
    
    /**
     * Whether user can re-submit documents in this status.
     * 
     * Includes PENDING_REVIEW to allow users to upload additional documents
     * (e.g., license back after front) while their submission is pending.
     */
    public boolean canResubmit() {
        return this == REJECTED || this == EXPIRED || this == NOT_STARTED || this == PENDING_REVIEW;
    }
    
    /**
     * Whether this is a terminal negative status (requires intervention).
     */
    public boolean isBlocked() {
        return this == SUSPENDED;
    }
}
