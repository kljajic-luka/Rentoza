package org.example.rentoza.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FeatureFlags {

    public static final String RENTER_VERIFICATION_ENABLED = "feature.renter-verification.enabled";
    public static final String RENTER_VERIFICATION_ASYNC_PROCESSING = "feature.renter-verification.async-processing";
    public static final String RENTER_VERIFICATION_STRICT_CHECKIN = "feature.renter-verification.strict-checkin";
    public static final String RENTER_VERIFICATION_ROLLOUT_PERCENT = "feature.renter-verification.rollout-percent";

    @Value("${" + RENTER_VERIFICATION_ENABLED + ":true}")
    private boolean renterVerificationEnabled;

    @Value("${" + RENTER_VERIFICATION_ASYNC_PROCESSING + ":true}")
    private boolean asyncProcessingEnabled;

    @Value("${" + RENTER_VERIFICATION_STRICT_CHECKIN + ":true}")
    private boolean strictCheckinEnabled;

    @Value("${" + RENTER_VERIFICATION_ROLLOUT_PERCENT + ":100}")
    private int rolloutPercent;

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
    
    public boolean isFeatureEnabledForUser(long userId) {
        if (!renterVerificationEnabled) {
            return false;
        }
        // Simple modulo-based rollout
        // If rolloutPercent is 10, users 0-9, 100-109 etc. get the feature
        return (userId % 100) < rolloutPercent;
    }
}
