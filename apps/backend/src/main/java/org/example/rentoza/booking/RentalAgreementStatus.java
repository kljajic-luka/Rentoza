package org.example.rentoza.booking;

/**
 * Status of a rental agreement between owner and renter.
 *
 * <p>State machine:
 * <pre>
 * PENDING → OWNER_ACCEPTED → FULLY_ACCEPTED
 * PENDING → RENTER_ACCEPTED → FULLY_ACCEPTED
 * PENDING → EXPIRED
 * PENDING → VOIDED
 * </pre>
 */
public enum RentalAgreementStatus {
    PENDING,
    OWNER_ACCEPTED,
    RENTER_ACCEPTED,
    FULLY_ACCEPTED,
    EXPIRED,
    VOIDED
}
