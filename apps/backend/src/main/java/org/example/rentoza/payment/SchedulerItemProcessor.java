package org.example.rentoza.payment;

import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationRecordRepository;
import org.example.rentoza.booking.cancellation.RefundStatus;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.example.rentoza.payment.PaymentProvider.ProviderOutcome;
import org.example.rentoza.payment.PaymentProvider.ProviderResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.example.rentoza.booking.dispute.DamageClaimRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Per-item processing methods for the payment lifecycle scheduler.
 *
 * <h2>Why a separate bean? (P1-1)</h2>
 * Spring's AOP proxy intercepts {@code @Transactional} annotations only on
 * external method calls. When a method in class A calls another method in the
 * same class A, the call goes directly to {@code this}, bypassing the proxy —
 * so {@code @Transactional(propagation = REQUIRES_NEW)} has no effect.
 *
 * <p>By moving the per-item processing methods here, every call from
 * {@link PaymentLifecycleScheduler} goes through Spring's proxy, giving each
 * item a genuinely isolated transaction. A single item failure rolls back only
 * its own transaction and never affects other items in the same batch.
 */
@Service
@Slf4j
public class SchedulerItemProcessor {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    private final BookingRepository bookingRepository;
    private final BookingPaymentService paymentService;
    private final PaymentProvider paymentProvider;
    private final CancellationRecordRepository cancellationRecordRepository;
    private final DamageClaimRepository damageClaimRepository;
    private final NotificationService notificationService;
    private final PayoutLedgerRepository payoutLedgerRepository;

    // H-15: Dedicated alerting counters for MANUAL_REVIEW escalations
    private final io.micrometer.core.instrument.Counter captureManualReviewCounter;
    private final io.micrometer.core.instrument.Counter refundManualReviewCounter;
    private final io.micrometer.core.instrument.Counter payoutManualReviewCounter;
    private final io.micrometer.core.instrument.Counter depositExpiryCounter;

    @Value("${app.payment.refund.retry-backoff-minutes:60}")
    private int refundRetryBackoffMinutes;

    public SchedulerItemProcessor(
            BookingRepository bookingRepository,
            BookingPaymentService paymentService,
            PaymentProvider paymentProvider,
            CancellationRecordRepository cancellationRecordRepository,
            DamageClaimRepository damageClaimRepository,
            NotificationService notificationService,
            PayoutLedgerRepository payoutLedgerRepository,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.paymentService = paymentService;
        this.paymentProvider = paymentProvider;
        this.cancellationRecordRepository = cancellationRecordRepository;
        this.damageClaimRepository = damageClaimRepository;
        this.notificationService = notificationService;
        this.payoutLedgerRepository = payoutLedgerRepository;

        // H-15: Alert-grade counters — configure alerting rules to fire on increment > 0
        // Runbook: https://wiki.internal/runbooks/manual-review-escalation
        this.captureManualReviewCounter = io.micrometer.core.instrument.Counter.builder(
                "payment.alert.capture.manual_review")
                .description("Capture escalations requiring manual review")
                .tag("severity", "high")
                .register(meterRegistry);
        this.refundManualReviewCounter = io.micrometer.core.instrument.Counter.builder(
                "payment.alert.refund.manual_review")
                .description("Refund escalations requiring manual review")
                .tag("severity", "high")
                .register(meterRegistry);
        this.payoutManualReviewCounter = io.micrometer.core.instrument.Counter.builder(
                "payment.alert.payout.manual_review")
                .description("Payout escalations requiring manual review")
                .tag("severity", "high")
                .register(meterRegistry);
        this.depositExpiryCounter = io.micrometer.core.instrument.Counter.builder(
                "payment.alert.deposit.auth_expiry")
                .description("Deposit authorization expiry events")
                .tag("severity", "high")
                .register(meterRegistry);
    }

    // ── Capture ──────────────────────────────────────────────────────────────

    /**
     * Capture payment for one booking in an isolated transaction.
     *
     * <p>P0-2: Only cancels the booking on confirmed terminal failures.
     * IN_PROGRESS outcomes are silently skipped (another thread is handling it).
     * Retryable failures leave the booking in CAPTURE_FAILED state so the next
     * scheduler run can retry up to the configured max attempts.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processCaptureSafely(Booking booking) {
        PaymentResult result;
        try {
            result = paymentService.captureBookingPayment(booking.getId());
        } catch (DataIntegrityViolationException dive) {
            // P0-2: A unique-key collision on the idempotency_key column means a concurrent
            // capture (typically the handshake path) already inserted the row and is
            // committing. This is idempotent — do NOT cancel the booking.
            log.warn("[SchedulerProcessor] DataIntegrityViolation during capture for booking {} — "
                    + "concurrent capture already in progress; skipping (booking NOT cancelled)",
                    booking.getId());
            return;
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Exception during payment capture for booking {}: {}",
                    booking.getId(), e.getMessage(), e);
            PaymentResult syntheticFailure = PaymentResult.builder()
                    .success(false).errorCode("CAPTURE_EXCEPTION").errorMessage(e.getMessage()).build();
            handleCaptureFailure(booking, syntheticFailure);
            return;
        }

        if (result.isSuccess()) {
            log.info("[SchedulerProcessor] Payment captured for booking {}: {}",
                    booking.getId(), result.getTransactionId());
            try {
                notificationService.createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(booking.getRenter().getId())
                        .type(NotificationType.PAYMENT_CAPTURED)
                        .message(String.format(
                                "Plaćanje od %s RSD za rezervaciju #%d je uspešno naplaćeno. Vaš trip počinje uskoro!",
                                booking.getTotalPrice(), booking.getId()))
                        .relatedEntityId(String.valueOf(booking.getId()))
                        .build());
            } catch (Exception notifEx) {
                log.warn("[SchedulerProcessor] Capture notification failed for booking {} — capture remains COMMITTED: {}",
                        booking.getId(), notifEx.getMessage());
            }
        } else {
            log.error("[SchedulerProcessor] Payment capture FAILED for booking {}: {} (code: {})",
                    booking.getId(), result.getErrorMessage(), result.getErrorCode());

            // P0-2: IN_PROGRESS means another concurrent execution is handling this capture.
            // Never cancel the booking just because a concurrent thread won the race.
            if ("IN_PROGRESS".equals(result.getErrorCode())) {
                log.info("[SchedulerProcessor] Capture in-progress for booking {} — skipping cancellation",
                        booking.getId());
                return;
            }

            // Refresh to see the latest captureAttempts value written by captureBookingPayment.
            Booking refreshed = bookingRepository.findById(booking.getId()).orElse(booking);
            boolean attemptsExhausted = refreshed.getCaptureAttempts() >= 3;
            boolean isKnownTerminal   = isTerminalCaptureErrorCode(result.getErrorCode());

            if (isKnownTerminal) {
                handleCaptureFailure(refreshed, result);
            } else if (attemptsExhausted) {
                escalateToManualReview(refreshed, result);
            } else {
                log.warn("[SchedulerProcessor] Retryable capture failure for booking {} (attempt {}/3) — retrying next run",
                        refreshed.getId(), refreshed.getCaptureAttempts());
            }
        }
    }

    private void handleCaptureFailure(Booking booking, PaymentResult result) {
        try {
            // P0-2: Re-read the booking to guard against a concurrent capture that may have
            // advanced the booking to IN_TRIP or CAPTURED between scheduler dispatch and here.
            // If the concurrent path already captured successfully, do NOT cancel.
            Booking fresh = bookingRepository.findById(booking.getId()).orElse(booking);
            if (fresh.getStatus() == BookingStatus.IN_TRIP
                    || fresh.getChargeLifecycleStatus() == ChargeLifecycleStatus.CAPTURED) {
                log.info("[SchedulerProcessor] Aborting cancellation for booking {} — "
                        + "concurrent capture already succeeded ({}/{})",
                        fresh.getId(), fresh.getStatus(), fresh.getChargeLifecycleStatus());
                return;
            }

            // R4-FIX: Mutate and save the re-read 'fresh' entity — not the stale method
            // parameter 'booking' — to avoid OptimisticLockException or overwriting
            // concurrent changes committed between scheduler dispatch and this point.
            // R6-FIX: Also set chargeLifecycleStatus to CAPTURE_FAILED alongside the
            // legacy paymentStatus to keep both fields synchronized.
            fresh.setStatus(BookingStatus.CANCELLED);
            fresh.setCancelledAt(LocalDateTime.now(SERBIA_ZONE));
            fresh.setPaymentStatus("CAPTURE_FAILED");
            fresh.setChargeLifecycleStatus(ChargeLifecycleStatus.CAPTURE_FAILED);
            bookingRepository.save(fresh);

            try { paymentService.releaseBookingPayment(fresh.getId()); }
            catch (Exception e) { log.warn("[SchedulerProcessor] Failed to release auth for {}: {}", fresh.getId(), e.getMessage()); }

            String depositAuthId = fresh.getDepositAuthorizationId();
            if (depositAuthId != null && !depositAuthId.isBlank()) {
                try { paymentService.releaseDeposit(fresh.getId(), depositAuthId); }
                catch (Exception e) { log.warn("[SchedulerProcessor] Failed to release deposit for {}: {}", fresh.getId(), e.getMessage()); }
            }

            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(fresh.getRenter().getId())
                    .type(NotificationType.BOOKING_CANCELLED)
                    .message(String.format("Vaša rezervacija #%d je otkazana jer naplaćivanje nije uspelo: %s.",
                            fresh.getId(), result.getErrorMessage()))
                    .relatedEntityId(String.valueOf(fresh.getId()))
                    .build());

            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(fresh.getCar().getOwner().getId())
                    .type(NotificationType.BOOKING_CANCELLED)
                    .message(String.format("Rezervacija #%d je automatski otkazana jer naplaćivanje gostu nije uspelo.",
                            fresh.getId()))
                    .relatedEntityId(String.valueOf(fresh.getId()))
                    .build());
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Failed to handle capture failure for {}: {}", booking.getId(), e.getMessage(), e);
        }
    }

    /**
     * Escalate a booking to MANUAL_REVIEW after retryable capture failures exhaust all attempts.
     * Unlike {@link #handleCaptureFailure}, this does NOT cancel the booking or release holds —
     * an operator must review and decide the action (retry with updated card, cancel, etc.).
     */
    private void escalateToManualReview(Booking booking, PaymentResult result) {
        try {
            Booking fresh = bookingRepository.findById(booking.getId()).orElse(booking);
            if (fresh.getChargeLifecycleStatus() == ChargeLifecycleStatus.CAPTURED) {
                log.info("[SchedulerProcessor] Aborting MANUAL_REVIEW for booking {} — already CAPTURED",
                        fresh.getId());
                return;
            }
            ChargeLifecycleStatus current = fresh.getChargeLifecycleStatus();
            if (current != null && !current.canTransitionTo(ChargeLifecycleStatus.MANUAL_REVIEW)) {
                log.warn("[SchedulerProcessor] Invalid charge lifecycle transition {} → MANUAL_REVIEW for booking {}",
                        current, fresh.getId());
            }
            fresh.setChargeLifecycleStatus(ChargeLifecycleStatus.MANUAL_REVIEW);
            fresh.setPaymentStatus("MANUAL_REVIEW");
            bookingRepository.save(fresh);

            // H-15: Increment alerting metric and emit structured alert log
            captureManualReviewCounter.increment();
            log.error("[ALERT][MANUAL_REVIEW] Booking {} ESCALATED to MANUAL_REVIEW — {} capture attempts exhausted. "
                    + "Last error: {} ({}). "
                    + "Runbook: https://wiki.internal/runbooks/capture-manual-review",
                    fresh.getId(), fresh.getCaptureAttempts(),
                    result.getErrorMessage(), result.getErrorCode());

            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(fresh.getRenter().getId())
                    .type(NotificationType.PAYMENT_FAILED)
                    .message(String.format("Naplaćivanje za rezervaciju #%d zahteva manuelnu proveru. "
                            + "Kontaktiraćemo vas uskoro.", fresh.getId()))
                    .relatedEntityId(String.valueOf(fresh.getId()))
                    .build());
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Failed to escalate booking {} to MANUAL_REVIEW: {}",
                    booking.getId(), e.getMessage(), e);
        }
    }

    /**
     * Known terminal error codes for capture operations.
     * These represent hard provider declines that should not be retried.
     */
    private static boolean isTerminalCaptureErrorCode(String code) {
        if (code == null) return false;
        return switch (code) {
            case "CARD_DECLINED", "INSUFFICIENT_FUNDS", "EXPIRED_CARD", "FRAUD_SUSPECTED",
                 "UNKNOWN_AUTHORIZATION", "AUTHORIZATION_EXPIRED", "AUTH_RELEASED",
                 "AMOUNT_EXCEEDS_AUTH", "INVALID_AMOUNT", "CAPTURE_EXCEPTION" -> true;
            default -> false;
        };
    }

    // ── Deposit release ───────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseDepositSafely(Booking booking) {
        try {
            if (damageClaimRepository.hasClaimsBlockingDepositRelease(booking.getId())) {
                log.info("[SchedulerProcessor] Deposit release blocked for booking {} — pending damage claims",
                        booking.getId());
                return;
            }

            String depositAuthId = booking.getDepositAuthorizationId();
            if (depositAuthId == null || depositAuthId.isBlank()) {
                log.warn("[SchedulerProcessor] No deposit auth ID for booking {} — marking released", booking.getId());
                booking.setSecurityDepositReleased(true);
                booking.setSecurityDepositResolvedAt(Instant.now());
                bookingRepository.save(booking);
                return;
            }

            PaymentResult result = paymentService.releaseDeposit(booking.getId(), depositAuthId);

            if (result.isSuccess()) {
                booking.setSecurityDepositReleased(true);
                booking.setSecurityDepositResolvedAt(Instant.now());
                bookingRepository.save(booking);

                notificationService.createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(booking.getRenter().getId())
                        .type(NotificationType.DEPOSIT_RELEASED)
                        .message(String.format("Vaš depozit za rezervaciju #%d je vraćen.", booking.getId()))
                        .relatedEntityId(String.valueOf(booking.getId()))
                        .build());

                log.info("[SchedulerProcessor] Deposit released for booking {}", booking.getId());
            } else {
                log.warn("[SchedulerProcessor] Deposit release failed for booking {}: {}",
                        booking.getId(), result.getErrorMessage());
            }
        } catch (IllegalStateException e) {
            log.info("[SchedulerProcessor] Deposit release blocked for booking {}: {}", booking.getId(), e.getMessage());
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Exception during deposit release for booking {}: {}",
                    booking.getId(), e.getMessage(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void forceReleaseDepositSafely(Booking booking) {
        try {
            String depositAuthId = booking.getDepositAuthorizationId();
            boolean gatewayReleased = false;

            if (depositAuthId != null && !depositAuthId.isBlank()) {
                try {
                    PaymentResult r = paymentService.releaseDeposit(booking.getId(), depositAuthId);
                    gatewayReleased = r.isSuccess();
                } catch (IllegalStateException e) {
                    log.warn("[SchedulerProcessor] SAFETY NET: Force-releasing deposit for {} despite pending claims",
                            booking.getId());
                    try {
                        ProviderResult forceResult = paymentProvider.releaseAuthorization(
                                depositAuthId,
                                PaymentIdempotencyKey.forDepositRelease(booking.getId()) + "_force");
                        gatewayReleased = forceResult.getOutcome() == ProviderOutcome.SUCCESS;
                        if (!gatewayReleased) {
                            log.error("[SchedulerProcessor] SAFETY NET: Gateway force-release FAILED for {}: {}",
                                    booking.getId(), forceResult.getErrorMessage());
                        }
                    } catch (Exception ex) {
                        log.error("[SchedulerProcessor] SAFETY NET: Gateway exception for {}: {}",
                                booking.getId(), ex.getMessage());
                    }
                }
            } else {
                gatewayReleased = true;
            }

            if (!gatewayReleased) {
                log.error("[SchedulerProcessor] SAFETY NET: Skipping DB update for {} — gateway not confirmed",
                        booking.getId());
                return;
            }

            booking.setSecurityDepositReleased(true);
            booking.setSecurityDepositResolvedAt(Instant.now());
            booking.setSecurityDepositHoldReason("AUTO_RELEASED_SAFETY_NET");
            bookingRepository.save(booking);

            log.warn("[SchedulerProcessor] ADMIN ALERT: Deposit auto-released for booking {}", booking.getId());

            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getRenter().getId())
                    .type(NotificationType.DEPOSIT_RELEASED)
                    .message(String.format("Vaš depozit za rezervaciju #%d je automatski vraćen.", booking.getId()))
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build());
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Failed to auto-release deposit for {}: {}",
                    booking.getId(), e.getMessage(), e);
        }
    }

    // ── Refund ────────────────────────────────────────────────────────────────

    /**
     * Process a cancellation refund in an isolated transaction.
     * Handles PENDING + FAILED (retry) + stale PROCESSING records.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processRefundSafely(CancellationRecord record) {
        Long bookingId = record.getBooking().getId();
        BigDecimal refundAmount = record.getRefundToGuest();
        int attempt = record.getRetryCount() + 1;

        record.setRefundStatus(RefundStatus.PROCESSING);
        record.setLastRetryAt(Instant.now());
        cancellationRecordRepository.save(record);

        PaymentResult result;
        try {
            result = paymentService.processCancellationSettlement(
                    bookingId, refundAmount,
                    "Cancellation refund: " + record.getAppliedRule());
        } catch (Exception e) {
            record.setLastError(e.getMessage());
            record.setRetryCount(attempt);
            boolean exhausted = attempt >= record.getMaxRetries();
            if (exhausted) {
                record.setRefundStatus(RefundStatus.MANUAL_REVIEW);
                record.setNextRetryAt(null);
                refundManualReviewCounter.increment();
                log.error("[ALERT][MANUAL_REVIEW] Refund ESCALATED to MANUAL_REVIEW for booking {} after {} attempts: {}. "
                        + "Runbook: https://wiki.internal/runbooks/refund-manual-review",
                        bookingId, attempt, e.getMessage());
            } else {
                record.setRefundStatus(RefundStatus.FAILED);
                record.setNextRetryAt(Instant.now().plusSeconds(refundRetryBackoffMinutes * 60L));
                log.error("[SchedulerProcessor] Exception on refund for record {}: {}", record.getId(), e.getMessage(), e);
            }
            cancellationRecordRepository.save(record);
            return;
        }

        if (result.isSuccess()) {
            record.getBooking().setStatus(BookingStatus.CANCELLED);
            bookingRepository.save(record.getBooking());
            record.setRefundStatus(RefundStatus.COMPLETED);
            record.setRetryCount(attempt);
            cancellationRecordRepository.save(record);
            log.info("[SchedulerProcessor] Cancellation refund processed for booking {}: {} RSD (attempt {})",
                    bookingId, refundAmount, attempt);

            try {
                Booking booking = record.getBooking();
                if (booking.getRenter() != null) {
                    notificationService.createNotification(CreateNotificationRequestDTO.builder()
                            .recipientId(booking.getRenter().getId())
                            .type(NotificationType.REFUND_PROCESSED)
                            .message(String.format("Povraćaj od %s RSD za otkazanu rezervaciju #%d je obrađen.",
                                    refundAmount, bookingId))
                            .relatedEntityId(String.valueOf(bookingId))
                            .build());
                }
            } catch (Exception notifEx) {
                log.warn("[SchedulerProcessor] Refund notification failed for booking {} — refund remains COMPLETED: {}",
                        bookingId, notifEx.getMessage());
            }
        } else {
            record.setRetryCount(attempt);
            record.setLastError(result.getErrorMessage());

            boolean exhausted = attempt >= record.getMaxRetries();
            if (exhausted) {
                record.setRefundStatus(RefundStatus.MANUAL_REVIEW);
                record.setNextRetryAt(null);
                refundManualReviewCounter.increment();
                log.error("[ALERT][MANUAL_REVIEW] Refund ESCALATED to MANUAL_REVIEW for booking {} after {} attempts: {}. "
                        + "Runbook: https://wiki.internal/runbooks/refund-manual-review",
                        bookingId, attempt, result.getErrorMessage());
            } else {
                record.setRefundStatus(RefundStatus.FAILED);
                record.setNextRetryAt(Instant.now().plusSeconds(refundRetryBackoffMinutes * 60L));
                log.warn("[SchedulerProcessor] Refund failed for booking {} (attempt {}/{}): {} — retrying at backoff",
                        bookingId, attempt, record.getMaxRetries(), result.getErrorMessage());
            }
            cancellationRecordRepository.save(record);
        }
    }

    // ── Payout scheduling ─────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void schedulePayoutSafely(Booking booking) {
        try {
            paymentService.scheduleHostPayout(booking);
            log.info("[SchedulerProcessor] Payout scheduled for booking {}", booking.getId());
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Failed to schedule payout for booking {}: {}",
                    booking.getId(), e.getMessage(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPayoutEligibleSafely(PayoutLedger ledger) {
        try {
            ledger.setStatus(PayoutLifecycleStatus.ELIGIBLE);
            // P1-FIX: Clear currentAttemptKey when requeuing to ELIGIBLE.
            // The stale PROCESSING record had no providerReference, meaning the
            // provider call never returned successfully. The old key has no cached
            // result to replay, so the next attempt must start fresh with a new key.
            // Without this, executeHostPayout would enter the crash-recovery path
            // (reusing the stale key) and skip setting status to PROCESSING,
            // leaving the payout in ELIGIBLE where webhooks can't finalize it.
            ledger.setCurrentAttemptKey(null);
            payoutLedgerRepository.save(ledger);
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Failed to mark payout eligible for ledger {}: {}",
                    ledger.getId(), e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executePayoutSafely(PayoutLedger ledger) {
        try {
            PaymentResult result = paymentService.executeHostPayout(ledger.getId());
            if (result.isSuccess()) {
                log.info("[SchedulerProcessor] Payout executed for booking {}: {} RSD",
                        ledger.getBookingId(), ledger.getHostPayoutAmount());
            } else {
                log.warn("[SchedulerProcessor] Payout failed for booking {}: {}",
                        ledger.getBookingId(), result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Exception executing payout for ledger {}: {}",
                    ledger.getId(), e.getMessage(), e);
        }
    }

    /**
     * P1-FIX: Escalate async payouts stuck waiting for a webhook beyond the timeout.
     * These payouts have a provider reference (bank accepted the transfer) but the
     * PAYOUT.COMPLETED webhook never arrived. They must NOT be requeued to ELIGIBLE
     * (would cause a duplicate bank transfer). Instead, escalate to MANUAL_REVIEW
     * for operator investigation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void escalateStaleAsyncPayoutSafely(PayoutLedger ledger) {
        try {
            ledger.setStatus(PayoutLifecycleStatus.MANUAL_REVIEW);
            ledger.setCurrentAttemptKey(null);
            ledger.setLastError("Async payout accepted by provider (ref: " + ledger.getProviderReference()
                    + ") but PAYOUT.COMPLETED webhook never arrived within timeout");
            payoutLedgerRepository.save(ledger);
            // P2-FIX: Increment alerting counter so Prometheus/Grafana fires an alert
            payoutManualReviewCounter.increment();
            log.error("[ALERT][MANUAL_REVIEW] Async payout for booking {} ESCALATED — provider accepted (ref {}) "
                    + "but no webhook confirmation received. "
                    + "Runbook: https://wiki.internal/runbooks/payout-webhook-missing",
                    ledger.getBookingId(), ledger.getProviderReference());
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Failed to escalate stale async payout for ledger {}: {}",
                    ledger.getId(), e.getMessage(), e);
        }
    }

    /**
     * P3-FIX: Escalate FAILED payouts that have exhausted all retry attempts.
     * Unlike {@link #escalateStaleAsyncPayoutSafely}, this is for payouts that
     * actively failed (via webhook PAYOUT.FAILED or provider error) and used up
     * their retry budget — not for async payouts waiting on a missing webhook.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void escalateExhaustedPayoutSafely(PayoutLedger ledger) {
        try {
            ledger.setStatus(PayoutLifecycleStatus.MANUAL_REVIEW);
            ledger.setCurrentAttemptKey(null);
            ledger.setLastError("Payout FAILED after " + ledger.getAttemptCount() + "/" + ledger.getMaxAttempts()
                    + " attempts exhausted. Last error: " + (ledger.getLastError() != null ? ledger.getLastError() : "unknown"));
            payoutLedgerRepository.save(ledger);
            payoutManualReviewCounter.increment();
            log.error("[ALERT][MANUAL_REVIEW] Payout for booking {} ESCALATED — {} retry attempts exhausted. "
                    + "Runbook: https://wiki.internal/runbooks/payout-failure",
                    ledger.getBookingId(), ledger.getAttemptCount());
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Failed to escalate exhausted payout for ledger {}: {}",
                    ledger.getId(), e.getMessage(), e);
        }
    }

    // ── Re-auth ───────────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReauthRequiredSafely(Booking booking) {
        try {
            // P1-FIX: Check the ENTITY's current state, not a constant.
            // ChargeLifecycleStatus.AUTHORIZED.canTransitionTo(...) always tests the same
            // source state regardless of what the booking actually holds.
            ChargeLifecycleStatus currentStatus = booking.getChargeLifecycleStatus();
            if (currentStatus == null || !currentStatus.canTransitionTo(ChargeLifecycleStatus.REAUTH_REQUIRED)) {
                log.warn("[SchedulerProcessor] Cannot mark REAUTH_REQUIRED for booking {} in state {}",
                        booking.getId(), currentStatus);
                return;
            }
            booking.setChargeLifecycleStatus(ChargeLifecycleStatus.REAUTH_REQUIRED);
            // R5-FIX: Keep legacy paymentStatus in sync with typed lifecycle status.
            // Admin UI reads paymentStatus only; without this, REAUTH_REQUIRED shows as stale "AUTHORIZED".
            booking.setPaymentStatus("REAUTH_REQUIRED");
            bookingRepository.save(booking);
            log.info("[SchedulerProcessor] Booking {} marked as REAUTH_REQUIRED (auth expires {})",
                    booking.getId(), booking.getBookingAuthExpiresAt());
            if (booking.getRenter() != null) {
                notificationService.createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(booking.getRenter().getId())
                        .type(NotificationType.PAYMENT_AUTH_EXPIRING)
                        .message(String.format(
                                "Autorizacija plaćanja za rezervaciju #%d ističe. Kontaktirajte podršku ako trip još nije počeo.",
                                booking.getId()))
                        .relatedEntityId(String.valueOf(booking.getId()))
                        .build());
            }
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Failed to mark booking {} as REAUTH_REQUIRED: {}",
                    booking.getId(), e.getMessage(), e);
        }
    }

    // ── Deposit auth expiry (H-11) ───────────────────────────────────────────

    /**
     * H-11: Mark bookings with expiring deposit authorizations.
     *
     * <p>For deposits that have actually expired ({@code depositAuthExpiresAt} is in the past),
     * transitions the deposit lifecycle to EXPIRED. For deposits expiring soon but not yet
     * expired, logs a high-severity warning without transitioning — this preserves the ability
     * to capture or release the deposit until actual expiry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDepositExpiryWarningSafely(Booking booking) {
        try {
            DepositLifecycleStatus currentDepositStatus = booking.getDepositLifecycleStatus();
            if (currentDepositStatus != DepositLifecycleStatus.AUTHORIZED) {
                log.debug("[SchedulerProcessor] Skipping deposit expiry for booking {} — deposit status is {}",
                        booking.getId(), currentDepositStatus);
                return;
            }

            Instant expiresAt = booking.getDepositAuthExpiresAt();
            boolean actuallyExpired = expiresAt != null && expiresAt.isBefore(Instant.now());

            if (actuallyExpired) {
                // Deposit has actually expired — transition to EXPIRED
                currentDepositStatus.transition(DepositLifecycleStatus.EXPIRED);
                booking.setDepositLifecycleStatus(DepositLifecycleStatus.EXPIRED);
                bookingRepository.save(booking);

                depositExpiryCounter.increment();
                log.error("[ALERT][DEPOSIT_EXPIRY] Booking {} deposit authorization EXPIRED at {} — "
                        + "deposit lifecycle marked EXPIRED. Operator action required: re-authorize or settle deposit. "
                        + "Runbook: https://wiki.internal/runbooks/deposit-auth-expiry",
                        booking.getId(), expiresAt);
            } else {
                // Deposit expiring soon but not yet expired — warn only, don't transition
                log.warn("[DEPOSIT_EXPIRY_WARNING] Booking {} deposit authorization expiring at {} — "
                        + "capture/release still possible. Monitor and act before expiry.",
                        booking.getId(), expiresAt);
            }

            if (booking.getRenter() != null) {
                notificationService.createNotification(CreateNotificationRequestDTO.builder()
                        .recipientId(booking.getRenter().getId())
                        .type(NotificationType.DEPOSIT_AUTH_EXPIRING)
                        .message(String.format(
                                "Autorizacija depozita za rezervaciju #%d %s. Kontaktirajte podršku.",
                                booking.getId(),
                                actuallyExpired ? "je istekla" : "uskoro ističe"))
                        .relatedEntityId(String.valueOf(booking.getId()))
                        .build());
            }
        } catch (Exception e) {
            log.error("[SchedulerProcessor] Failed to process deposit auth expiry for booking {}: {}",
                    booking.getId(), e.getMessage(), e);
        }
    }
}