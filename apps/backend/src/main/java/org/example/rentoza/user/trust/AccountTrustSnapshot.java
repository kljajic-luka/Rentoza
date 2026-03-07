package org.example.rentoza.user.trust;

import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.RegistrationStatus;
import org.example.rentoza.user.RiskLevel;

import java.util.List;

/**
 * Centralized read model for account/trust interpretation.
 */
public record AccountTrustSnapshot(
        AccountAccessState accountAccessState,
        RegistrationStatus registrationStatus,
        RegistrationCompletionState registrationCompletionState,
        DriverLicenseStatus driverLicenseStatus,
        RenterVerificationState renterVerificationState,
        OwnerVerificationState ownerVerificationState,
        RiskLevel riskLevel,
        boolean canAuthenticate,
        boolean needsProfileCompletion,
        boolean canBookAsRenter,
        List<String> missingProfileFields
) {
}