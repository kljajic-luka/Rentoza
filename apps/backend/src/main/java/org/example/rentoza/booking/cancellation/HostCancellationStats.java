package org.example.rentoza.booking.cancellation;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.user.User;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tracks host cancellation behavior for penalty tier escalation.
 * 
 * <p>This entity maintains a running tally of a host's cancellation history,
 * used to determine which penalty tier applies when they cancel a booking:
 * 
 * <table border="1">
 *   <tr><th>Tier</th><th>Cancellations (Year)</th><th>Penalty (RSD)</th><th>Consequence</th></tr>
 *   <tr><td>1</td><td>1st</td><td>5,500</td><td>Warning notification</td></tr>
 *   <tr><td>2</td><td>2nd</td><td>11,000</td><td>Account review trigger</td></tr>
 *   <tr><td>3+</td><td>3rd+</td><td>16,500</td><td>7-day listing suspension</td></tr>
 * </table>
 * 
 * <p><b>Yearly Reset:</b> {@code cancellationsThisYear} resets to 0 on January 1st
 * (handled by scheduled job, not in this entity).
 * 
 * <p><b>Suspension Logic:</b> If {@code suspensionEndsAt} is in the future,
 * the host cannot cancel any booking until the suspension expires.
 * 
 * <p><b>Pattern Detection:</b> {@code cancellationsLast30Days} is used for
 * additional monitoring - 3+ cancellations in 30 days triggers account review
 * regardless of tier.
 * 
 * @since 2024-01 (Cancellation Policy Migration - Phase 1)
 * @see CancellationRecord
 */
@Entity
@Table(
    name = "host_cancellation_stats",
    indexes = {
        @Index(
            name = "idx_host_stats_suspension",
            columnList = "suspension_ends_at"
        )
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostCancellationStats {

    /**
     * Primary key is the host's user ID (same as users.id).
     * This creates a 1:1 relationship with User.
     */
    @Id
    @Column(name = "host_id")
    private Long hostId;

    /**
     * Reference to the host user (for navigation).
     * Maps to the same ID as {@code hostId}.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", insertable = false, updatable = false)
    private User host;

    // ==================== CANCELLATION COUNTS ====================

    /**
     * Number of cancellations in the current calendar year.
     * Used to determine penalty tier (1st, 2nd, 3rd+).
     * 
     * <p>Reset to 0 on January 1st by scheduled job.
     */
    @Column(name = "cancellations_this_year", nullable = false)
    @Builder.Default
    private Integer cancellationsThisYear = 0;

    /**
     * Rolling count of cancellations in the last 30 days.
     * Used for pattern detection (3+ triggers review).
     * 
     * <p>Updated by scheduled job or on each cancellation.
     */
    @Column(name = "cancellations_last_30_days", nullable = false)
    @Builder.Default
    private Integer cancellationsLast30Days = 0;

    /**
     * Total number of bookings the host has ever received.
     * Used to calculate {@code cancellationRate}.
     */
    @Column(name = "total_bookings", nullable = false)
    @Builder.Default
    private Integer totalBookings = 0;

    /**
     * Cancellation rate as percentage (cancellationsThisYear / totalBookings * 100).
     * 
     * <p>Rate > 5% may result in reduced search visibility.
     */
    @Column(name = "cancellation_rate", precision = 5, scale = 2)
    private BigDecimal cancellationRate;

    // ==================== PENALTY STATE ====================

    /**
     * Current penalty tier (1, 2, or 3+).
     * Tier 0 means no cancellations this year (clean record).
     * 
     * <p>Determines the penalty amount for the next cancellation.
     */
    @Column(name = "penalty_tier", nullable = false)
    @Builder.Default
    private Integer penaltyTier = 0;

    /**
     * Timestamp of the host's last cancellation.
     * Used for 30-day rolling window calculation.
     */
    @Column(name = "last_cancellation_at")
    private LocalDateTime lastCancellationAt;

    /**
     * When the current suspension ends (null if not suspended).
     * 
     * <p>If this timestamp is in the future, the host cannot cancel bookings.
     * Suspension is typically 7 days for tier 3+ cancellations.
     */
    @Column(name = "suspension_ends_at")
    private LocalDateTime suspensionEndsAt;

    // ==================== AUDIT ====================

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ==================== BUSINESS LOGIC HELPERS ====================

    /**
     * Check if the host is currently suspended from cancelling bookings.
     * 
     * @return true if suspension is active, false otherwise
     */
    public boolean isSuspended() {
        return suspensionEndsAt != null && suspensionEndsAt.isAfter(LocalDateTime.now());
    }

    /**
     * Get the next penalty tier that will apply on the next cancellation.
     * 
     * @return 1, 2, or 3 (capped at 3 for escalation purposes)
     */
    public int getNextTier() {
        return Math.min(penaltyTier + 1, 3);
    }

    /**
     * Increment cancellation counts and update tier.
     * Called when a host cancels a booking (without penalty waiver).
     */
    public void recordCancellation() {
        this.cancellationsThisYear++;
        this.cancellationsLast30Days++;
        this.lastCancellationAt = LocalDateTime.now();
        this.penaltyTier = Math.min(this.cancellationsThisYear, 3);
        recalculateCancellationRate();
    }

    /**
     * Increment total bookings count.
     * Called when a new booking is confirmed for this host.
     */
    public void recordBooking() {
        this.totalBookings++;
        recalculateCancellationRate();
    }

    /**
     * Recalculate the cancellation rate percentage.
     */
    private void recalculateCancellationRate() {
        if (totalBookings > 0) {
            this.cancellationRate = BigDecimal.valueOf(cancellationsThisYear)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalBookings), 2, java.math.RoundingMode.HALF_UP);
        } else {
            this.cancellationRate = BigDecimal.ZERO;
        }
    }
}
