package org.example.rentoza.user.trust;

import org.example.rentoza.user.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized interpreter for account access, profile completion, renter verification, and owner verification.
 */
@Service
public class AccountTrustStateService {

    public AccountTrustSnapshot snapshot(User user) {
        AccountAccessState accountAccessState = resolveAccountAccessState(user);
        RegistrationStatus registrationStatus = user.getRegistrationStatus() != null
                ? user.getRegistrationStatus()
                : RegistrationStatus.ACTIVE;
        RegistrationCompletionState completionState = registrationStatus == RegistrationStatus.INCOMPLETE
                ? RegistrationCompletionState.INCOMPLETE
                : RegistrationCompletionState.COMPLETE;

        RenterVerificationState renterVerificationState = resolveRenterVerificationState(user);
        OwnerVerificationState ownerVerificationState = resolveOwnerVerificationState(user);
        List<String> missingProfileFields = resolveMissingProfileFields(user);

        boolean canAuthenticate = accountAccessState == AccountAccessState.ACTIVE;
        boolean needsProfileCompletion = completionState == RegistrationCompletionState.INCOMPLETE;
        boolean canBookAsRenter = canAuthenticate && renterVerificationState.isBookable();

        return new AccountTrustSnapshot(
                accountAccessState,
                registrationStatus,
                completionState,
                user.getDriverLicenseStatus(),
                renterVerificationState,
                ownerVerificationState,
                user.getRiskLevel(),
                canAuthenticate,
                needsProfileCompletion,
                canBookAsRenter,
                missingProfileFields
        );
    }

    private AccountAccessState resolveAccountAccessState(User user) {
        if (user.isDeleted() || user.getRegistrationStatus() == RegistrationStatus.DELETED) {
            return AccountAccessState.DELETED;
        }
        if (user.isBanned()) {
            return AccountAccessState.BANNED;
        }
        if (user.getRegistrationStatus() == RegistrationStatus.SUSPENDED) {
            return AccountAccessState.SUSPENDED;
        }
        if (user.isAccountLocked() || user.isLocked()) {
            return AccountAccessState.LOCKED;
        }
        if (!user.isEnabled()) {
            return AccountAccessState.DISABLED;
        }
        return AccountAccessState.ACTIVE;
    }

    private RenterVerificationState resolveRenterVerificationState(User user) {
        DriverLicenseStatus status = user.getDriverLicenseStatus();
        if (status == null) {
            return RenterVerificationState.UNKNOWN;
        }

        return switch (status) {
            case NOT_STARTED -> RenterVerificationState.NOT_STARTED;
            case PENDING_REVIEW -> RenterVerificationState.PENDING_REVIEW;
            case REJECTED -> RenterVerificationState.REJECTED;
            case SUSPENDED -> RenterVerificationState.SUSPENDED;
            case EXPIRED -> RenterVerificationState.EXPIRED;
            case APPROVED -> {
                if (user.isDriverLicenseExpired()) {
                    yield RenterVerificationState.EXPIRED;
                }
                if (user.willDriverLicenseExpireWithin(30)) {
                    yield RenterVerificationState.APPROVED_EXPIRING_SOON;
                }
                yield RenterVerificationState.APPROVED;
            }
        };
    }

    private OwnerVerificationState resolveOwnerVerificationState(User user) {
        if (user.getRole() != Role.OWNER && user.getRole() != Role.ADMIN) {
            return OwnerVerificationState.NOT_APPLICABLE;
        }
        if (Boolean.TRUE.equals(user.getIsIdentityVerified())) {
            return OwnerVerificationState.VERIFIED;
        }
        if (user.getOwnerVerificationSubmittedAt() != null) {
            return OwnerVerificationState.PENDING_REVIEW;
        }
        if (user.getIdentityRejectedAt() != null) {
            return OwnerVerificationState.REJECTED;
        }
        return OwnerVerificationState.NOT_SUBMITTED;
    }

    private List<String> resolveMissingProfileFields(User user) {
        List<String> missing = new ArrayList<>();

        if (user.getPhone() == null || user.getPhone().isBlank()) {
            missing.add("phone");
        }
        if (user.getDateOfBirth() == null) {
            missing.add("dateOfBirth");
        }

        if (user.getRole() == Role.OWNER) {
            OwnerType ownerType = user.getOwnerType() != null ? user.getOwnerType() : OwnerType.INDIVIDUAL;
            if (ownerType == OwnerType.INDIVIDUAL) {
                if (user.getJmbg() == null || user.getJmbg().isBlank()) {
                    missing.add("jmbg");
                }
            } else {
                if (user.getPib() == null || user.getPib().isBlank()) {
                    missing.add("pib");
                }
                if (user.getBankAccountNumber() == null || user.getBankAccountNumber().isBlank()) {
                    missing.add("bankAccountNumber");
                }
            }
        }

        return missing;
    }
}