package org.example.rentoza.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FeatureFlags {

    public static final String RENTER_VERIFICATION_ENABLED = "feature.renter-verification.enabled";
    public static final String RENTER_VERIFICATION_ASYNC_PROCESSING = "feature.renter-verification.async-processing";
    public static final String RENTER_VERIFICATION_STRICT_CHECKIN = "feature.renter-verification.strict-checkin";
    public static final String RENTER_VERIFICATION_ROLLOUT_PERCENT = "feature.renter-verification.rollout-percent";
    
    // Rental Agreement Compliance Feature Flags
    public static final String RENTAL_AGREEMENT_CHECKIN_ENFORCED = "app.compliance.rental-agreement.checkin-enforced";

    // Dual-Party Photo Verification Feature Flags
    public static final String DUAL_PARTY_PHOTOS_ENABLED = "feature.dual-party-photos.enabled";
    public static final String DUAL_PARTY_PHOTOS_ROLLOUT_PERCENT = "feature.dual-party-photos.rollout-percent";
    public static final String DUAL_PARTY_PHOTOS_REQUIRED_FOR_HANDSHAKE = "feature.dual-party-photos.required-for-handshake";

    @Value("${" + RENTER_VERIFICATION_ENABLED + ":true}")
    private boolean renterVerificationEnabled;

    @Value("${" + RENTER_VERIFICATION_ASYNC_PROCESSING + ":true}")
    private boolean asyncProcessingEnabled;

    @Value("${" + RENTER_VERIFICATION_STRICT_CHECKIN + ":true}")
    private boolean strictCheckinEnabled;

    @Value("${" + RENTER_VERIFICATION_ROLLOUT_PERCENT + ":100}")
    private int rolloutPercent;
    
    // Dual-Party Photo Verification Flags
    @Value("${" + DUAL_PARTY_PHOTOS_ENABLED + ":false}")
    private boolean dualPartyPhotosEnabled;
    
    @Value("${" + DUAL_PARTY_PHOTOS_ROLLOUT_PERCENT + ":0}")
    private int dualPartyPhotosRolloutPercent;
    
    @Value("${" + DUAL_PARTY_PHOTOS_REQUIRED_FOR_HANDSHAKE + ":false}")
    private boolean dualPartyPhotosRequiredForHandshake;

    // Rental Agreement Enforcement (fail-safe: true prevents trips without signed agreements)
    @Value("${" + RENTAL_AGREEMENT_CHECKIN_ENFORCED + ":true}")
    private boolean rentalAgreementCheckinEnforced;

    public boolean isRenterVerificationEnabled() {
        return renterVerificationEnabled;
    }

    public boolean isAsyncProcessingEnabled() {
        return asyncProcessingEnabled;
    }

    public boolean isStrictCheckinEnabled() {
        return strictCheckinEnabled;
    }

    public int getRolloutPercent() {
        return rolloutPercent;
    }
    
    // ========== Dual-Party Photo Methods ==========
    
    /**
     * Check if dual-party photo verification is enabled globally.
     */
    public boolean isDualPartyPhotosEnabled() {
        return dualPartyPhotosEnabled;
    }
    
    /**
     * Get rollout percentage for dual-party photos.
     */
    public int getDualPartyPhotosRolloutPercent() {
        return dualPartyPhotosRolloutPercent;
    }
    
    /**
     * Check if dual-party photos are required before handshake can proceed.
     * When true, guests MUST upload their photos before completing check-in.
     */
    public boolean isDualPartyPhotosRequiredForHandshake() {
        return dualPartyPhotosEnabled && dualPartyPhotosRequiredForHandshake;
    }
    
    /**
     * Check if dual-party photos are enabled for a specific booking/user.
     * Uses gradual rollout based on booking ID.
     */
    public boolean isDualPartyPhotosEnabledForBooking(long bookingId) {
        if (!dualPartyPhotosEnabled) {
            return false;
        }
        // Gradual rollout based on booking ID modulo
        return (bookingId % 100) < dualPartyPhotosRolloutPercent;
    }
    
    public boolean isFeatureEnabledForUser(long userId) {
        if (!renterVerificationEnabled) {
            return false;
        }
        // Simple modulo-based rollout
        // If rolloutPercent is 10, users 0-9, 100-109 etc. get the feature
        return (userId % 100) < rolloutPercent;
    }

    /**
     * Whether rental agreement acceptance is required before handshake.
     * When false, only warning logs are emitted for missing agreements.
     */
    public boolean isRentalAgreementCheckinEnforced() {
        return rentalAgreementCheckinEnforced;
    }
}
