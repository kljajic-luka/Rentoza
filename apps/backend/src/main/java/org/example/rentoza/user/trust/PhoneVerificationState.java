package org.example.rentoza.user.trust;

/**
 * Stanje verifikacije telefona korisnika.
 */
public enum PhoneVerificationState {
    /** Telefon nije verifikovan. */
    UNVERIFIED,
    /** Korisnik je pokrenuo zamenu telefona; ceka se OTP za novi broj. */
    PENDING_CHANGE,
    /** Kanonski telefon je verifikovan. */
    VERIFIED;

    public boolean isVerified() {
        return this == VERIFIED;
    }
}
