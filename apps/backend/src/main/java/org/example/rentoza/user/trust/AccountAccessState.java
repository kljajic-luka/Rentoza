package org.example.rentoza.user.trust;

/**
 * Canonical account access state used across auth, booking, and admin surfaces.
 */
public enum AccountAccessState {
    ACTIVE,
    DISABLED,
    LOCKED,
    BANNED,
    SUSPENDED,
    DELETED
}