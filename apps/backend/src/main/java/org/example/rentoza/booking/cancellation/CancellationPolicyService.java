package org.example.rentoza.booking.cancellation;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.dto.CancellationPreviewDTO;
import org.example.rentoza.booking.dto.CancellationResultDTO;
import org.example.rentoza.user.User;

/**
 * Service interface for calculating and processing cancellations.
 * 
 * <p>This is the Strategy pattern entry point - different implementations
 * can provide different cancellation policies (e.g., Turo-style, legacy, etc.).
 * 
 * <p><b>Current Implementation:</b> {@link TuroCancellationPolicyService}
 * 
 * <p><b>Thread Safety:</b> Implementations must be stateless and thread-safe.
 * 
 * @since 2024-01 (Cancellation Policy Migration - Phase 2)
 * @see TuroCancellationPolicyService
 */
public interface CancellationPolicyService {

    /**
     * Current policy version string for audit trail.
     * Format: "TURO_V1.0_2024" or similar.
     */
    String getPolicyVersion();

    /**
     * Generate a cancellation preview WITHOUT executing the cancellation.
     * 
     * <p>This method calculates what would happen if the user cancelled,
     * but does not persist any changes. Safe to call multiple times.
     * 
     * @param booking the booking to preview cancellation for
     * @param initiator the user initiating the cancellation (guest or host)
     * @return preview DTO with financial consequences
     */
    CancellationPreviewDTO generatePreview(Booking booking, User initiator);

    /**
     * Process and execute a cancellation.
     * 
     * <p>This method:
     * <ol>
     *   <li>Validates cancellation is allowed</li>
     *   <li>Calculates penalties/refunds using the same logic as preview</li>
     *   <li>Creates an immutable CancellationRecord</li>
     *   <li>Updates Booking status and denormalized fields</li>
     *   <li>Updates HostCancellationStats if host cancelled</li>
     *   <li>Initiates refund processing (async)</li>
     * </ol>
     * 
     * <p><b>Transactional:</b> This method should be called within a transaction.
     * The caller (BookingService) manages the transaction boundary.
     * 
     * @param booking the booking to cancel (must be in cancellable state)
     * @param initiator the user initiating the cancellation
     * @param reason the reason for cancellation
     * @param notes optional free-text notes
     * @return result DTO with final state
     * @throws IllegalStateException if booking cannot be cancelled
     */
    CancellationResultDTO processCancellation(
        Booking booking, 
        User initiator, 
        CancellationReason reason,
        String notes
    );

    /**
     * Check if a host is currently suspended from cancelling bookings.
     * 
     * @param hostId the host user's ID
     * @return true if host is suspended, false otherwise
     */
    boolean isHostSuspended(Long hostId);

    /**
     * Get the penalty amount for a host based on their current tier.
     * 
     * <p>Turo-style penalties:
     * <ul>
     *   <li>Tier 1 (1st cancellation): RSD 5,500</li>
     *   <li>Tier 2 (2nd cancellation): RSD 11,000</li>
     *   <li>Tier 3+ (3rd+ cancellation): RSD 16,500</li>
     * </ul>
     * 
     * @param hostId the host user's ID
     * @return the penalty amount in RSD
     */
    java.math.BigDecimal getHostPenaltyForNextCancellation(Long hostId);
}
