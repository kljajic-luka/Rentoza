package org.example.rentoza.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationRecordRepository;
import org.example.rentoza.booking.cancellation.RefundStatus;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Payment Lifecycle Scheduler — Turo-standard payment automation.
 * 
 * <h2>Scheduled Jobs</h2>
 * <ol>
 *   <li><b>captureUpcomingPayments</b> — Capture authorized booking payments T-24h before trip start</li>
 *   <li><b>releaseDepositsAfterTrip</b> — Release security deposits T+48h after trip end (no claims)</li>
 *   <li><b>autoReleaseOverdueDeposits</b> — Safety net: force-release deposits held >7 days</li>
 *   <li><b>processPendingCancellationRefunds</b> — Execute refunds for cancelled bookings</li>
 * </ol>
 * 
 * <h2>Turo Standard Compliance</h2>
 * <ul>
 *   <li>Payment captured 24h before trip → guest committed</li>
 *   <li>Deposit released 48h after trip end → no-claim window</li>
 *   <li>Deposits auto-released after 7 days → safety net, never held forever</li>
 *   <li>Cancellation refunds processed immediately → guest sees money back</li>
 * </ul>
 */
@Service
@Slf4j
public class PaymentLifecycleScheduler {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    private final BookingRepository bookingRepository;
    private final BookingPaymentService paymentService;
    private final PaymentProvider paymentProvider;
    private final CancellationRecordRepository cancellationRecordRepository;
    private final DamageClaimRepository damageClaimRepository;
    private final NotificationService notificationService;

    // Metrics
    private final Counter captureSuccessCounter;
    private final Counter captureFailedCounter;
    private final Counter depositReleaseCounter;
    private final Counter overdueDepositReleaseCounter;
    private final Counter refundProcessedCounter;
    private final Counter refundFailedCounter;

    @Value("${app.payment.capture.hours-before-trip:24}")
    private int captureHoursBeforeTrip;

    @Value("${app.payment.deposit.release-hours-after-trip:48}")
    private int depositReleaseHoursAfterTrip;

    @Value("${app.payment.deposit.max-hold-days:7}")
    private int maxDepositHoldDays;

    public PaymentLifecycleScheduler(
            BookingRepository bookingRepository,
            BookingPaymentService paymentService,
            PaymentProvider paymentProvider,
            CancellationRecordRepository cancellationRecordRepository,
            DamageClaimRepository damageClaimRepository,
            NotificationService notificationService,
            MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.paymentService = paymentService;
        this.paymentProvider = paymentProvider;
        this.cancellationRecordRepository = cancellationRecordRepository;
        this.damageClaimRepository = damageClaimRepository;
        this.notificationService = notificationService;

        this.captureSuccessCounter = Counter.builder("payment.scheduler.capture.success")
                .description("Scheduled payment captures succeeded")
                .register(meterRegistry);
        this.captureFailedCounter = Counter.builder("payment.scheduler.capture.failed")
                .description("Scheduled payment captures failed")
                .register(meterRegistry);
        this.depositReleaseCounter = Counter.builder("payment.scheduler.deposit.released")
                .description("Deposits released by scheduler")
                .register(meterRegistry);
        this.overdueDepositReleaseCounter = Counter.builder("payment.scheduler.deposit.overdue_released")
                .description("Overdue deposits force-released by safety net")
                .register(meterRegistry);
        this.refundProcessedCounter = Counter.builder("payment.scheduler.refund.processed")
                .description("Cancellation refunds processed by scheduler")
                .register(meterRegistry);
        this.refundFailedCounter = Counter.builder("payment.scheduler.refund.failed")
                .description("Cancellation refund processing failures")
                .register(meterRegistry);
    }

    // ========== JOB 1: CAPTURE PAYMENTS 24H BEFORE TRIP ==========

    /**
     * Capture authorized payments for bookings starting within 24 hours.
     * 
     * <p><b>Turo Standard:</b> Payment is captured 24h before trip start.
     * If capture fails (card expired, insufficient funds), the booking is cancelled
     * and both parties are notified.
     * 
     * <p>Runs every hour to ensure timely capture.
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour
    @Transactional
    public void captureUpcomingPayments() {
        log.info("[PaymentScheduler] Running captureUpcomingPayments job");

        LocalDateTime captureWindow = LocalDateTime.now(SERBIA_ZONE).plusHours(captureHoursBeforeTrip);
        List<BookingStatus> eligibleStatuses = List.of(BookingStatus.ACTIVE, BookingStatus.APPROVED);

        List<Booking> bookingsToCapture = bookingRepository.findBookingsNeedingPaymentCapture(
                eligibleStatuses, captureWindow);

        if (bookingsToCapture.isEmpty()) {
            log.debug("[PaymentScheduler] No bookings need payment capture");
            return;
        }

        log.info("[PaymentScheduler] Found {} bookings needing payment capture", bookingsToCapture.size());

        for (Booking booking : bookingsToCapture) {
            try {
                PaymentResult result = paymentService.captureBookingPayment(booking.getId());

                if (result.isSuccess()) {
                    captureSuccessCounter.increment();
                    log.info("[PaymentScheduler] Payment captured for booking {}: {}",
                            booking.getId(), result.getTransactionId());

                    // Notify guest that payment has been charged
                    notificationService.createNotification(CreateNotificationRequestDTO.builder()
                            .recipientId(booking.getRenter().getId())
                            .type(NotificationType.PAYMENT_CAPTURED)
                            .message(String.format(
                                    "Plaćanje od %s RSD za rezervaciju #%d je uspešno naplaćeno. Vaš trip počinje uskoro!",
                                    booking.getTotalPrice(), booking.getId()))
                            .relatedEntityId(String.valueOf(booking.getId()))
                            .build());
                } else {
                    captureFailedCounter.increment();
                    log.error("[PaymentScheduler] Payment capture FAILED for booking {}: {} (code: {})",
                            booking.getId(), result.getErrorMessage(), result.getErrorCode());

                    // Cancel the booking - payment failed
                    handleCaptureFailure(booking, result);
                }
            } catch (Exception e) {
                captureFailedCounter.increment();
                log.error("[PaymentScheduler] Exception during payment capture for booking {}: {}",
                        booking.getId(), e.getMessage(), e);

                // P1 FIX: Treat unhandled exceptions the same as structured capture failure —
                // cancel booking and notify both parties (stale auth, network error, etc.)
                PaymentResult syntheticFailure = PaymentResult.builder()
                        .success(false)
                        .errorCode("CAPTURE_EXCEPTION")
                        .errorMessage(e.getMessage())
                        .build();
                handleCaptureFailure(booking, syntheticFailure);
            }
        }
    }

    /**
     * Handle payment capture failure — cancel booking and notify both parties.
     */
    private void handleCaptureFailure(Booking booking, PaymentResult result) {
        try {
            // Cancel the booking
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelledAt(LocalDateTime.now(SERBIA_ZONE));
            booking.setPaymentStatus("CAPTURE_FAILED");
            bookingRepository.save(booking);

            // Release any remaining holds
            try {
                paymentService.releaseBookingPayment(booking.getId());
            } catch (Exception e) {
                log.warn("[PaymentScheduler] Failed to release auth for cancelled booking {}: {}", 
                    booking.getId(), e.getMessage());
            }

            // Release deposit hold
            String depositAuthId = booking.getDepositAuthorizationId();
            if (depositAuthId != null && !depositAuthId.isBlank()) {
                try {
                    paymentService.releaseDeposit(booking.getId(), depositAuthId);
                } catch (Exception e) {
                    log.warn("[PaymentScheduler] Failed to release deposit for cancelled booking {}: {}",
                            booking.getId(), e.getMessage());
                }
            }

            // Notify guest
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getRenter().getId())
                    .type(NotificationType.BOOKING_CANCELLED)
                    .message(String.format(
                            "Vaša rezervacija #%d je otkazana jer naplaćivanje nije uspelo: %s. " +
                            "Molimo proverite vaše podatke o plaćanju.",
                            booking.getId(), result.getErrorMessage()))
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build());

            // Notify host
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getCar().getOwner().getId())
                    .type(NotificationType.BOOKING_CANCELLED)
                    .message(String.format(
                            "Rezervacija #%d je automatski otkazana jer naplaćivanje gostu nije uspelo.",
                            booking.getId()))
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build());

            log.info("[PaymentScheduler] Booking {} cancelled due to capture failure", booking.getId());

        } catch (Exception e) {
            log.error("[PaymentScheduler] Failed to handle capture failure for booking {}: {}",
                    booking.getId(), e.getMessage(), e);
        }
    }

    // ========== JOB 2: RELEASE DEPOSITS 48H AFTER TRIP ==========

    /**
     * Release security deposits for completed trips with no pending damage claims.
     * 
     * <p><b>Turo Standard:</b> Deposit is released 48h after trip ends if no damage claims.
     * This window gives the host time to file a damage claim after the trip.
     * 
     * <p>Runs every 30 minutes.
     */
    @Scheduled(cron = "0 */30 * * * *") // Every 30 minutes
    @Transactional
    public void releaseDepositsAfterTrip() {
        log.info("[PaymentScheduler] Running releaseDepositsAfterTrip job");

        Instant releaseAfter = Instant.now().minus(depositReleaseHoursAfterTrip, ChronoUnit.HOURS);
        List<Booking> eligibleBookings = bookingRepository.findBookingsEligibleForDepositRelease(
                BookingStatus.COMPLETED, releaseAfter);

        if (eligibleBookings.isEmpty()) {
            log.debug("[PaymentScheduler] No deposits eligible for release");
            return;
        }

        log.info("[PaymentScheduler] Found {} deposits eligible for release", eligibleBookings.size());

        for (Booking booking : eligibleBookings) {
            try {
                // Double-check for pending damage claims (defense in depth)
                if (damageClaimRepository.hasClaimsBlockingDepositRelease(booking.getId())) {
                    log.info("[PaymentScheduler] Deposit release blocked for booking {} - pending damage claims",
                            booking.getId());
                    continue;
                }

                String depositAuthId = booking.getDepositAuthorizationId();
                if (depositAuthId == null || depositAuthId.isBlank()) {
                    log.warn("[PaymentScheduler] No deposit auth ID for booking {} - marking as released",
                            booking.getId());
                    booking.setSecurityDepositReleased(true);
                    booking.setSecurityDepositResolvedAt(Instant.now());
                    bookingRepository.save(booking);
                    continue;
                }

                PaymentResult result = paymentService.releaseDeposit(booking.getId(), depositAuthId);

                if (result.isSuccess()) {
                    booking.setSecurityDepositReleased(true);
                    booking.setSecurityDepositResolvedAt(Instant.now());
                    bookingRepository.save(booking);
                    depositReleaseCounter.increment();

                    // Notify guest
                    notificationService.createNotification(CreateNotificationRequestDTO.builder()
                            .recipientId(booking.getRenter().getId())
                            .type(NotificationType.DEPOSIT_RELEASED)
                            .message(String.format(
                                    "Vaš depozit od %s RSD za rezervaciju #%d je vraćen.",
                                    booking.getSecurityDeposit() != null ? booking.getSecurityDeposit() : "30,000",
                                    booking.getId()))
                            .relatedEntityId(String.valueOf(booking.getId()))
                            .build());

                    log.info("[PaymentScheduler] Deposit released for booking {}", booking.getId());
                } else {
                    log.warn("[PaymentScheduler] Deposit release failed for booking {}: {}",
                            booking.getId(), result.getErrorMessage());
                }
            } catch (IllegalStateException e) {
                // Expected: pending damage claims block release
                log.info("[PaymentScheduler] Deposit release blocked for booking {}: {}", 
                    booking.getId(), e.getMessage());
            } catch (Exception e) {
                log.error("[PaymentScheduler] Exception during deposit release for booking {}: {}",
                        booking.getId(), e.getMessage(), e);
            }
        }
    }

    // ========== JOB 3: SAFETY NET — AUTO-RELEASE OVERDUE DEPOSITS ==========

    /**
     * Safety net: force-release deposits held longer than 7 days.
     * 
     * <p><b>Critical:</b> Deposits should NEVER be held indefinitely.
     * Even if there are unresolved damage claims, the deposit must be released
     * after the maximum hold period. Any remaining damage charges are pursued
     * through separate collections.
     * 
     * <p>Runs every 6 hours.
     */
    @Scheduled(cron = "0 0 */6 * * *") // Every 6 hours
    @Transactional
    public void autoReleaseOverdueDeposits() {
        log.info("[PaymentScheduler] Running autoReleaseOverdueDeposits safety net");

        Instant maxHoldDeadline = Instant.now().minus(maxDepositHoldDays, ChronoUnit.DAYS);
        List<BookingStatus> terminalStatuses = List.of(
                BookingStatus.COMPLETED, BookingStatus.CANCELLED);

        List<Booking> overdueBookings = bookingRepository.findBookingsWithOverdueDepositHold(
                maxHoldDeadline, terminalStatuses);

        if (overdueBookings.isEmpty()) {
            log.debug("[PaymentScheduler] No overdue deposit holds found");
            return;
        }

        log.warn("[PaymentScheduler] SAFETY NET: Found {} deposits held beyond {} day limit",
                overdueBookings.size(), maxDepositHoldDays);

        for (Booking booking : overdueBookings) {
            try {
                String depositAuthId = booking.getDepositAuthorizationId();
                boolean gatewayReleased = false;
                if (depositAuthId != null && !depositAuthId.isBlank()) {
                    try {
                        PaymentResult releaseResult = paymentService.releaseDeposit(booking.getId(), depositAuthId);
                        gatewayReleased = releaseResult.isSuccess();
                    } catch (IllegalStateException e) {
                        // If blocked by claims, force-release via provider directly (safety net override)
                        log.warn("[PaymentScheduler] SAFETY NET: Force-releasing deposit for booking {} " +
                                "despite pending claims (held {} days)", booking.getId(), maxDepositHoldDays);
                        try {
                            PaymentResult forceResult = paymentProvider.releaseAuthorization(depositAuthId);
                            gatewayReleased = forceResult.isSuccess();
                            if (!forceResult.isSuccess()) {
                                log.error("[PaymentScheduler] SAFETY NET: Gateway force-release FAILED for booking {}: {}",
                                        booking.getId(), forceResult.getErrorMessage());
                            }
                        } catch (Exception ex) {
                            log.error("[PaymentScheduler] SAFETY NET: Gateway force-release exception for booking {}: {}",
                                    booking.getId(), ex.getMessage());
                        }
                    }
                } else {
                    // No auth ID — deposit may have already been released or never created
                    gatewayReleased = true;
                }

                if (!gatewayReleased) {
                    log.error("[PaymentScheduler] SAFETY NET: Skipping DB update for booking {} — gateway release not confirmed",
                            booking.getId());
                    continue;
                }

                booking.setSecurityDepositReleased(true);
                booking.setSecurityDepositResolvedAt(Instant.now());
                booking.setSecurityDepositHoldReason("AUTO_RELEASED_SAFETY_NET");
                bookingRepository.save(booking);
                overdueDepositReleaseCounter.increment();

                // Alert admin about forced release
                log.warn("[PaymentScheduler] ADMIN ALERT: Deposit auto-released for booking {} " +
                        "(held {} days, trip ended: {})", 
                        booking.getId(), maxDepositHoldDays, booking.getTripEndedAt());

                // Notify guest
                notificationService.createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(booking.getRenter().getId())
                        .type(NotificationType.DEPOSIT_RELEASED)
                        .message(String.format(
                                "Vaš depozit za rezervaciju #%d je automatski vraćen.",
                                booking.getId()))
                        .relatedEntityId(String.valueOf(booking.getId()))
                        .build());

            } catch (Exception e) {
                log.error("[PaymentScheduler] Failed to auto-release deposit for booking {}: {}",
                        booking.getId(), e.getMessage(), e);
            }
        }
    }

    // ========== JOB 4: PROCESS PENDING CANCELLATION REFUNDS ==========

    /**
     * Process pending refunds from cancelled bookings.
     * 
     * <p><b>P0 FIX:</b> Previously, cancellation policy computed refund amounts but
     * never executed them. This job picks up PENDING refunds and executes settlement.
     * 
     * <p>Runs every 15 minutes.
     */
    @Scheduled(cron = "0 */15 * * * *") // Every 15 minutes
    @Transactional
    public void processPendingCancellationRefunds() {
        log.info("[PaymentScheduler] Running processPendingCancellationRefunds job");

        List<CancellationRecord> pendingRefunds = cancellationRecordRepository
                .findByRefundStatus(RefundStatus.PENDING);

        if (pendingRefunds.isEmpty()) {
            log.debug("[PaymentScheduler] No pending cancellation refunds");
            return;
        }

        log.info("[PaymentScheduler] Found {} pending cancellation refunds to process", pendingRefunds.size());

        for (CancellationRecord record : pendingRefunds) {
            try {
                Long bookingId = record.getBooking().getId();
                BigDecimal refundAmount = record.getRefundToGuest();

                // Mark as processing
                record.setRefundStatus(RefundStatus.PROCESSING);
                cancellationRecordRepository.save(record);

                PaymentResult result = paymentService.processCancellationSettlement(
                        bookingId, refundAmount,
                        "Cancellation refund: " + record.getAppliedRule());

                if (result.isSuccess()) {
                    record.setRefundStatus(RefundStatus.COMPLETED);
                    cancellationRecordRepository.save(record);
                    refundProcessedCounter.increment();

                    log.info("[PaymentScheduler] Cancellation refund processed for booking {}: {} RSD",
                            bookingId, refundAmount);

                    // Notify guest
                    Booking booking = record.getBooking();
                    if (booking.getRenter() != null) {
                        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                                .recipientId(booking.getRenter().getId())
                                .type(NotificationType.REFUND_PROCESSED)
                                .message(String.format(
                                        "Povraćaj od %s RSD za otkazanu rezervaciju #%d je obrađen.",
                                        refundAmount, bookingId))
                                .relatedEntityId(String.valueOf(bookingId))
                                .build());
                    }
                } else {
                    record.setRefundStatus(RefundStatus.FAILED);
                    cancellationRecordRepository.save(record);
                    refundFailedCounter.increment();

                    log.error("[PaymentScheduler] Cancellation refund FAILED for booking {}: {}",
                            bookingId, result.getErrorMessage());
                }

            } catch (Exception e) {
                record.setRefundStatus(RefundStatus.FAILED);
                cancellationRecordRepository.save(record);
                refundFailedCounter.increment();

                log.error("[PaymentScheduler] Exception processing cancellation refund for record {}: {}",
                        record.getId(), e.getMessage(), e);
            }
        }
    }
}
