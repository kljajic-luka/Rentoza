package org.example.rentoza.owner.dto;

import lombok.Builder;
import org.example.rentoza.booking.cancellation.HostCancellationStats;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for host cancellation statistics.
 * 
 * <p>Exposes cancellation tracking data to the frontend for:
 * <ul>
 *   <li>Dashboard: Show current penalty tier and suspension status</li>
 *   <li>Warning dialogs: Alert host before cancellation about consequences</li>
 *   <li>Transparency: Let hosts understand their standing</li>
 * </ul>
 * 
 * <p>Penalty Tier Reference:
 * <table border="1">
 *   <tr><th>Tier</th><th>Next Cancellation Penalty</th><th>Consequence</th></tr>
 *   <tr><td>0</td><td>RSD 5,500</td><td>Warning notification</td></tr>
 *   <tr><td>1</td><td>RSD 11,000</td><td>Account review trigger</td></tr>
 *   <tr><td>2+</td><td>RSD 16,500</td><td>7-day listing suspension</td></tr>
 * </table>
 * 
 * @since 2024-01 (Cancellation Policy Migration - Phase 2)
 */
@Builder
public record HostCancellationStatsDTO(
    /**
     * Number of cancellations in the current calendar year.
     * Determines penalty tier (1st, 2nd, 3rd+).
     */
    int cancellationsThisYear,
    
    /**
     * Rolling count of cancellations in the last 30 days.
     * 3+ triggers account review regardless of yearly count.
     */
    int cancellationsLast30Days,
    
    /**
     * Total number of bookings received by this host (all time).
     * Used to calculate cancellation rate.
     */
    int totalBookings,
    
    /**
     * Cancellation rate as percentage.
     * Rate > 5% may result in reduced search visibility.
     */
    BigDecimal cancellationRate,
    
    /**
     * Current penalty tier (0, 1, 2, or 3).
     * Tier 0 = clean record (no cancellations this year).
     */
    int currentTier,
    
    /**
     * Penalty amount (RSD) for the NEXT cancellation.
     * Based on currentTier + 1 escalation.
     */
    BigDecimal nextPenaltyAmount,
    
    /**
     * Whether host is currently suspended from cancelling bookings.
     * If true, suspensionEndsAt indicates when suspension expires.
     */
    boolean isSuspended,
    
    /**
     * When current suspension ends (null if not suspended).
     * After this timestamp, host can cancel bookings again.
     */
    LocalDateTime suspensionEndsAt,
    
    /**
     * Timestamp of the most recent cancellation.
     * Null if host has never cancelled a booking.
     */
    LocalDateTime lastCancellationAt,
    
    /**
     * Whether the next cancellation will trigger a 7-day suspension.
     * True if currentTier >= 2 (3rd+ cancellation will suspend).
     */
    boolean willTriggerSuspension
) {
    
    /**
     * Penalty amounts in Serbian Dinar (RSD) by tier.
     * Based on Turo-style escalating penalty structure.
     */
    private static final BigDecimal TIER_1_PENALTY = new BigDecimal("5500");   // $50 equivalent
    private static final BigDecimal TIER_2_PENALTY = new BigDecimal("11000");  // $100 equivalent
    private static final BigDecimal TIER_3_PENALTY = new BigDecimal("16500");  // $150 equivalent
    
    /**
     * Create DTO from entity, calculating derived fields.
     * 
     * @param entity HostCancellationStats entity (or null for new hosts)
     * @return DTO with all fields populated
     */
    public static HostCancellationStatsDTO fromEntity(HostCancellationStats entity) {
        if (entity == null) {
            // New host with no cancellation history
            return HostCancellationStatsDTO.builder()
                .cancellationsThisYear(0)
                .cancellationsLast30Days(0)
                .totalBookings(0)
                .cancellationRate(BigDecimal.ZERO)
                .currentTier(0)
                .nextPenaltyAmount(TIER_1_PENALTY)
                .isSuspended(false)
                .suspensionEndsAt(null)
                .lastCancellationAt(null)
                .willTriggerSuspension(false)
                .build();
        }
        
        // Calculate next penalty based on current tier
        BigDecimal nextPenalty = switch (entity.getPenaltyTier()) {
            case 0 -> TIER_1_PENALTY;
            case 1 -> TIER_2_PENALTY;
            default -> TIER_3_PENALTY;
        };
        
        // Next cancellation triggers suspension if already at tier 2+
        boolean willSuspend = entity.getPenaltyTier() >= 2;
        
        return HostCancellationStatsDTO.builder()
            .cancellationsThisYear(entity.getCancellationsThisYear())
            .cancellationsLast30Days(entity.getCancellationsLast30Days())
            .totalBookings(entity.getTotalBookings())
            .cancellationRate(entity.getCancellationRate() != null 
                ? entity.getCancellationRate() 
                : BigDecimal.ZERO)
            .currentTier(entity.getPenaltyTier())
            .nextPenaltyAmount(nextPenalty)
            .isSuspended(entity.isSuspended())
            .suspensionEndsAt(entity.getSuspensionEndsAt())
            .lastCancellationAt(entity.getLastCancellationAt())
            .willTriggerSuspension(willSuspend)
            .build();
    }
}
