package org.example.rentoza.booking.checkin;

/**
 * ID verification status for guest identity checks.
 * 
 * <p>Guest identity is verified through:
 * <ul>
 *   <li>Liveness check (anti-spoofing biometric)</li>
 *   <li>Document validation (driver's license, passport, or national ID)</li>
 *   <li>Name matching (OCR extracted name vs user profile, Serbian-aware)</li>
 * </ul>
 *
 * @see CheckInIdVerification
 */
public enum IdVerificationStatus {
    
    /** Verification not yet started or in progress */
    PENDING,
    
    /** All checks passed: liveness, document valid, name matches */
    PASSED,
    
    /** Failed liveness check (spoofing detected or low confidence) */
    FAILED_LIVENESS,
    
    /** Document expired or will expire before trip end date */
    FAILED_DOCUMENT_EXPIRED,
    
    /** OCR name doesn't match user profile (below 80% Jaro-Winkler threshold) */
    FAILED_NAME_MISMATCH,
    
    /** Document image unreadable (blurry, glare, partial) */
    FAILED_DOCUMENT_UNREADABLE,
    
    /** Document country not accepted (non-Serbian document for Serbia rental) */
    FAILED_DOCUMENT_COUNTRY,
    
    /** Automated checks inconclusive, requires manual review */
    MANUAL_REVIEW,
    
    /** Admin manually approved despite failed automated checks */
    OVERRIDE_APPROVED;
    
    /**
     * Check if this status allows the guest to proceed with check-in.
     * @return true if verification passed or was manually approved
     */
    public boolean isPassed() {
        return this == PASSED || this == OVERRIDE_APPROVED;
    }
    
    /**
     * Check if this status is a failure requiring guest action.
     * @return true if any FAILED_* status
     */
    public boolean isFailed() {
        return name().startsWith("FAILED_");
    }
    
    /**
     * Check if this status requires admin intervention.
     * @return true if MANUAL_REVIEW
     */
    public boolean requiresReview() {
        return this == MANUAL_REVIEW;
    }
}
