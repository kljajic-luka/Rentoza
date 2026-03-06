package org.example.rentoza.booking.cancellation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.util.BookingDurationPolicy;
import org.example.rentoza.booking.dto.CancellationPreviewDTO;
import org.example.rentoza.booking.dto.CancellationResultDTO;
import org.example.rentoza.user.User;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Turo-style cancellation policy implementation.
 * 
 * <h2>Guest Cancellation Rules</h2>
 * <table border="1">
 *   <tr><th>Scenario</th><th>Penalty</th><th>Refund</th></tr>
 *   <tr><td>>24h before trip</td><td>0</td><td>100%</td></tr>
 *   <tr><td>&lt;24h but booked &lt;1h ago (remorse)</td><td>0</td><td>100%</td></tr>
 *   <tr><td>&lt;24h, short trip (≤2 days)</td><td>1 day rate</td><td>Total - 1 day</td></tr>
 *   <tr><td>&lt;24h, long trip (>2 days)</td><td>50%</td><td>50%</td></tr>
 * </table>
 * 
 * <h2>Host Cancellation Rules</h2>
 * <table border="1">
 *   <tr><th>Tier</th><th>Cancellations (Year)</th><th>Penalty (RSD)</th><th>Consequence</th></tr>
 *   <tr><td>1</td><td>1st</td><td>5,500</td><td>Warning</td></tr>
 *   <tr><td>2</td><td>2nd</td><td>11,000</td><td>Account review</td></tr>
 *   <tr><td>3+</td><td>3rd+</td><td>16,500</td><td>7-day suspension</td></tr>
 * </table>
 * 
 * <h2>Timezone</h2>
 * All calculations use Europe/Belgrade timezone.
 * 
 * @since 2024-01 (Cancellation Policy Migration - Phase 2)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TuroCancellationPolicyService implements CancellationPolicyService {

    private static final String POLICY_VERSION = "TURO_V1.0_2024";
    private static final ZoneId BELGRADE_ZONE = ZoneId.of("Europe/Belgrade");
    
    // Guest cancellation thresholds
    private static final long FREE_CANCELLATION_HOURS = 24;
    private static final long REMORSE_WINDOW_HOURS = 1;
    private static final int SHORT_TRIP_MAX_DAYS = 2;
    
    // Host penalty amounts (RSD)
    private static final BigDecimal HOST_PENALTY_TIER_1 = new BigDecimal("5500.00");
    private static final BigDecimal HOST_PENALTY_TIER_2 = new BigDecimal("11000.00");
    private static final BigDecimal HOST_PENALTY_TIER_3 = new BigDecimal("16500.00");
    
    // Host suspension duration
    private static final int SUSPENSION_DAYS = 7;

    private final CancellationRecordRepository cancellationRecordRepository;
    private final HostCancellationStatsRepository hostCancellationStatsRepository;
    private final CancellationSettlementService cancellationSettlementService;

    @Override
    public String getPolicyVersion() {
        return POLICY_VERSION;
    }

    @Override
    public CancellationPreviewDTO generatePreview(Booking booking, User initiator) {
        log.debug("Generating cancellation preview for booking {} by user {}", 
            booking.getId(), initiator.getId());

        // Determine who is cancelling
        CancelledBy cancelledBy = determineCancelledBy(booking, initiator);
        
        // Check if cancellation is blocked
        if (cancelledBy == CancelledBy.HOST && isHostSuspended(initiator.getId())) {
            LocalDateTime suspensionEnd = hostCancellationStatsRepository
                .findById(initiator.getId())
                .map(HostCancellationStats::getSuspensionEndsAt)
                .orElse(null);
            return CancellationPreviewDTO.blocked(booking.getId(), 
                "Host is suspended from cancelling until " + suspensionEnd);
        }

        // Calculate timing
        LocalDateTime tripStart = calculateTripStartDateTime(booking);
        LocalDateTime now = LocalDateTime.now(BELGRADE_ZONE);
        long hoursUntilStart = ChronoUnit.HOURS.between(now, tripStart);
        
        // Check windows
        boolean isWithinFreeWindow = hoursUntilStart >= FREE_CANCELLATION_HOURS;
        boolean isWithinRemorseWindow = isWithinRemorseWindow(booking);
        
        // Calculate trip duration
        int tripDays = calculateTripDays(booking);
        
        // Get pricing
        BigDecimal originalTotal = booking.getTotalPrice();
        BigDecimal dailyRate = booking.getSnapshotDailyRate() != null 
            ? booking.getSnapshotDailyRate() 
            : calculateDailyRate(booking);
        
        // Calculate penalty and refund based on rules
        CancellationCalculation calc = calculateCancellation(
            booking.getStatus(),
            cancelledBy, 
            hoursUntilStart, 
            isWithinRemorseWindow, 
            tripDays, 
            originalTotal, 
            dailyRate,
            initiator.getId()
        );
        
        return new CancellationPreviewDTO(
            booking.getId(),
            true,
            null,
            tripStart,
            hoursUntilStart,
            isWithinFreeWindow,
            isWithinRemorseWindow,
            tripDays,
            originalTotal,
            dailyRate,
            calc.penaltyAmount,
            calc.refundToGuest,
            calc.payoutToHost,
            calc.appliedRule,
            POLICY_VERSION
        );
    }

    @Override
    public CancellationResultDTO processCancellation(
            Booking booking, 
            User initiator, 
            CancellationReason reason,
            String notes) {
        
        log.info("Processing cancellation for booking {} by user {} with reason {}", 
            booking.getId(), initiator.getId(), reason);

        // Idempotency check - prevent double cancellation
        if (cancellationRecordRepository.existsByBookingId(booking.getId())) {
            throw new IllegalStateException("Booking " + booking.getId() + " has already been cancelled");
        }

        // Validate booking state
        if (!isCancellable(booking)) {
            throw new IllegalStateException("Booking " + booking.getId() + 
                " cannot be cancelled in state: " + booking.getStatus());
        }

        CancelledBy cancelledBy = determineCancelledBy(booking, initiator);
        
        // Check host suspension
        if (cancelledBy == CancelledBy.HOST && isHostSuspended(initiator.getId())) {
            throw new IllegalStateException("Host is currently suspended from cancelling bookings");
        }

        // Calculate timing
        LocalDateTime tripStart = calculateTripStartDateTime(booking);
        LocalDateTime now = LocalDateTime.now(BELGRADE_ZONE);
        long hoursBeforeTripStart = ChronoUnit.HOURS.between(now, tripStart);
        
        boolean isWithinRemorseWindow = isWithinRemorseWindow(booking);
        int tripDays = calculateTripDays(booking);
        
        BigDecimal originalTotal = booking.getTotalPrice();
        BigDecimal dailyRate = booking.getSnapshotDailyRate() != null 
            ? booking.getSnapshotDailyRate() 
            : calculateDailyRate(booking);
        
        // Calculate financial outcome
        CancellationCalculation calc = calculateCancellation(
            booking.getStatus(),
            cancelledBy, 
            hoursBeforeTripStart, 
            isWithinRemorseWindow, 
            tripDays, 
            originalTotal, 
            dailyRate,
            cancelledBy == CancelledBy.HOST ? initiator.getId() : null
        );
        
        CancellationRecord record = cancellationSettlementService.beginSettlement(
            booking,
            cancelledBy,
            reason,
            notes,
            calc.penaltyAmount,
            calc.refundToGuest,
            calc.payoutToHost,
            calc.appliedRule,
            POLICY_VERSION,
            dailyRate,
            now
        );
        
        // Handle host-specific logic
        BigDecimal hostPenaltyApplied = null;
        Integer hostNewTier = null;
        LocalDateTime hostSuspendedUntil = null;
        
        if (cancelledBy == CancelledBy.HOST) {
            HostPenaltyResult penaltyResult = applyHostPenalty(initiator.getId());
            hostPenaltyApplied = penaltyResult.penaltyAmount;
            hostNewTier = penaltyResult.newTier;
            hostSuspendedUntil = penaltyResult.suspendedUntil;
            
            // Update the record with host penalty
            record.setHostPenaltyAmount(hostPenaltyApplied);
        }
        
        log.info("Cancellation processed: bookingId={}, recordId={}, cancelledBy={}, " +
            "penalty={}, refund={}, payout={}", 
            booking.getId(), record.getId(), cancelledBy, 
            calc.penaltyAmount, calc.refundToGuest, calc.payoutToHost);
        
        return CancellationResultDTO.builder()
            .bookingId(booking.getId())
            .cancellationRecordId(record.getId())
            .cancelledBy(cancelledBy)
            .reason(reason)
            .cancelledAt(now)
            .hoursBeforeTripStart(hoursBeforeTripStart)
            .originalTotalPrice(originalTotal)
            .penaltyAmount(calc.penaltyAmount)
            .refundToGuest(calc.refundToGuest)
            .payoutToHost(calc.payoutToHost)
            .refundStatus(RefundStatus.PENDING)
            .appliedRule(calc.appliedRule)
            .hostPenaltyApplied(hostPenaltyApplied)
            .hostNewTier(hostNewTier)
            .hostSuspendedUntil(hostSuspendedUntil)
            .build();
    }

    @Override
    public boolean isHostSuspended(Long hostId) {
        return hostCancellationStatsRepository.findById(hostId)
            .map(HostCancellationStats::isSuspended)
            .orElse(false);
    }

    @Override
    public BigDecimal getHostPenaltyForNextCancellation(Long hostId) {
        int currentTier = hostCancellationStatsRepository.findById(hostId)
            .map(HostCancellationStats::getPenaltyTier)
            .orElse(0);
        
        return getHostPenaltyForTier(currentTier + 1);
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private CancelledBy determineCancelledBy(Booking booking, User initiator) {
        if (booking.getRenter() != null && booking.getRenter().getId().equals(initiator.getId())) {
            return CancelledBy.GUEST;
        }
        if (booking.getCar() != null && booking.getCar().getOwner() != null 
            && booking.getCar().getOwner().getId().equals(initiator.getId())) {
            return CancelledBy.HOST;
        }
        // Default to SYSTEM if neither (e.g., admin action)
        return CancelledBy.SYSTEM;
    }

    private LocalDateTime calculateTripStartDateTime(Booking booking) {
        // With exact timestamp architecture, startTime is now directly available
        return booking.getStartTime();
    }

    private boolean isWithinRemorseWindow(Booking booking) {
        if (booking.getCreatedAt() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(BELGRADE_ZONE);
        Duration sinceCreation = Duration.between(booking.getCreatedAt(), now);
        return sinceCreation.toHours() < REMORSE_WINDOW_HOURS;
    }

    private int calculateTripDays(Booking booking) {
        if (booking.getStartTime() == null || booking.getEndTime() == null) {
            return 1;
        }
        return Math.toIntExact(
            BookingDurationPolicy.calculate(booking.getStartTime(), booking.getEndTime())
                .cancellationCalendarDaysInclusive()
        );
    }

    private BigDecimal calculateDailyRate(Booking booking) {
        int tripDays = calculateTripDays(booking);
        if (tripDays <= 0) {
            return booking.getTotalPrice();
        }
        return booking.getTotalPrice().divide(
            BigDecimal.valueOf(tripDays), 
            2, 
            RoundingMode.HALF_UP
        );
    }

    private boolean isCancellable(Booking booking) {
        BookingStatus status = booking.getStatus();
        // PENDING_APPROVAL: Guest requested, awaiting host approval
        // ACTIVE: Host approved, trip is active (can be cancelled before trip starts)
        return status == BookingStatus.PENDING_APPROVAL || 
               status == BookingStatus.ACTIVE;
    }

    private CancellationCalculation calculateCancellation(
            BookingStatus bookingStatus,
            CancelledBy cancelledBy,
            long hoursUntilStart,
            boolean isWithinRemorseWindow,
            int tripDays,
            BigDecimal originalTotal,
            BigDecimal dailyRate,
            Long hostIdForPenalty) {
        
        if (cancelledBy == CancelledBy.HOST || cancelledBy == CancelledBy.SYSTEM) {
            // Host/System cancellation: Guest gets full refund, host pays penalty
            return new CancellationCalculation(
                BigDecimal.ZERO,  // No guest penalty
                originalTotal,    // Full refund to guest
                BigDecimal.ZERO,  // No payout to host
                cancelledBy == CancelledBy.HOST 
                    ? "Host cancelled - full refund to guest, host penalty applies"
                    : "System cancelled - full refund to guest"
            );
        }

        // Fairness rule: guest cancellation while still awaiting host approval is always penalty-free.
        if (bookingStatus == BookingStatus.PENDING_APPROVAL && cancelledBy == CancelledBy.GUEST) {
            return new CancellationCalculation(
                BigDecimal.ZERO,
                originalTotal,
                BigDecimal.ZERO,
                "Guest cancelled pending approval - full refund"
            );
        }
        
        // Guest cancellation rules
        
        // Rule 1: >24h before trip = free cancellation
        if (hoursUntilStart >= FREE_CANCELLATION_HOURS) {
            return new CancellationCalculation(
                BigDecimal.ZERO,
                originalTotal,
                BigDecimal.ZERO,
                "Guest cancelled >24h before trip - full refund"
            );
        }
        
        // Rule 2: <24h but booked <1h ago = remorse window (impulse booking protection)
        if (isWithinRemorseWindow) {
            return new CancellationCalculation(
                BigDecimal.ZERO,
                originalTotal,
                BigDecimal.ZERO,
                "Guest cancelled within 1-hour remorse window - full refund"
            );
        }
        
        // Rule 3: <24h, short trip (≤2 days) = 1 day penalty
        if (tripDays <= SHORT_TRIP_MAX_DAYS) {
            BigDecimal penalty = dailyRate;
            BigDecimal refund = originalTotal.subtract(penalty).max(BigDecimal.ZERO);
            BigDecimal hostPayout = penalty; // Host keeps the penalty amount
            return new CancellationCalculation(
                penalty,
                refund,
                hostPayout,
                "Guest cancelled <24h before short trip (≤2 days) - 1 day penalty"
            );
        }
        
        // Rule 4: <24h, long trip (>2 days) = 50% penalty
        BigDecimal penalty = originalTotal.multiply(new BigDecimal("0.50"))
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal refund = originalTotal.subtract(penalty);
        BigDecimal hostPayout = penalty;
        
        return new CancellationCalculation(
            penalty,
            refund,
            hostPayout,
            "Guest cancelled <24h before long trip (>2 days) - 50% penalty"
        );
    }

    private BigDecimal getHostPenaltyForTier(int tier) {
        return switch (tier) {
            case 1 -> HOST_PENALTY_TIER_1;
            case 2 -> HOST_PENALTY_TIER_2;
            default -> HOST_PENALTY_TIER_3;
        };
    }

    private HostPenaltyResult applyHostPenalty(Long hostId) {
        HostCancellationStats stats = hostCancellationStatsRepository.findByIdForUpdate(hostId)
            .orElseGet(() -> HostCancellationStats.builder()
                .hostId(hostId)
                .cancellationsThisYear(0)
                .cancellationsLast30Days(0)
                .totalBookings(0)
                .penaltyTier(0)
                .build());
        
        // Record the cancellation (increments counts and tier)
        stats.recordCancellation();
        
        // Calculate penalty based on NEW tier
        BigDecimal penaltyAmount = getHostPenaltyForTier(stats.getPenaltyTier());
        
        // Apply suspension if tier 3+
        LocalDateTime suspendedUntil = null;
        if (stats.getPenaltyTier() >= 3) {
            suspendedUntil = LocalDateTime.now(BELGRADE_ZONE).plusDays(SUSPENSION_DAYS);
            stats.setSuspensionEndsAt(suspendedUntil);
            log.warn("Host {} suspended until {} after {} cancellations this year", 
                hostId, suspendedUntil, stats.getCancellationsThisYear());
        }
        
        // Save updated stats
        hostCancellationStatsRepository.save(stats);
        
        return new HostPenaltyResult(penaltyAmount, stats.getPenaltyTier(), suspendedUntil);
    }

    // ==================== INNER CLASSES ====================

    private record CancellationCalculation(
        BigDecimal penaltyAmount,
        BigDecimal refundToGuest,
        BigDecimal payoutToHost,
        String appliedRule
    ) {}

    private record HostPenaltyResult(
        BigDecimal penaltyAmount,
        Integer newTier,
        LocalDateTime suspendedUntil
    ) {}
}
