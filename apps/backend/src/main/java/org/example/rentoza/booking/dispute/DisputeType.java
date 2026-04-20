package org.example.rentoza.booking.dispute;

/**
 * Type/category of damage claim or dispute.
 * 
 * <p>Helps admins categorize and prioritize disputes.
 * 
 * @since VAL-004 - Guest Check-In Dispute Flow
 */
public enum DisputeType {
    
    // ========== CHECK-IN DISPUTE TYPES ==========
    
    /**
     * Guest reports damage that existed before the trip started.
     * Damage was not disclosed in listing or check-in photos.
     * Raised during CHECK_IN stage.
     */
    PRE_EXISTING_DAMAGE,
    
    /**
     * Guest reports vehicle doesn't match listing description.
     * Missing features, different model, etc.
     */
    VEHICLE_MISMATCH,
    
    /**
     * Guest reports vehicle is not clean or has odor issues.
     */
    CLEANLINESS_ISSUE,
    
    // ========== CHECKOUT DISPUTE TYPES ==========
    
    /**
     * Host reports damage that occurred during the rental period.
     * Standard checkout damage claim.
     */
    CHECKOUT_DAMAGE,
    
    /**
     * Host claims vehicle was returned excessively dirty.
     * Cleaning fee dispute.
     */
    CLEANING_FEE,
    
    /**
     * Host claims fuel level is lower than at check-in.
     * Fuel shortage compensation.
     */
    FUEL_SHORTAGE,
    
    /**
     * Host claims excessive mileage beyond booking terms.
     */
    MILEAGE_OVERAGE,
    
    /**
     * Guest returned vehicle late without notification.
     */
    LATE_RETURN,
    
    // ========== GENERAL ==========
    
    /**
     * Other dispute type not covered by categories above.
     * Requires admin notes for clarification.
     */
    OTHER
}
