package org.example.rentoza.booking.checkout.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaState.SagaStatus;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 *   <li>CALCULATE_CHARGES - Calculate extra fees</li>
 *   <li>CAPTURE_DEPOSIT - Charge from held deposit</li>
 *   <li>RELEASE_DEPOSIT - Return unused deposit</li>
 *   <li>COMPLETE_BOOKING - Mark booking as completed</li>
 * </ol>
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
    private final NotificationService notificationService;

    // Metrics
    private final Counter sagaStartedCounter;
    private final Counter sagaCompletedCounter;
    private final Counter sagaFailedCounter;
    private final Counter sagaCompensatedCounter;
    private final Timer sagaDurationTimer;

    // Configuration (could be externalized)
    private static final BigDecimal MILEAGE_RATE_PER_KM = new BigDecimal("0.25");  // EUR per km
    private static final BigDecimal FUEL_RATE_PER_PERCENT = new BigDecimal("0.50"); // EUR per %
    private static final BigDecimal LATE_FEE_PER_HOUR = new BigDecimal("10.00");    // EUR per hour

    public CheckoutSagaOrchestrator(
            CheckoutSagaStateRepository sagaRepository,
            BookingRepository bookingRepository,
            NotificationService notificationService,
            MeterRegistry meterRegistry) {
        this.sagaRepository = sagaRepository;
        this.bookingRepository = bookingRepository;
        this.notificationService = notificationService;

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

        this.sagaDurationTimer = Timer.builder("checkout.saga.duration")
                .description("Checkout saga total duration")
                .register(meterRegistry);
    }

    // ========== SAGA LIFECYCLE ==========

    /**
     * Start a new checkout saga for a booking.
     * 
     * @param bookingId Booking to checkout
     * @return Saga state
     * @throws IllegalStateException if saga already exists
     */
    @Transactional
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

        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Booking must be in CHECK_OUT_COMPLETE status. Current: " + booking.getStatus());
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

                // Notify parties
                notifySagaCompleted(saga);
            }

        } catch (Exception e) {
            log.error("[Saga] Error in saga {} at step {}: {}",
                    saga.getSagaId(), saga.getCurrentStep(), e.getMessage(), e);

            saga.setStatus(SagaStatus.FAILED);
            saga.setFailedAtStep(saga.getCurrentStep());
            saga.setErrorMessage(e.getMessage());

            sagaFailedCounter.increment();

            // Start compensation if there are compensable steps
            if (saga.getLastCompletedStep() != null && saga.getLastCompletedStep().hasCompensation()) {
                startCompensation(saga);
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

    private void executeValidateReturn(CheckoutSagaState saga) {
        Booking booking = loadBooking(saga.getBookingId());

        // Validate checkout data exists
        if (booking.getEndOdometer() == null) {
            throw new IllegalStateException("Završni kilometraža nije unesena");
        }
        if (booking.getEndFuelLevel() == null) {
            throw new IllegalStateException("Završni nivo goriva nije unesen");
        }

        // TODO: Validate checkout photos exist
        // TODO: Check for unresolved damage claims

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
            saga.setExtraMileageCharge(MILEAGE_RATE_PER_KM.multiply(BigDecimal.valueOf(extraKm)));
        }

        // Calculate fuel difference
        int fuelDiff = booking.getStartFuelLevel() - booking.getEndFuelLevel();
        if (fuelDiff > 0) {
            saga.setFuelDifferencePercent(fuelDiff);
            saga.setFuelCharge(FUEL_RATE_PER_PERCENT.multiply(BigDecimal.valueOf(fuelDiff)));
        }

        // Calculate late fee
        if (booking.getTripEndedAt() != null && booking.getEndTime() != null) {
            long lateMinutes = ChronoUnit.MINUTES.between(
                    booking.getEndTime().atZone(java.time.ZoneId.of("Europe/Belgrade")).toInstant(),
                    booking.getTripEndedAt());
            if (lateMinutes > 30) {  // 30 minute grace period
                int lateHours = (int) Math.ceil((lateMinutes - 30) / 60.0);
                saga.setLateHours(lateHours);
                saga.setLateFee(LATE_FEE_PER_HOUR.multiply(BigDecimal.valueOf(lateHours)));
            }
        }

        // Calculate total
        BigDecimal total = BigDecimal.ZERO;
        if (saga.getExtraMileageCharge() != null) total = total.add(saga.getExtraMileageCharge());
        if (saga.getFuelCharge() != null) total = total.add(saga.getFuelCharge());
        if (saga.getLateFee() != null) total = total.add(saga.getLateFee());

        saga.setTotalCharges(total);

        log.info("[Saga] Calculated charges for booking {}: total={}",
                saga.getBookingId(), total);
    }

    private void executeCaptureDeposit(CheckoutSagaState saga) {
        if (saga.getTotalCharges() == null || saga.getTotalCharges().compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[Saga] No charges to capture for booking {}", saga.getBookingId());
            saga.setCapturedAmount(BigDecimal.ZERO);
            return;
        }

        Booking booking = loadBooking(saga.getBookingId());

        // TODO: Integrate with payment gateway to capture from held deposit
        // For now, simulate capture
        BigDecimal depositAmount = booking.getSecurityDeposit();
        BigDecimal captureAmount = saga.getTotalCharges().min(depositAmount);

        saga.setCapturedAmount(captureAmount);
        saga.setCaptureTransactionId("TXN-" + UUID.randomUUID().toString().substring(0, 8));

        log.info("[Saga] Captured {} from deposit for booking {} (txn: {})",
                captureAmount, saga.getBookingId(), saga.getCaptureTransactionId());
    }

    private void executeReleaseDeposit(CheckoutSagaState saga) {
        Booking booking = loadBooking(saga.getBookingId());

        BigDecimal depositAmount = booking.getSecurityDeposit();
        BigDecimal capturedAmount = saga.getCapturedAmount() != null ? saga.getCapturedAmount() : BigDecimal.ZERO;
        BigDecimal releaseAmount = depositAmount.subtract(capturedAmount);

        if (releaseAmount.compareTo(BigDecimal.ZERO) > 0) {
            // TODO: Integrate with payment gateway to release remaining deposit
            saga.setReleasedAmount(releaseAmount);
            saga.setReleaseTransactionId("REL-" + UUID.randomUUID().toString().substring(0, 8));

            log.info("[Saga] Released {} deposit for booking {} (txn: {})",
                    releaseAmount, saga.getBookingId(), saga.getReleaseTransactionId());
        } else {
            saga.setReleasedAmount(BigDecimal.ZERO);
            log.debug("[Saga] No deposit to release for booking {}", saga.getBookingId());
        }
    }

    private void executeCompleteBooking(CheckoutSagaState saga) {
        Booking booking = loadBooking(saga.getBookingId());

        booking.setStatus(BookingStatus.COMPLETED);
        bookingRepository.save(booking);

        log.info("[Saga] Booking {} marked as COMPLETED", saga.getBookingId());
    }

    // ========== COMPENSATION ==========

    @Transactional
    public void startCompensation(CheckoutSagaState saga) {
        log.warn("[Saga] Starting compensation for saga {} from step {}",
                saga.getSagaId(), saga.getFailedAtStep());

        saga.setStatus(SagaStatus.COMPENSATING);

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
            case COMPLETE_BOOKING -> compensateCompleteBooking(saga);
            default -> log.debug("[Saga] No compensation needed for step {}", step);
        }
    }

    private void compensateCaptureDeposit(CheckoutSagaState saga) {
        if (saga.getCaptureTransactionId() != null && saga.getCapturedAmount() != null
                && saga.getCapturedAmount().compareTo(BigDecimal.ZERO) > 0) {
            // TODO: Integrate with payment gateway to refund captured amount
            log.info("[Saga] Refunding captured amount {} for booking {} (original txn: {})",
                    saga.getCapturedAmount(), saga.getBookingId(), saga.getCaptureTransactionId());
        }
    }

    private void compensateCompleteBooking(CheckoutSagaState saga) {
        Booking booking = loadBooking(saga.getBookingId());
        booking.setStatus(BookingStatus.COMPLETED);  // Revert to previous status
        bookingRepository.save(booking);

        log.info("[Saga] Reverted booking {} status to COMPLETED", saga.getBookingId());
    }

    // ========== HELPER METHODS ==========

    private Booking loadBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
    }

    private int calculateAllowedKm(Booking booking) {
        // Calculate allowed km based on rental duration and daily limit
        long days = ChronoUnit.DAYS.between(
                booking.getStartTime().toLocalDate(),
                booking.getEndTime().toLocalDate()) + 1;

        int dailyLimit = 200;  // Default 200km per day
        // TODO: Load from car or booking config
        
        return (int) (days * dailyLimit);
    }

    private void notifySagaCompleted(CheckoutSagaState saga) {
        try {
            Booking booking = loadBooking(saga.getBookingId());

            // Notify host
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getCar().getOwner().getId())
                    .type(NotificationType.CHECKOUT_COMPLETE)
                    .message("Vraćanje vozila je završeno. Ukupni troškovi: " + saga.getTotalCharges() + " EUR")
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build());

            // Notify guest
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getRenter().getId())
                    .type(NotificationType.CHECKOUT_COMPLETE)
                    .message("Vaša rezervacija je završena. Depozit od " + saga.getReleasedAmount() + " EUR će biti vraćen.")
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

    /**
     * Get saga status for a booking.
     */
    @Transactional(readOnly = true)
    public Optional<CheckoutSagaState> getSagaStatus(Long bookingId) {
        return sagaRepository.findActiveSagaForBooking(bookingId);
    }
}
