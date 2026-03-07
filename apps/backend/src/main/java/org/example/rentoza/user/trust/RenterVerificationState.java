package org.example.rentoza.user.trust;

public enum RenterVerificationState {
    NOT_STARTED,
    PENDING_REVIEW,
    APPROVED,
    APPROVED_EXPIRING_SOON,
    REJECTED,
    EXPIRED,
    SUSPENDED,
    UNKNOWN;

    public boolean isBookable() {
        return this == APPROVED || this == APPROVED_EXPIRING_SOON;
    }
}