package org.example.rentoza.booking.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Preview DTO returned BEFORE cancellation is executed.
 * 
 * <p>Allows the user to see the financial consequences of cancelling
 * before they commit. This is a read-only projection - no mutation occurs.
 * 
 * <p><b>Use Case:</b> User clicks "Cancel Booking" → sees this preview →
 * confirms or aborts.
 * 
 * @param bookingId the booking being previewed for cancellation
 * @param canCancel whether cancellation is allowed (false if host is suspended, etc.)
 * @param blockReason if canCancel is false, explains why (e.g., "Host is suspended until 2024-01-15")
 * @param tripStartDateTime exact start of the trip (startDate + pickupTime) in Europe/Belgrade timezone
 * @param hoursUntilStart hours from now until trip starts (negative if trip already started)
 * @param isWithinFreeWindow true if guest is within >24h free cancellation window
 * @param isWithinRemorseWindow true if booked <1h ago (impulse booking protection)
 * @param tripDays total days of the trip (used to determine short vs long trip rules)
 * @param originalTotalPrice the total price of the booking
 * @param dailyRate the snapshotted daily rate at booking time
 * @param penaltyAmount amount the cancelling party will be charged/forfeit
 * @param refundToGuest amount that will be refunded to the guest
 * @param payoutToHost amount that will be paid to the host (after platform commission)
 * @param appliedRule human-readable description of the rule applied (e.g., "Guest cancelled >24h before trip - full refund")
 * @param policyVersion version identifier for the policy (for audit trail)
 * @since 2024-01 (Cancellation Policy Migration - Phase 2)
 */
public record CancellationPreviewDTO(
    Long bookingId,
    boolean canCancel,
    String blockReason,
    LocalDateTime tripStartDateTime,
    long hoursUntilStart,
    boolean isWithinFreeWindow,
    boolean isWithinRemorseWindow,
    int tripDays,
    BigDecimal originalTotalPrice,
    BigDecimal dailyRate,
    BigDecimal penaltyAmount,
    BigDecimal refundToGuest,
    BigDecimal payoutToHost,
    String appliedRule,
    String policyVersion
) {
    /**
     * Factory method for blocked cancellation (e.g., host suspended).
     */
    public static CancellationPreviewDTO blocked(Long bookingId, String reason) {
        return new CancellationPreviewDTO(
            bookingId,
            false,
            reason,
            null,
            0,
            false,
            false,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
