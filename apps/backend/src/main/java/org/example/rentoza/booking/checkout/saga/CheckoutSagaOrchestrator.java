package org.example.rentoza.booking.checkout.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.CheckInActorRole;
import org.example.rentoza.booking.checkin.CheckInEventService;
import org.example.rentoza.booking.checkin.CheckInEventType;
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.booking.checkout.cqrs.CheckoutDomainEvent;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaState.SagaStatus;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.payment.PaymentIdempotencyKey;
import org.example.rentoza.payment.PaymentProvider;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.example.rentoza.payment.PaymentProvider.ProviderResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Checkout Saga Orchestrator.
 * 
 * <h2>Phase 2 Architecture Improvement - Saga Pattern</h2>
 * <p>Coordinates the multi-step checkout process with compensation support.
 * 
 * <h2>Steps</h2>
 * <ol>
 *   <li>VALIDATE_RETURN - Verify checkout photos and readings</li>
 *   <li>CALCULATE_CHARGES - Calculate extra fees (with Phase 4D tiered late fees)</li>
 *   <li>CAPTURE_DEPOSIT - Charge from held deposit</li>
 *   <li>RELEASE_DEPOSIT - Return unused deposit</li>
 *   <li>COMPLETE_BOOKING - Mark booking as completed</li>
 * </ol>
 * 
 * <h2>Phase 4D: Tiered Late Fee Structure</h2>
 * <ul>
 *   <li><b>Tier 1 (0-2 hours):</b> 500 RSD/hour - grace period expired, basic fee</li>
 *   <li><b>Tier 2 (2-6 hours):</b> 750 RSD/hour - significant delay</li>
 *   <li><b>Tier 3 (6+ hours):</b> 1000 RSD/hour - major delay, approaching no-return</li>
 * </ul>
 * 
 * <h2>Error Handling</h2>
 * <p>If any step fails:
 * <ul>
 *   <li>Saga enters COMPENSATING state</li>
 *   <li>Previously completed steps are rolled back in reverse order</li>
 *   <li>Saga ends in COMPENSATED or FAILED state</li>
 * </ul>
 * 
 * @see CheckoutSagaStep for step definitions
 * @see CheckoutSagaState for persistence
 */
@Service
@Slf4j
public class CheckoutSagaOrchestrator {

    private final CheckoutSagaStateRepository sagaRepository;
    private final BookingRepository bookingRepository;
    private final CheckInPhotoRepository checkInPhotoRepository;
    private final NotificationService notificationService;
    private final CheckInEventService eventService;
    private final ApplicationEventPublisher eventPublisher;
    private final BookingPaymentService bookingPaymentService;
    private final PaymentProvider paymentProvider;

    // Metrics
    private final Counter sagaStartedCounter;
    private final Counter sagaCompletedCounter;
    private final Counter sagaFailedCounter;
    private final Counter sagaCompensatedCounter;
    private final Counter lateFeesTier1Counter;
    private final Counter lateFeesTier2Counter;
    private final Counter lateFeesTier3Counter;
    private final Counter vehicleNotReturnedCounter;
    private final Timer sagaDurationTimer;

    // ========== PHASE 4D: TIERED LATE FEE CONFIGURATION ==========
    // All rates are configurable via application properties for production flexibility
    
    @Value("${app.checkout.late.tier1-max-hours:2}")
    private int tier1MaxHours;
    
    @Value("${app.checkout.late.tier1-rate-rsd:500}")
    private int tier1RateRsd;
    
    @Value("${app.checkout.late.tier2-max-hours:6}")
    private int tier2MaxHours;
    
    @Value("${app.checkout.late.tier2-rate-rsd:750}")
    private int tier2RateRsd;
    
    @Value("${app.checkout.late.tier3-rate-rsd:1000}")
    private int tier3RateRsd;
    
    @Value("${app.checkout.vehicle-not-returned-threshold-hours:24}")
    private int vehicleNotReturnedThresholdHours;
    
    @Value("${app.checkout.surcharge.mileage-rate-per-km:25}")
    private BigDecimal mileageRatePerKm;

    @Value("${app.checkout.surcharge.fuel-rate-per-percent:50}")
    private BigDecimal fuelRatePerPercent;

    private static final int LATE_GRACE_MINUTES = 15;  // Grace period before fees apply
    private static final int MAX_LATE_HOURS = 24;  // Maximum billable hours

    public CheckoutSagaOrchestrator(
            CheckoutSagaStateRepository sagaRepository,
            BookingRepository bookingRepository,
            CheckInPhotoRepository checkInPhotoRepository,
            NotificationService notificationService,
            CheckInEventService eventService,
            ApplicationEventPublisher eventPublisher,
            BookingPaymentService bookingPaymentService,
            PaymentProvider paymentProvider,
            MeterRegistry meterRegistry) {
        this.sagaRepository = sagaRepository;
        this.bookingRepository = bookingRepository;
        this.checkInPhotoRepository = checkInPhotoRepository;
        this.notificationService = notificationService;
        this.eventService = eventService;
        this.eventPublisher = eventPublisher;
        this.bookingPaymentService = bookingPaymentService;
        this.paymentProvider = paymentProvider;

        this.sagaStartedCounter = Counter.builder("checkout.saga.started")
                .description("Checkout sagas started")
                .register(meterRegistry);

        this.sagaCompletedCounter = Counter.builder("checkout.saga.completed")
                .description("Checkout sagas completed successfully")
                .register(meterRegistry);

        this.sagaFailedCounter = Counter.builder("checkout.saga.failed")
                .description("Checkout sagas failed")
                .register(meterRegistry);

        this.sagaCompensatedCounter = Counter.builder("checkout.saga.compensated")
                .description("Checkout sagas compensated")
                .register(meterRegistry);
                
        // Phase 4D: Tiered late fee metrics
        this.lateFeesTier1Counter = Counter.builder("checkout.late_fees.tier1")
                .description("Late fees at Tier 1 (0-2 hours)")
                .register(meterRegistry);
                
        this.lateFeesTier2Counter = Counter.builder("checkout.late_fees.tier2")
                .description("Late fees at Tier 2 (2-6 hours)")
                .register(meterRegistry);
                
        this.lateFeesTier3Counter = Counter.builder("checkout.late_fees.tier3")
                .description("Late fees at Tier 3 (6+ hours)")
                .register(meterRegistry);
                
        this.vehicleNotReturnedCounter = Counter.builder("checkout.vehicle_not_returned")
                .description("Vehicles flagged as not returned (24+ hours)")
                .register(meterRegistry);

        this.sagaDurationTimer = Timer.builder("checkout.saga.duration")
                .description("Checkout saga total duration")
                .register(meterRegistry);
    }

    // ========== SAGA LIFECYCLE ==========

    /**
     * Start a new checkout saga for a booking.
     * 
     * <p><strong>Isolation Level:</strong> READ_COMMITTED</p>
     * <p>Uses optimistic locking (@Version) to detect concurrent modifications.
     * Allows service transaction to commit before saga reads booking state.</p>
     * 
     * @param bookingId Booking to checkout
     * @return Saga state
     * @throws IllegalStateException if saga already exists
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public CheckoutSagaState startSaga(Long bookingId) {
        log.info("[Saga] Starting checkout saga for booking {}", bookingId);

        // Check for existing active saga
        Optional<CheckoutSagaState> existingSaga = sagaRepository.findActiveSagaForBooking(bookingId);
        if (existingSaga.isPresent()) {
            log.warn("[Saga] Active saga already exists for booking {}: {}",
                    bookingId, existingSaga.get().getSagaId());
            return existingSaga.get();
        }

        // Validate booking state
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        if (booking.getStatus() != BookingStatus.CHECKOUT_HOST_COMPLETE
            && booking.getStatus() != BookingStatus.CHECKOUT_SETTLEMENT_PENDING) {
            throw new IllegalStateException(
                "Booking must be in CHECKOUT_HOST_COMPLETE or CHECKOUT_SETTLEMENT_PENDING status. Current: "
                    + booking.getStatus());
        }

        // Create saga state
        CheckoutSagaState saga = CheckoutSagaState.builder()
                .sagaId(UUID.randomUUID())
                .bookingId(bookingId)
                .status(SagaStatus.PENDING)
                .currentStep(CheckoutSagaStep.VALIDATE_RETURN)
                .build();

        sagaRepository.save(saga);

        sagaStartedCounter.increment();
        log.info("[Saga] Created saga {} for booking {}", saga.getSagaId(), bookingId);

        // Publish CQRS event
        publishEvent(new CheckoutDomainEvent.SagaStarted(
                bookingId,
                saga.getSagaId(),
                Instant.now()
        ));

        // Start execution
        return executeSaga(saga);
    }

    /**
     * Execute saga steps until completion or failure.
     */
    @Transactional
    public CheckoutSagaState executeSaga(CheckoutSagaState saga) {
        log.debug("[Saga] Executing saga {} from step {}",
                saga.getSagaId(), saga.getCurrentStep());

        saga.setStatus(SagaStatus.RUNNING);
        saga.setUpdatedAt(Instant.now());

        try {
            while (saga.getCurrentStep() != null && saga.getStatus() == SagaStatus.RUNNING) {
                executeStep(saga);

                if (saga.getStatus() == SagaStatus.RUNNING) {
                    // Move to next step
                    saga.setLastCompletedStep(saga.getCurrentStep());
                    saga.setCurrentStep(saga.getCurrentStep().next());
                }
            }

            // All steps completed
            if (saga.getCurrentStep() == null && saga.getStatus() == SagaStatus.RUNNING) {
                saga.setStatus(SagaStatus.COMPLETED);
                saga.setCompletedAt(Instant.now());

                if (saga.getCreatedAt() != null) {
                    Duration duration = Duration.between(saga.getCreatedAt(), Instant.now());
                    sagaDurationTimer.record(duration);
                }

                sagaCompletedCounter.increment();
                log.info("[Saga] Completed saga {} for booking {}", saga.getSagaId(), saga.getBookingId());

                // Publish CQRS event for saga completion
                Booking booking = loadBooking(saga.getBookingId());
                publishEvent(new CheckoutDomainEvent.BookingCompleted(
                        saga.getBookingId(),
                        saga.getSagaId(),
                        booking.getTotalAmount(),
                        booking.getDurationDays(),
                        Instant.now()
                ));

                // Notify parties
                notifySagaCompleted(saga);
            }

        } catch (Exception e) {
            log.error("[Saga] Error in saga {} at step {}: {}",
                    saga.getSagaId(), saga.getCurrentStep(), e.getMessage(), e);

            // P1 FIX: Don't overwrite SUSPENDED status with FAILED.
            // If executeValidateReturn() suspended the saga due to a damage claim,
            // keeping SUSPENDED allows resumeSuspendedSaga() to find and resume it.
            if (saga.getStatus() != SagaStatus.SUSPENDED) {
                saga.setStatus(SagaStatus.FAILED);
                saga.setFailedAtStep(saga.getCurrentStep());
                saga.setErrorMessage(e.getMessage());

                sagaFailedCounter.increment();

                // Start compensation if there are compensable steps
                if (saga.getLastCompletedStep() != null && saga.getLastCompletedStep().hasCompensation()) {
                    startCompensation(saga);
                }
            } else {
                log.info("[Saga] Saga {} is SUSPENDED (not marking as FAILED) - awaiting dispute resolution",
                        saga.getSagaId());
            }
        }

        return sagaRepository.save(saga);
    }

    // ========== STEP EXECUTION ==========

    private void executeStep(CheckoutSagaState saga) {
        log.debug("[Saga] Executing step {} for saga {}", saga.getCurrentStep(), saga.getSagaId());

        switch (saga.getCurrentStep()) {
            case VALIDATE_RETURN -> executeValidateReturn(saga);
            case CALCULATE_CHARGES -> executeCalculateCharges(saga);
            case CAPTURE_DEPOSIT -> executeCaptureDeposit(saga);
            case RELEASE_DEPOSIT -> executeReleaseDeposit(saga);
            case COMPLETE_BOOKING -> executeCompleteBooking(saga);
        }
    }

    /**
     * Validate return data and check for blockers.
     * 
     * <h2>VAL-010: Damage Claims Block Deposit Release</h2>
     * <p>If booking is in CHECKOUT_DAMAGE_DISPUTE status or has an unresolved
     * checkout damage claim, the saga MUST be suspended until resolved.</p>
     * 
     * @throws IllegalStateException if validation fails or saga should be suspended
     */
    private void executeValidateReturn(CheckoutSagaState saga) {
        Booking booking = loadBooking(saga.getBookingId());

        // ================================================================
        // VAL-010: CHECK FOR UNRESOLVED DAMAGE CLAIMS
        // ================================================================
        // If the host reported damage at checkout, we MUST NOT proceed with
        // deposit release until the dispute is resolved. This is critical
        // for protecting hosts from unrecompensed damage.
        
        if (booking.getStatus() == BookingStatus.CHECKOUT_DAMAGE_DISPUTE) {
            log.info("[Saga] Suspending saga {} - booking {} has unresolved damage claim",
                    saga.getSagaId(), saga.getBookingId());
            saga.setStatus(SagaStatus.SUSPENDED);
            saga.setErrorMessage("Checkout damage claim pending resolution");
            throw new IllegalStateException(
                    "Cannot proceed with checkout: damage claim awaiting resolution");
        }
        
        // Also check if there's an unresolved checkout damage claim linked
        if (booking.getCheckoutDamageClaim() != null && 
                booking.getCheckoutDamageClaim().getStatus().requiresDepositHold()) {
            log.warn("[Saga] Blocking saga {} - checkout damage claim {} requires deposit hold",
                    saga.getSagaId(), booking.getCheckoutDamageClaim().getId());
            saga.setStatus(SagaStatus.SUSPENDED);
            saga.setErrorMessage("Deposit held for damage claim #" + booking.getCheckoutDamageClaim().getId());
            throw new IllegalStateException(
                    "Deposit release blocked: damage claim #" + booking.getCheckoutDamageClaim().getId() + " pending");
        }
        
        // Check deposit hold flag
        if (Boolean.FALSE.equals(booking.getSecurityDepositReleased()) && 
                booking.getSecurityDepositHoldReason() != null) {
            log.info("[Saga] Saga {} blocked - deposit held for: {}",
                    saga.getSagaId(), booking.getSecurityDepositHoldReason());
            saga.setStatus(SagaStatus.SUSPENDED);
            saga.setErrorMessage("Deposit held: " + booking.getSecurityDepositHoldReason());
            throw new IllegalStateException(
                    "Deposit release blocked: " + booking.getSecurityDepositHoldReason());
        }

        // Validate checkout data exists
        if (booking.getEndOdometer() == null) {
            throw new IllegalStateException("Završni kilometraža nije unesena");
        }
        if (booking.getEndFuelLevel() == null) {
            throw new IllegalStateException("Završni nivo goriva nije unesen");
        }

        // C-2 FIX: Validate guest checkout photos exist (minimum 6 required type slots)
        long guestCheckoutPhotoTypeCount = checkInPhotoRepository.countCheckoutPhotoTypes(booking.getId());
        if (guestCheckoutPhotoTypeCount < 6) {
            throw new IllegalStateException(
                    String.format("Nedovoljno fotografija za checkout. Potrebno: 6 vrsta, pronađeno: %d",
                            guestCheckoutPhotoTypeCount));
        }

        log.debug("[Saga] Validation passed for booking {}", saga.getBookingId());
    }

    private void executeCalculateCharges(CheckoutSagaState saga) {
        Booking booking = loadBooking(saga.getBookingId());

        // Calculate extra mileage
        int allowedKm = calculateAllowedKm(booking);
        int actualKm = booking.getEndOdometer() - booking.getStartOdometer();
        int extraKm = Math.max(0, actualKm - allowedKm);

        if (extraKm > 0) {
            saga.setExtraMileageKm(extraKm);
            saga.setExtraMileageCharge(mileageRatePerKm.multiply(BigDecimal.valueOf(extraKm)));
        }

        // Calculate fuel difference
        int fuelDiff = booking.getStartFuelLevel() - booking.getEndFuelLevel();
        if (fuelDiff > 0) {
            saga.setFuelDifferencePercent(fuelDiff);
            saga.setFuelCharge(fuelRatePerPercent.multiply(BigDecimal.valueOf(fuelDiff)));
        }

        // ================================================================
        // PHASE 4D: TIERED LATE FEE CALCULATION
        // ================================================================
        // Three-tier system to incentivize timely returns while being fair:
        //   Tier 1 (0-2h):  Base rate - minor delays, grace expired
        //   Tier 2 (2-6h):  1.5x rate - significant delay, inconveniences host
        //   Tier 3 (6h+):   2x rate - major delay, approaching no-return territory
        //
        // Vehicle Not Returned Flag: Set at 24+ hours for insurance/escalation
        
        if (booking.getScheduledReturnTime() != null && booking.getTripEndedAt() != null) {
            Instant scheduledReturn = booking.getScheduledReturnTime();
            Instant actualReturn = booking.getTripEndedAt();
            Instant graceEnd = scheduledReturn.plus(LATE_GRACE_MINUTES, ChronoUnit.MINUTES);
            
            if (actualReturn.isAfter(graceEnd)) {
                // P0 FIX: Measure billable minutes from GRACE END, not scheduled return.
                // The grace period is free; billing starts only after grace expires.
                // Use actualReturnTime (set at guest checkout step) as the billing source,
                // not tripEndedAt which may be set later during completion.
                Instant billingSource = booking.getActualReturnTime() != null 
                        ? booking.getActualReturnTime() 
                        : actualReturn;
                long lateMinutes = ChronoUnit.MINUTES.between(graceEnd, billingSource);
                lateMinutes = Math.max(0, lateMinutes); // Safety: never negative
                long lateHours = Math.min((lateMinutes + 59) / 60, MAX_LATE_HOURS);
                
                // Calculate tiered late fee
                TieredLateFeeResult feeResult = calculateTieredLateFee(lateHours);
                
                saga.setLateHours((int) lateHours);
                saga.setLateFee(feeResult.totalFee);
                
                // Update booking with tier information for audit trail
                booking.setLateFeeTier(feeResult.tier);
                booking.setLateFeeAmount(feeResult.totalFee);
                
                // Record audit event for the tier applied
                recordLateFeeAuditEvent(booking, feeResult.tier, feeResult.totalFee, lateHours);
                
                // Update metrics based on tier
                switch (feeResult.tier) {
                    case 1 -> lateFeesTier1Counter.increment();
                    case 2 -> lateFeesTier2Counter.increment();
                    case 3 -> lateFeesTier3Counter.increment();
                }
                
                log.info("[Saga-Phase4D] Tiered late fee for booking {}: {} hours late = Tier {} = {} RSD",
                    saga.getBookingId(), lateHours, feeResult.tier, feeResult.totalFee);
                
                // ================================================================
                // PHASE 4D: VEHICLE NOT RETURNED FLAG
                // ================================================================
                // If 24+ hours overdue, flag for manual review and potential insurance notification
                if (lateHours >= vehicleNotReturnedThresholdHours) {
                    flagVehicleNotReturned(booking);
                }
            }
        }

        // ================================================================
        // P0 FIX: INCLUDE DAMAGE CLAIM AMOUNT IN TOTAL CHARGES
        // ================================================================
        // If host reported damage and guest accepted (or admin approved),
        // the approved damage amount must be included in total charges.
        DamageClaim checkoutClaim = booking.getCheckoutDamageClaim();
        if (checkoutClaim != null && checkoutClaim.getApprovedAmount() != null
                && checkoutClaim.getStatus() != null
                && checkoutClaim.getStatus().isApproved()) {
            saga.setDamageClaimCharge(checkoutClaim.getApprovedAmount());
            log.info("[Saga] Including damage claim charge {} RSD for booking {}",
                    checkoutClaim.getApprovedAmount(), saga.getBookingId());
        }

        // Calculate total
        BigDecimal total = BigDecimal.ZERO;
        if (saga.getExtraMileageCharge() != null) total = total.add(saga.getExtraMileageCharge());
        if (saga.getFuelCharge() != null) total = total.add(saga.getFuelCharge());
        if (saga.getLateFee() != null) total = total.add(saga.getLateFee());
        if (saga.getDamageClaimCharge() != null) total = total.add(saga.getDamageClaimCharge());

        saga.setTotalCharges(total);

        log.info("[Saga] Calculated charges for booking {}: total={}",
                saga.getBookingId(), total);
        
        // Save booking with updated late fee tier
        bookingRepository.save(booking);
    }
    
    /**
     * Phase 4D: Calculate late fee using tiered structure.
     * 
     * <p>Tiers are calculated progressively - hours in each tier are charged
     * at that tier's rate:
     * <ul>
     *   <li>Hours 1-2: Tier 1 rate (500 RSD/hour default)</li>
     *   <li>Hours 3-6: Tier 2 rate (750 RSD/hour default)</li>
     *   <li>Hours 7+:  Tier 3 rate (1000 RSD/hour default)</li>
     * </ul>
     * 
     * @param lateHours Total hours late (rounded up from minutes)
     * @return Fee result with total amount and highest tier reached
     */
    private TieredLateFeeResult calculateTieredLateFee(long lateHours) {
        BigDecimal totalFee = BigDecimal.ZERO;
        int highestTier = 1;
        
        // Tier 1: First tier1MaxHours hours
        long tier1Hours = Math.min(lateHours, tier1MaxHours);
        totalFee = totalFee.add(BigDecimal.valueOf(tier1Hours * tier1RateRsd));
        
        // Tier 2: Hours from tier1MaxHours to tier2MaxHours
        if (lateHours > tier1MaxHours) {
            long tier2Hours = Math.min(lateHours - tier1MaxHours, tier2MaxHours - tier1MaxHours);
            totalFee = totalFee.add(BigDecimal.valueOf(tier2Hours * tier2RateRsd));
            highestTier = 2;
        }
        
        // Tier 3: Hours beyond tier2MaxHours
        if (lateHours > tier2MaxHours) {
            long tier3Hours = lateHours - tier2MaxHours;
            totalFee = totalFee.add(BigDecimal.valueOf(tier3Hours * tier3RateRsd));
            highestTier = 3;
        }
        
        log.debug("[Phase4D] Late fee breakdown: {} hours = {} RSD (highest tier: {})",
                lateHours, totalFee, highestTier);
        
        return new TieredLateFeeResult(totalFee, highestTier);
    }
    
    /**
     * Phase 4D: Flag vehicle as not returned (24+ hours overdue).
     * 
     * <p>This triggers:
     * <ul>
     *   <li>Manual review notification to ops team</li>
     *   <li>Potential insurance notification</li>
     *   <li>Audit event for compliance</li>
     * </ul>
     */
    private void flagVehicleNotReturned(Booking booking) {
        if (Boolean.TRUE.equals(booking.getVehicleNotReturnedFlag())) {
            log.debug("[Phase4D] Vehicle already flagged as not returned for booking {}",
                    booking.getId());
            return;
        }
        
        booking.setVehicleNotReturnedFlag(true);
        booking.setVehicleNotReturnedFlaggedAt(Instant.now());
        
        vehicleNotReturnedCounter.increment();
        
        // Record audit event
        eventService.recordEvent(
            booking,
            booking.getCheckoutSessionId(),
            CheckInEventType.VEHICLE_NOT_RETURNED_FLAG,
            null, // System triggered
            CheckInActorRole.SYSTEM,
            Map.of(
                "thresholdHours", vehicleNotReturnedThresholdHours,
                "scheduledReturn", booking.getScheduledReturnTime().toString(),
                "flaggedAt", Instant.now().toString()
            )
        );
        
        // TODO: Send notification to ops team for manual review
        // TODO: Consider insurance notification based on policy
        
        log.warn("[Phase4D] VEHICLE NOT RETURNED: Booking {} flagged as 24+ hours overdue. " +
                "Manual review required. Scheduled return: {}",
                booking.getId(), booking.getScheduledReturnTime());
    }
    
    /**
     * Record audit event for late fee tier application.
     */
    private void recordLateFeeAuditEvent(Booking booking, int tier, BigDecimal amount, long hours) {
        CheckInEventType eventType = switch (tier) {
            case 1 -> CheckInEventType.LATE_FEE_TIER_1_APPLIED;
            case 2 -> CheckInEventType.LATE_FEE_TIER_2_APPLIED;
            case 3 -> CheckInEventType.LATE_FEE_TIER_3_APPLIED;
            default -> CheckInEventType.LATE_FEE_TIER_1_APPLIED;
        };
        
        eventService.recordEvent(
            booking,
            booking.getCheckoutSessionId(),
            eventType,
            null,
            CheckInActorRole.SYSTEM,
            Map.of(
                "tier", tier,
                "amount", amount.toString(),
                "hours", hours,
                "scheduledReturn", booking.getScheduledReturnTime() != null 
                    ? booking.getScheduledReturnTime().toString() : "N/A",
                "actualReturn", booking.getTripEndedAt() != null 
                    ? booking.getTripEndedAt().toString() : "N/A"
            )
        );
    }
    
    /**
     * Result holder for tiered late fee calculation.
     */
    private record TieredLateFeeResult(BigDecimal totalFee, int tier) {}

    private void executeCaptureDeposit(CheckoutSagaState saga) {
        if (saga.getTotalCharges() == null || saga.getTotalCharges().compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[Saga] No charges to capture for booking {}", saga.getBookingId());
            saga.setCapturedAmount(BigDecimal.ZERO);
            return;
        }

        Booking booking = loadBooking(saga.getBookingId());

        // AUDIT-C2-FIX: Re-authorize deposit if auth expired before capture attempt.
        if (booking.getDepositAuthExpiresAt() != null
                && booking.getDepositAuthExpiresAt().isBefore(Instant.now())
                && booking.getStoredPaymentMethodId() != null) {
            log.info("[Saga] Deposit auth expired for booking {} - attempting re-authorization", booking.getId());
            PaymentResult reauthResult = bookingPaymentService.reauthorizeDeposit(booking.getId());
            if (reauthResult.isSuccess()) {
                Long bookingId = booking.getId();
                booking = bookingRepository.findById(booking.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
                log.info("[Saga] Deposit re-authorized for booking {} - new authId: {}",
                        booking.getId(), booking.getDepositAuthorizationId());
            } else if (reauthResult.isRedirectRequired()) {
                saga.setStatus(SagaStatus.SUSPENDED);
                saga.setErrorMessage("Deposit re-authorization requires 3DS - awaiting guest action");
                sagaRepository.save(saga);
                return;
            } else {
                log.error("[Saga] Deposit re-auth failed for booking {}", booking.getId());
                // Fall through to capture attempt which will fail and trigger normal retry/escalation.
            }
        }

        // Idempotency: If deposit was already resolved (e.g. admin dispute resolution
        // handled capture/release before saga started), skip to avoid double-capture.
        if (booking.getSecurityDepositResolvedAt() != null) {
            log.info("[Saga] Deposit already resolved for booking {} (admin dispute flow) — skipping capture",
                    saga.getBookingId());
            saga.setCapturedAmount(BigDecimal.ZERO);
            return;
        }

        // Treat null securityDeposit as ZERO - allows checkout to proceed without deposit feature
        BigDecimal depositAmount = booking.getSecurityDeposit() != null 
                ? booking.getSecurityDeposit() 
                : BigDecimal.ZERO;
        
        if (depositAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[Saga] No security deposit held for booking {} - skipping capture", saga.getBookingId());
            saga.setCapturedAmount(BigDecimal.ZERO);
            return;
        }

        String depositAuthId = booking.getDepositAuthorizationId();
        if (depositAuthId == null || depositAuthId.isBlank()) {
            log.warn("[Saga] No deposit authorization ID for booking {} - skipping capture", saga.getBookingId());
            saga.setCapturedAmount(BigDecimal.ZERO);
            return;
        }
        
        BigDecimal captureAmount = saga.getTotalCharges().min(depositAmount);

        // P0-4 FIX: Use deterministic idempotency key (not random wrapper) so retries are idempotent.
        ProviderResult captureResult = paymentProvider.capture(depositAuthId, captureAmount,
                PaymentIdempotencyKey.forDepositCapture(saga.getBookingId(), 1));

        if (!captureResult.isSuccess()) {
            log.error("[Saga] Deposit capture FAILED for booking {}: {} - {}",
                    saga.getBookingId(), captureResult.getErrorCode(), captureResult.getErrorMessage());
            throw new IllegalStateException(
                    "Neuspešno naplata depozita: " + captureResult.getErrorMessage());
        }

        saga.setCapturedAmount(captureAmount);
        saga.setCaptureTransactionId(captureResult.getProviderTransactionId());

        log.info("[Saga] Captured {} RSD from deposit for booking {} (txn: {})",
                captureAmount, saga.getBookingId(), saga.getCaptureTransactionId());

        // P1 FIX: Direct-charge the remainder when total charges exceed the deposit
        BigDecimal remainderAmount = saga.getTotalCharges().subtract(depositAmount);
        if (remainderAmount.compareTo(BigDecimal.ZERO) > 0) {
            log.info("[Saga] Charges ({} RSD) exceed deposit ({} RSD) for booking {} — direct-charging remainder {} RSD",
                    saga.getTotalCharges(), depositAmount, saga.getBookingId(), remainderAmount);

            Booking bookingForCharge = loadBooking(saga.getBookingId());

            // R2-FIX: Use the stored payment method token (tokenized card) for direct charges,
            // not the booking authorization ID. An authorization ID is a hold reference, not a
            // valid charge token — real gateways reject it. storedPaymentMethodId is persisted
            // at booking creation and represents the guest's actual payment instrument.
            String chargePaymentMethod = bookingForCharge.getStoredPaymentMethodId();
            if (chargePaymentMethod == null || chargePaymentMethod.isBlank()) {
                log.error("[Saga] REMAINDER CHARGE SKIPPED for booking {} — storedPaymentMethodId is missing. "
                        + "Cannot charge {} RSD remainder without a valid payment method.",
                        saga.getBookingId(), remainderAmount);
                saga.setRemainderAmount(remainderAmount);
                saga.setRemainderTransactionId("FAILED:MISSING_PAYMENT_METHOD");
                notificationService.createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(bookingForCharge.getCar().getOwner().getId())
                        .type(NotificationType.PAYMENT_FAILED)
                        .message(String.format(
                                "Dodatni troškovi od %s RSD za rezervaciju #%d nisu naplaćeni — "
                                + "nije pronađen sačuvan način plaćanja. Potrebno ručno rešavanje.",
                                remainderAmount, saga.getBookingId()))
                        .relatedEntityId(String.valueOf(saga.getBookingId()))
                        .build());
                return;
            }

            PaymentProvider.PaymentRequest remainderRequest = PaymentProvider.PaymentRequest.builder()
                    .bookingId(saga.getBookingId())
                    .userId(bookingForCharge.getRenter().getId())
                    .amount(remainderAmount)
                    .currency("RSD")
                    .description(String.format("Dodatni troškovi iznad depozita - Rezervacija #%d", saga.getBookingId()))
                    .type(PaymentProvider.PaymentType.LATE_FEE)
                    .paymentMethodId(chargePaymentMethod)
                    .build();

            // P0-4 FIX: Deterministic key for remainder charge.
            ProviderResult remainderResult = paymentProvider.charge(remainderRequest,
                    PaymentIdempotencyKey.forCheckoutRemainder(saga.getBookingId()));

            if (remainderResult.isSuccess()) {
                saga.setRemainderAmount(remainderAmount);
                saga.setRemainderTransactionId(remainderResult.getProviderTransactionId());
                log.info("[Saga] Remainder {} RSD charged for booking {} (txn: {})",
                        remainderAmount, saga.getBookingId(), remainderResult.getProviderTransactionId());
            } else {
                // Log but do not fail the saga — deposit was captured, remainder becomes
                // an outstanding invoice the admin can follow up on
                saga.setRemainderAmount(remainderAmount);
                saga.setRemainderTransactionId("FAILED:" + remainderResult.getErrorCode());
                log.error("[Saga] REMAINDER CHARGE FAILED for booking {} ({} RSD): {} — outstanding invoice created",
                        saga.getBookingId(), remainderAmount, remainderResult.getErrorMessage());

                // Notify admin about outstanding balance
                notificationService.createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(bookingForCharge.getCar().getOwner().getId())
                        .type(NotificationType.PAYMENT_FAILED)
                        .message(String.format(
                                "Dodatni troškovi od %s RSD za rezervaciju #%d nisu naplaćeni. " +
                                "Depozit je zadržan, ali preostali iznos zahteva ručno rešavanje.",
                                remainderAmount, saga.getBookingId()))
                        .relatedEntityId(String.valueOf(saga.getBookingId()))
                        .build());
            }
        } else {
            saga.setRemainderAmount(BigDecimal.ZERO);
        }
    }

    private void executeReleaseDeposit(CheckoutSagaState saga) {
        Booking booking = loadBooking(saga.getBookingId());

        // Idempotency: If deposit was already resolved (e.g. admin dispute resolution
        // handled capture/release before saga started), skip to avoid double-release.
        if (booking.getSecurityDepositResolvedAt() != null) {
            log.info("[Saga] Deposit already resolved for booking {} (admin dispute flow) — skipping release",
                    saga.getBookingId());
            saga.setReleasedAmount(BigDecimal.ZERO);
            return;
        }

        // Treat null securityDeposit as ZERO - allows checkout to proceed without deposit feature
        BigDecimal depositAmount = booking.getSecurityDeposit() != null 
                ? booking.getSecurityDeposit() 
                : BigDecimal.ZERO;
        BigDecimal capturedAmount = saga.getCapturedAmount() != null ? saga.getCapturedAmount() : BigDecimal.ZERO;
        
        if (depositAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[Saga] No security deposit to release for booking {}", saga.getBookingId());
            saga.setReleasedAmount(BigDecimal.ZERO);
            return;
        }
        
        BigDecimal releaseAmount = depositAmount.subtract(capturedAmount);

        if (releaseAmount.compareTo(BigDecimal.ZERO) > 0) {
            // ================================================================
            // P0 FIX: DEFER DEPOSIT RELEASE TO 48H SCHEDULER
            // ================================================================
            // Do NOT release the deposit immediately. Instead, schedule it for
            // release 48 hours after checkout completion. This prevents BUG-007
            // regression where a claim is filed after the deposit is released.
            //
            // The PaymentLifecycleScheduler.releaseDepositsAfterTrip() job runs
            // every 30 minutes and releases deposits T+48h after trip end,
            // with defense-in-depth damage claim checks.
            // ================================================================
            
            Instant releaseScheduledAt = Instant.now().plus(48, ChronoUnit.HOURS);
            booking.setSecurityDepositReleased(false); // NOT released yet
            booking.setSecurityDepositHoldUntil(releaseScheduledAt);
            // H-2 FIX: Don't overwrite admin-set hold reason
            if (booking.getSecurityDepositHoldReason() == null) {
                booking.setSecurityDepositHoldReason("48h post-checkout hold period (standard policy)");
            }
            bookingRepository.save(booking);
            
            saga.setReleasedAmount(BigDecimal.ZERO); // Nothing released in saga
            saga.setReleaseTransactionId("DEFERRED-48H-" + releaseScheduledAt);
            
            log.info("[Saga] Deposit release DEFERRED for booking {} — {} RSD held until {} (48h policy). " +
                    "PaymentLifecycleScheduler will release if no claims are filed.",
                    saga.getBookingId(), releaseAmount, releaseScheduledAt);
        } else {
            // Full deposit was captured — nothing to release
            booking.setSecurityDepositReleased(true);
            booking.setSecurityDepositResolvedAt(Instant.now());
            bookingRepository.save(booking);
            saga.setReleasedAmount(BigDecimal.ZERO);
            log.debug("[Saga] Full deposit captured for booking {} — nothing to release", saga.getBookingId());
        }
    }

    /**
     * Complete booking step - IDEMPOTENT design for saga retry safety.
     * 
     * <p>Enterprise Pattern: Idempotent Operations</p>
     * <ul>
     *   <li>Checks current status before updating</li>
     *   <li>Skips update if already COMPLETED</li>
     *   <li>Prevents OptimisticLockException on retry</li>
     *   <li>Safe for saga recovery scheduler</li>
     * </ul>
     */
    private void executeCompleteBooking(CheckoutSagaState saga) {
        Booking booking = loadBooking(saga.getBookingId());

        // IDEMPOTENT: Only update if not already COMPLETED.
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            booking.setStatus(BookingStatus.COMPLETED);
            bookingRepository.save(booking);
            log.info("[Saga] Booking {} transitioned to COMPLETED", saga.getBookingId());
        } else {
            log.debug("[Saga] Booking {} already COMPLETED - skipping status update (idempotent retry)", 
                saga.getBookingId());
        }

        try {
            String batchRef = "SAGA_AUTO_" + saga.getSagaId().toString().substring(0, 8) + "_" + 
                              System.currentTimeMillis();
            PaymentResult payoutResult = bookingPaymentService.processHostPayout(booking, batchRef);
            
            if (payoutResult.isSuccess()) {
                log.info("[Saga] Auto host payout queued: {} RSD for booking {} (ref: {})",
                        payoutResult.getAmount(), booking.getId(), batchRef);
            } else {
                // Payout failure is non-critical — admin can trigger manually
                log.warn("[Saga] Auto host payout failed for booking {} — admin can retry. Error: {}",
                        booking.getId(), payoutResult.getErrorMessage());
            }
        } catch (Exception e) {
            // Don't fail the saga for payout issues — admin batch will catch missed payouts
            log.warn("[Saga] Auto host payout exception for booking {} — will be included in admin batch: {}",
                    booking.getId(), e.getMessage());
        }
    }

    // ========== COMPENSATION ==========

    @Transactional
    public void startCompensation(CheckoutSagaState saga) {
        log.warn("[Saga] Starting compensation for saga {} from step {}",
                saga.getSagaId(), saga.getFailedAtStep());

        saga.setStatus(SagaStatus.COMPENSATING);

        // Publish CQRS event for compensation start
        publishEvent(new CheckoutDomainEvent.SagaCompensating(
                saga.getBookingId(),
                saga.getSagaId(),
                saga.getFailedAtStep() != null ? saga.getFailedAtStep().name() : "UNKNOWN",
                saga.getErrorMessage(),
                Instant.now()
        ));

        try {
            // Compensate in reverse order
            CheckoutSagaStep stepToCompensate = saga.getLastCompletedStep();

            while (stepToCompensate != null) {
                if (stepToCompensate.hasCompensation()) {
                    compensateStep(saga, stepToCompensate);
                }
                stepToCompensate = stepToCompensate.previous();
            }

            saga.setStatus(SagaStatus.COMPENSATED);
            sagaCompensatedCounter.increment();

            log.info("[Saga] Compensation completed for saga {}", saga.getSagaId());

        } catch (Exception e) {
            log.error("[Saga] Compensation failed for saga {}: {}",
                    saga.getSagaId(), e.getMessage(), e);
            saga.setStatus(SagaStatus.FAILED);
            saga.setErrorMessage("Compensation failed: " + e.getMessage());
        }

        sagaRepository.save(saga);
    }

    private void compensateStep(CheckoutSagaState saga, CheckoutSagaStep step) {
        log.debug("[Saga] Compensating step {} for saga {}", step, saga.getSagaId());

        switch (step) {
            case CAPTURE_DEPOSIT -> compensateCaptureDeposit(saga);
            case RELEASE_DEPOSIT -> compensateReleaseDeposit(saga);
            case COMPLETE_BOOKING -> compensateCompleteBooking(saga);
            default -> log.debug("[Saga] No compensation needed for step {}", step);
        }
    }

    private void compensateCaptureDeposit(CheckoutSagaState saga) {
        if (saga.getCaptureTransactionId() != null && saga.getCapturedAmount() != null
                && saga.getCapturedAmount().compareTo(BigDecimal.ZERO) > 0) {
            try {
                // P0-4 FIX: Deterministic key for saga compensation refund.
                ProviderResult refundResult = paymentProvider.refund(
                        saga.getCaptureTransactionId(),
                        saga.getCapturedAmount(),
                        "Saga compensation — reverting deposit capture for booking " + saga.getBookingId(),
                        PaymentIdempotencyKey.forSagaCompensation(saga.getBookingId()));

                if (refundResult.isSuccess()) {
                    log.info("[Saga] Refunded captured amount {} RSD for booking {} (original txn: {}, refund txn: {})",
                            saga.getCapturedAmount(), saga.getBookingId(),
                            saga.getCaptureTransactionId(), refundResult.getProviderRefundId());
                } else {
                    log.error("[Saga] CRITICAL: Failed to refund deposit capture for booking {}. " +
                            "Manual intervention required. Error: {}",
                            saga.getBookingId(), refundResult.getErrorMessage());
                    // C-7 FIX: Fail the compensation so saga doesn't mark as COMPENSATED
                    throw new RuntimeException("Compensation refund refused by payment provider for booking "
                            + saga.getBookingId() + ": " + refundResult.getErrorMessage());
                }
            } catch (Exception e) {
                log.error("[Saga] CRITICAL: Exception during deposit capture compensation for booking {}: {}",
                        saga.getBookingId(), e.getMessage(), e);
                // C-7 FIX: Re-throw so saga transitions to FAILED, not COMPENSATED
                throw new RuntimeException("Compensation refund failed for booking " + saga.getBookingId(), e);
            }
        }
    }

    private void compensateCompleteBooking(CheckoutSagaState saga) {
        Booking booking = loadBooking(saga.getBookingId());
        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);  // Revert to pre-saga status
        bookingRepository.save(booking);

        log.info("[Saga] Reverted booking {} status to CHECKOUT_HOST_COMPLETE", saga.getBookingId());
    }

    /**
     * Compensate the local side effects of RELEASE_DEPOSIT.
     *
     * <p>The release step does not call the provider immediately. It either schedules a
     * deferred release (48h hold) or marks the deposit as fully resolved when the whole
     * deposit was captured. Compensation therefore needs to undo those local markers so
     * earlier steps can be safely rolled back.
     */
    private void compensateReleaseDeposit(CheckoutSagaState saga) {
        Booking booking = loadBooking(saga.getBookingId());

        if (saga.getReleaseTransactionId() != null && saga.getReleaseTransactionId().startsWith("DEFERRED-48H-")) {
            booking.setSecurityDepositHoldUntil(null);
            if ("48h post-checkout hold period (standard policy)".equals(booking.getSecurityDepositHoldReason())) {
                booking.setSecurityDepositHoldReason(null);
            }
            log.info("[Saga] Cleared deferred deposit release markers for booking {}", saga.getBookingId());
        } else {
            booking.setSecurityDepositReleased(false);
            booking.setSecurityDepositResolvedAt(null);
            log.info("[Saga] Re-opened deposit resolution markers for booking {}", saga.getBookingId());
        }

        bookingRepository.save(booking);
    }

    // ========== HELPER METHODS ==========

    private Booking loadBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
    }

    private int calculateAllowedKm(Booking booking) {
        // Calculate allowed km based on rental duration and car-specific daily limit
        long days = ChronoUnit.DAYS.between(
                booking.getStartTime().toLocalDate(),
                booking.getEndTime().toLocalDate()) + 1;

        // Use car-specific daily mileage limit (host-configurable), default 200km/day
        int dailyLimit = 200;
        if (booking.getCar() != null && booking.getCar().getDailyMileageLimitKm() != null) {
            dailyLimit = booking.getCar().getDailyMileageLimitKm();
        }
        
        return (int) (days * dailyLimit);
    }

    private void notifySagaCompleted(CheckoutSagaState saga) {
        try {
            Booking booking = loadBooking(saga.getBookingId());

            // Notify host
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getCar().getOwner().getId())
                    .type(NotificationType.CHECKOUT_COMPLETE)
                    .message("Vraćanje vozila je završeno. Ukupni troškovi: " + saga.getTotalCharges() + " RSD")
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build());

            // Notify guest
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getRenter().getId())
                    .type(NotificationType.CHECKOUT_COMPLETE)
                    .message("Vaša rezervacija je završena. Depozit od " + saga.getReleasedAmount() + " RSD će biti vraćen.")
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build());

        } catch (Exception e) {
            log.warn("[Saga] Failed to send completion notifications: {}", e.getMessage());
        }
    }

    // ========== RECOVERY ==========

    /**
     * Retry a failed saga.
     */
    @Transactional
    public CheckoutSagaState retrySaga(UUID sagaId) {
        CheckoutSagaState saga = sagaRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new ResourceNotFoundException("Saga not found: " + sagaId));

        if (!saga.canRetry()) {
            throw new IllegalStateException("Saga cannot be retried: " + saga.getStatus());
        }

        saga.setRetryCount(saga.getRetryCount() + 1);
        saga.setLastRetryAt(Instant.now());
        saga.setStatus(SagaStatus.PENDING);
        saga.setErrorMessage(null);

        // Resume from failed step
        saga.setCurrentStep(saga.getFailedAtStep());
        saga.setFailedAtStep(null);

        log.info("[Saga] Retrying saga {} (attempt {})", sagaId, saga.getRetryCount());

        return executeSaga(saga);
    }
    
    // ========== VAL-010: DAMAGE DISPUTE RESOLUTION ==========
    
    /**
     * Resume a suspended saga after damage claim resolution (VAL-010).
     * 
     * <p>Called when:
     * <ul>
     *   <li>Guest accepts damage claim - deposit captured, then resume</li>
     *   <li>Guest disputes and admin resolves - appropriate action, then resume</li>
     *   <li>Timeout escalation resolved by admin</li>
     * </ul>
     * 
     * @param bookingId Booking with resolved damage claim
     * @return Resumed saga state
     * @throws ResourceNotFoundException if no suspended saga exists
     * @throws IllegalStateException if saga is not suspended
     */
    @Transactional
    public CheckoutSagaState resumeSuspendedSaga(Long bookingId) {
        CheckoutSagaState saga = sagaRepository.findActiveSagaForBooking(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active saga found for booking: " + bookingId));
        
        if (saga.getStatus() != SagaStatus.SUSPENDED) {
            throw new IllegalStateException(
                    "Saga is not suspended. Current status: " + saga.getStatus());
        }
        
        // Verify booking is no longer blocked
        Booking booking = loadBooking(bookingId);
        if (booking.getStatus() == BookingStatus.CHECKOUT_DAMAGE_DISPUTE) {
            throw new IllegalStateException(
                    "Cannot resume saga: booking still in CHECKOUT_DAMAGE_DISPUTE status");
        }
        
        log.info("[Saga] Resuming suspended saga {} for booking {} after damage resolution",
                saga.getSagaId(), bookingId);
        
        // Reset saga state
        saga.setStatus(SagaStatus.PENDING);
        saga.setErrorMessage(null);
        saga.setUpdatedAt(Instant.now());
        
        // Continue from validation step (will pass this time)
        saga.setCurrentStep(CheckoutSagaStep.VALIDATE_RETURN);
        
        // Publish resume event
        publishEvent(new CheckoutDomainEvent.SagaResumed(
                bookingId,
                saga.getSagaId(),
                Instant.now()
        ));
        
        return executeSaga(saga);
    }
    
    /**
     * Check if a booking has a suspended saga due to damage claim.
     */
    @Transactional(readOnly = true)
    public boolean hasSuspendedSaga(Long bookingId) {
        return sagaRepository.findActiveSagaForBooking(bookingId)
                .map(saga -> saga.getStatus() == SagaStatus.SUSPENDED)
                .orElse(false);
    }

    /**
     * Get saga status for a booking.
     */
    @Transactional(readOnly = true)
    public Optional<CheckoutSagaState> getSagaStatus(Long bookingId) {
        return sagaRepository.findActiveSagaForBooking(bookingId);
    }

    // ========== CQRS EVENT PUBLISHING ==========

    /**
     * Publish a checkout domain event for CQRS read model synchronization.
     * 
     * <p>Events are published asynchronously and consumed by view sync listeners.
     */
    private void publishEvent(CheckoutDomainEvent event) {
        try {
            eventPublisher.publishEvent(event);
            log.debug("[Saga] Published event: {}", event.getClass().getSimpleName());
        } catch (Exception e) {
            // Log but don't fail the saga - event publishing is non-critical
            log.error("[Saga] Failed to publish event {}: {}", 
                    event.getClass().getSimpleName(), e.getMessage());
        }
    }
}
