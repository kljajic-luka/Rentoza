package org.example.rentoza.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.payment.ChargeLifecycleStatus;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationRecordRepository;
import org.example.rentoza.booking.cancellation.RefundStatus;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.scheduler.SchedulerLockStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Payment Lifecycle Scheduler — enterprise-grade payment automation.
 *
 * <h2>Concurrency Safety</h2>
 * Every job acquires a distributed lock before running ({@link SchedulerLockStore}).
 * If the lock is held (another pod won), the job exits immediately — no duplicate work.
 * The lock TTL is set to ≤ 58 min so a crashed pod releases it before the next hourly tick.
 *
 * <h2>Isolation</h2>
 * Job-level methods are NOT @Transactional. Each booking is processed in its own
 * isolated transaction (via BookingPaymentService). A single booking failure does
 * not roll back the rest of the batch.
 *
 * <h2>Scheduled Jobs</h2>
 * <ol>
 *   <li><b>captureUpcomingPayments</b> — Capture authorized booking payments T-24h</li>
 *   <li><b>releaseDepositsAfterTrip</b> — Release security deposits T+48h after trip</li>
 *   <li><b>autoReleaseOverdueDeposits</b> — Safety net: force-release after 7 days</li>
 *   <li><b>processCancellationRefunds</b> — Execute PENDING/FAILED/stale-PROCESSING refunds</li>
 *   <li><b>scheduleHostPayouts</b> — Create PayoutLedger entries for completed bookings</li>
 *   <li><b>executeEligiblePayouts</b> — Disburse host payouts after dispute window</li>
 *   <li><b>reauthExpiredBookings</b> — Mark AUTHORIZED bookings near auth-expiry as REAUTH_REQUIRED</li>
 * </ol>
 */
@Service
@Slf4j
public class PaymentLifecycleScheduler {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    // Lock keys — unique per job
    private static final String LOCK_CAPTURE      = "payment.scheduler.capture";
    private static final String LOCK_DEP_RELEASE  = "payment.scheduler.deposit.release";
    private static final String LOCK_DEP_OVERDUE  = "payment.scheduler.deposit.overdue";
    private static final String LOCK_REFUND       = "payment.scheduler.refund";
    private static final String LOCK_PAYOUT_SCHED = "payment.scheduler.payout.schedule";
    private static final String LOCK_PAYOUT_EXEC  = "payment.scheduler.payout.execute";
    private static final String LOCK_REAUTH        = "payment.scheduler.reauth";
    private static final String LOCK_DEP_REAUTH    = "payment.scheduler.deposit.reauth";
    private static final String LOCK_WEBHOOK_REPLAY = "payment.scheduler.webhook.replay";
    private static final String LOCK_RECONCILIATION = "payment.scheduler.reconciliation";

    // Conservative TTL: must cover worst-case job duration; must leave gap before next tick
    private static final Duration LOCK_TTL_HOURLY  = Duration.ofMinutes(55);
    private static final Duration LOCK_TTL_SHORT   = Duration.ofMinutes(13);
    private static final Duration LOCK_TTL_SIXHRLY = Duration.ofHours(5).plusMinutes(30);

    /** Stale PROCESSING cutoff — records stuck longer than this are crash-recovery candidates. */
    private static final Duration STALE_PROCESSING_CUTOFF = Duration.ofMinutes(30);

    /**
     * Async payout cutoff — PROCESSING payouts with a provider reference older than this
     * are escalated to MANUAL_REVIEW. Bank-accepted transfers that never received a webhook
     * within 24 hours indicate a provider integration issue that needs human attention.
     */
    private static final Duration STALE_ASYNC_PAYOUT_CUTOFF = Duration.ofHours(24);

    private final BookingRepository bookingRepository;
    private final BookingPaymentService paymentService;
    private final PaymentProvider paymentProvider;
    private final CancellationRecordRepository cancellationRecordRepository;
    private final DamageClaimRepository damageClaimRepository;
    private final NotificationService notificationService;
    private final SchedulerLockStore schedulerLockStore;
    private final PayoutLedgerRepository payoutLedgerRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final ProviderEventService providerEventService;
    /**
     * P1-1: Per-item processor lives in a separate bean so that
     * {@code @Transactional(REQUIRES_NEW)} takes effect via the Spring proxy.
     * Direct self-invocation (same bean) would bypass the proxy.
     */
    private final SchedulerItemProcessor itemProcessor;

    // Metrics
    private final Counter captureSuccessCounter;
    private final Counter captureFailedCounter;
    private final Counter depositReleaseCounter;
    private final Counter overdueDepositReleaseCounter;
    private final Counter refundProcessedCounter;
    private final Counter refundFailedCounter;
    private final Counter refundManualReviewCounter;
    private final Counter payoutScheduledCounter;
    private final Counter payoutExecutedCounter;
    private final Counter reconciliationFlaggedCounter;

    @Value("${app.payment.capture.hours-before-trip:24}")
    private int captureHoursBeforeTrip;

    @Value("${app.payment.deposit.release-hours-after-trip:48}")
    private int depositReleaseHoursAfterTrip;

    @Value("${app.payment.deposit.max-hold-days:7}")
    private int maxDepositHoldDays;

    @Value("${app.payment.refund.retry-backoff-minutes:60}")
    private int refundRetryBackoffMinutes;

    @Value("${app.payment.reconciliation.stale-hours:2}")
    private int reconciliationStaleHours;

    public PaymentLifecycleScheduler(
            BookingRepository bookingRepository,
            BookingPaymentService paymentService,
            PaymentProvider paymentProvider,
            CancellationRecordRepository cancellationRecordRepository,
            DamageClaimRepository damageClaimRepository,
            NotificationService notificationService,
            SchedulerLockStore schedulerLockStore,
            PayoutLedgerRepository payoutLedgerRepository,
            PaymentTransactionRepository transactionRepository,
            ProviderEventService providerEventService,
            SchedulerItemProcessor itemProcessor,
            MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.paymentService = paymentService;
        this.paymentProvider = paymentProvider;
        this.cancellationRecordRepository = cancellationRecordRepository;
        this.damageClaimRepository = damageClaimRepository;
        this.notificationService = notificationService;
        this.schedulerLockStore = schedulerLockStore;
        this.payoutLedgerRepository = payoutLedgerRepository;
        this.transactionRepository = transactionRepository;
        this.providerEventService = providerEventService;
        this.itemProcessor = itemProcessor;

        this.captureSuccessCounter      = counter(meterRegistry, "payment.scheduler.capture.success",   "Scheduled captures succeeded");
        this.captureFailedCounter       = counter(meterRegistry, "payment.scheduler.capture.failed",    "Scheduled captures failed");
        this.depositReleaseCounter      = counter(meterRegistry, "payment.scheduler.deposit.released",   "Deposits released");
        this.overdueDepositReleaseCounter = counter(meterRegistry, "payment.scheduler.deposit.overdue_released", "Overdue deposits force-released");
        this.refundProcessedCounter     = counter(meterRegistry, "payment.scheduler.refund.processed",  "Cancellation refunds processed");
        this.refundFailedCounter        = counter(meterRegistry, "payment.scheduler.refund.failed",     "Cancellation refund failures");
        this.refundManualReviewCounter  = counter(meterRegistry, "payment.scheduler.refund.manual_review", "Refunds escalated to MANUAL_REVIEW");
        this.payoutScheduledCounter     = counter(meterRegistry, "payment.scheduler.payout.scheduled",  "Host payouts scheduled");
        this.payoutExecutedCounter      = counter(meterRegistry, "payment.scheduler.payout.executed",   "Host payouts executed");
        this.reconciliationFlaggedCounter = counter(meterRegistry, "payment.scheduler.reconciliation.flagged", "Stale non-terminal transactions flagged for reconciliation");
    }

    // ========== JOB 1: IN-TRIP CAPTURE SAFETY-NET ==========
    //
    // P0-1 FIX: The T-24h pre-capture scheduler has been REMOVED.
    // Capture now happens exclusively at the physical hand-off handshake
    // (CheckInService.confirmHandshake → BookingPaymentService.captureBookingPaymentNow).
    // This job is a pure safety-net: it captures any IN_TRIP booking whose
    // handshake capture never completed (e.g., transient provider timeout).
    // tripStartedAt IS NOT NULL ensures the physical hand-off already occurred.

    @Scheduled(cron = "0 0 * * * *") // Every hour
    public void captureUpcomingPayments() {
        if (!schedulerLockStore.tryAcquireLock(LOCK_CAPTURE, LOCK_TTL_HOURLY)) {
            log.debug("[PaymentScheduler] captureUpcomingPayments — lock held by another pod, skipping");
            return;
        }
        try {
            log.info("[PaymentScheduler] Running IN_TRIP capture safety-net job");
            List<Booking> bookingsToCapture = bookingRepository.findBookingsNeedingPaymentCapture();

            if (!bookingsToCapture.isEmpty()) {
                log.warn("[PaymentScheduler] Safety-net: {} IN_TRIP booking(s) have AUTHORIZED charge not yet captured",
                        bookingsToCapture.size());
            }

            for (Booking booking : bookingsToCapture) {
                processCaptureSafely(booking);
            }
        } finally {
            schedulerLockStore.releaseLock(LOCK_CAPTURE);
        }
    }

    /**
     * Delegates to {@link SchedulerItemProcessor#processCaptureSafely} which runs
     * in an isolated {@code REQUIRES_NEW} transaction via the Spring proxy.
     * Kept here for backward-compatibility with tests that call this method directly.
     */
    /** Delegates to {@link SchedulerItemProcessor#processCaptureSafely} for isolated transaction. */
    public void processCaptureSafely(Booking booking) {
        itemProcessor.processCaptureSafely(booking);
    }

    // ========== JOB 2: RELEASE DEPOSITS 48H AFTER TRIP ==========

    @Scheduled(cron = "0 */30 * * * *") // Every 30 minutes
    public void releaseDepositsAfterTrip() {
        if (!schedulerLockStore.tryAcquireLock(LOCK_DEP_RELEASE, LOCK_TTL_SHORT)) {
            log.debug("[PaymentScheduler] releaseDepositsAfterTrip — lock held, skipping");
            return;
        }
        try {
            log.info("[PaymentScheduler] Running releaseDepositsAfterTrip job");
            Instant releaseAfter = Instant.now().minus(depositReleaseHoursAfterTrip, ChronoUnit.HOURS);
            List<Booking> eligible = bookingRepository.findBookingsEligibleForDepositRelease(
                    BookingStatus.COMPLETED, releaseAfter);

            log.info("[PaymentScheduler] Found {} deposits eligible for release", eligible.size());

            for (Booking booking : eligible) {
                releaseDepositSafely(booking);
            }
        } finally {
            schedulerLockStore.releaseLock(LOCK_DEP_RELEASE);
        }
    }

    /** Delegates to {@link SchedulerItemProcessor#releaseDepositSafely} for isolated transaction. */
    public void releaseDepositSafely(Booking booking) {
        itemProcessor.releaseDepositSafely(booking);
    }

    // ========== JOB 3: SAFETY NET — AUTO-RELEASE OVERDUE DEPOSITS ==========

    @Scheduled(cron = "0 0 */6 * * *") // Every 6 hours
    public void autoReleaseOverdueDeposits() {
        if (!schedulerLockStore.tryAcquireLock(LOCK_DEP_OVERDUE, LOCK_TTL_SIXHRLY)) {
            log.debug("[PaymentScheduler] autoReleaseOverdueDeposits — lock held, skipping");
            return;
        }
        try {
            log.info("[PaymentScheduler] Running autoReleaseOverdueDeposits safety net");
            Instant maxHoldDeadline = Instant.now().minus(maxDepositHoldDays, ChronoUnit.DAYS);
            List<BookingStatus> terminalStatuses = List.of(BookingStatus.COMPLETED, BookingStatus.CANCELLED);
            List<Booking> overdueBookings = bookingRepository.findBookingsWithOverdueDepositHold(
                    maxHoldDeadline, terminalStatuses);

            if (!overdueBookings.isEmpty()) {
                log.warn("[PaymentScheduler] SAFETY NET: {} deposits held beyond {} day limit",
                        overdueBookings.size(), maxDepositHoldDays);
            }

            for (Booking booking : overdueBookings) {
                forceReleaseDepositSafely(booking);
            }
        } finally {
            schedulerLockStore.releaseLock(LOCK_DEP_OVERDUE);
        }
    }

    /** Delegates to {@link SchedulerItemProcessor#forceReleaseDepositSafely} for isolated transaction. */
    public void forceReleaseDepositSafely(Booking booking) {
        itemProcessor.forceReleaseDepositSafely(booking);
    }

    // ========== JOB 4: PROCESS CANCELLATION REFUNDS (PENDING + FAILED + STALE PROCESSING) ==========

    /**
     * Process refunds across three states:
     * <ol>
     *   <li>PENDING — initial pending, process now</li>
     *   <li>FAILED — retry-eligible failed (retryCount &lt; maxRetries, backoff elapsed)</li>
     *   <li>PROCESSING (stale) — crash-recovery: treat like PENDING and re-attempt</li>
     * </ol>
     * On final failure (retryCount == maxRetries), escalates to MANUAL_REVIEW.
     */
    @Scheduled(cron = "0 */15 * * * *") // Every 15 minutes
    public void processCancellationRefunds() {
        if (!schedulerLockStore.tryAcquireLock(LOCK_REFUND, LOCK_TTL_SHORT)) {
            log.debug("[PaymentScheduler] processCancellationRefunds — lock held, skipping");
            return;
        }
        try {
            log.info("[PaymentScheduler] Running processCancellationRefunds job");
            Instant now = Instant.now();

            List<CancellationRecord> pending = cancellationRecordRepository.findByRefundStatus(RefundStatus.PENDING);
            List<CancellationRecord> retryable = cancellationRecordRepository.findRetryEligibleFailed(now);
            List<CancellationRecord> stale = cancellationRecordRepository.findStaleProcessing(
                    now.minus(STALE_PROCESSING_CUTOFF));

            int total = pending.size() + retryable.size() + stale.size();
            if (total > 0) {
                log.info("[PaymentScheduler] Processing {} refunds: {} PENDING, {} FAILED retry, {} stale PROCESSING",
                        total, pending.size(), retryable.size(), stale.size());
            }

            for (CancellationRecord r : pending)   processRefundSafely(r);
            for (CancellationRecord r : retryable)  processRefundSafely(r);
            for (CancellationRecord r : stale)      processRefundSafely(r);

        } finally {
            schedulerLockStore.releaseLock(LOCK_REFUND);
        }
    }

    /** Delegates to {@link SchedulerItemProcessor#processRefundSafely} for isolated transaction. */
    public void processRefundSafely(CancellationRecord record) {
        itemProcessor.processRefundSafely(record);
    }

    // ========== JOB 4B: REPLAY STALE WEBHOOK EVENTS ==========

    @Scheduled(cron = "0 */5 * * * *")
    public void replayStaleWebhookEvents() {
        if (!schedulerLockStore.tryAcquireLock(LOCK_WEBHOOK_REPLAY, LOCK_TTL_SHORT)) {
            log.debug("[PaymentScheduler] replayStaleWebhookEvents — lock held, skipping");
            return;
        }
        try {
            Instant replayBefore = Instant.now().minus(30, ChronoUnit.SECONDS);
            int replayed = providerEventService.replayStaleEvents(replayBefore);
            if (replayed > 0) {
                log.info("[PaymentScheduler] Replayed {} stale webhook event(s)", replayed);
            }
        } finally {
            schedulerLockStore.releaseLock(LOCK_WEBHOOK_REPLAY);
        }
    }

    // ========== JOB 5: SCHEDULE HOST PAYOUTS ==========

    @Scheduled(cron = "0 0 * * * *") // Every hour (offset scheduling via different second)
    public void scheduleHostPayouts() {
        if (!schedulerLockStore.tryAcquireLock(LOCK_PAYOUT_SCHED, LOCK_TTL_HOURLY)) {
            log.debug("[PaymentScheduler] scheduleHostPayouts — lock held, skipping");
            return;
        }
        try {
            log.info("[PaymentScheduler] Running scheduleHostPayouts job");
            // Find completed bookings without a payout ledger entry yet
            List<Booking> completedWithoutPayout = bookingRepository.findCompletedBookingsNeedingPayout();
            log.info("[PaymentScheduler] Found {} completed bookings to schedule payouts for", completedWithoutPayout.size());

            for (Booking booking : completedWithoutPayout) {
                schedulePayoutSafely(booking);
            }
        } finally {
            schedulerLockStore.releaseLock(LOCK_PAYOUT_SCHED);
        }
    }

    /** Delegates to {@link SchedulerItemProcessor#schedulePayoutSafely} for isolated transaction. */
    public void schedulePayoutSafely(Booking booking) {
        itemProcessor.schedulePayoutSafely(booking);
    }

    // ========== JOB 6: EXECUTE ELIGIBLE PAYOUTS ==========

    @Scheduled(cron = "0 30 * * * *") // Every hour at :30 past
    // P2-FIX: NOT @Transactional. The outer method must not hold a transaction because:
    // 1. findEligibleForPayout() uses FOR UPDATE SKIP LOCKED, which acquires row locks
    // 2. executePayoutSafely() runs in REQUIRES_NEW, which suspends the outer tx
    // 3. The inner tx tries to UPDATE the same rows locked by the suspended outer tx → self-deadlock
    // Instead, the query runs in its own implicit read tx (locks released after query),
    // and each item is processed in an isolated REQUIRES_NEW tx via SchedulerItemProcessor.
    // The distributed lock + idempotency keys + @Version provide sufficient safety.
    public void executeEligiblePayouts() {
        if (!schedulerLockStore.tryAcquireLock(LOCK_PAYOUT_EXEC, LOCK_TTL_HOURLY)) {
            log.debug("[PaymentScheduler] executeEligiblePayouts — lock held, skipping");
            return;
        }
        try {
            log.info("[PaymentScheduler] Running executeEligiblePayouts job");
            Instant now = Instant.now();

            // First: mark PENDING→ELIGIBLE where dispute window has elapsed
            List<PayoutLedger> readyToEligible = payoutLedgerRepository.findReadyToMarkEligible(now);
            for (PayoutLedger ledger : readyToEligible) {
                markPayoutEligibleSafely(ledger);
            }

            // Then: execute ELIGIBLE payouts
            List<PayoutLedger> eligible = payoutLedgerRepository.findEligibleForPayout(now);
            log.info("[PaymentScheduler] Found {} payouts eligible for execution", eligible.size());

            for (PayoutLedger ledger : eligible) {
                executePayoutSafely(ledger);
            }

            // Recovery: check stale PROCESSING payouts (crashed before provider responded)
            List<PayoutLedger> staleProcessing = payoutLedgerRepository.findStaleProcessing(
                    now.minus(STALE_PROCESSING_CUTOFF));
            for (PayoutLedger ledger : staleProcessing) {
                log.warn("[PaymentScheduler] Stale payout PROCESSING for booking {} (no provider ref) — re-queueing as ELIGIBLE",
                        ledger.getBookingId());
                markPayoutEligibleSafely(ledger);
            }

            // P1-FIX: Escalate async payouts stuck in PROCESSING with a provider reference
            // for >24h. The bank accepted the transfer but the webhook never arrived.
            // These must NOT be requeued (would cause duplicate transfers) — escalate to
            // MANUAL_REVIEW for operator investigation.
            List<PayoutLedger> staleAsync = payoutLedgerRepository.findStaleAsyncProcessing(
                    now.minus(STALE_ASYNC_PAYOUT_CUTOFF));
            for (PayoutLedger ledger : staleAsync) {
                escalateStaleAsyncPayoutSafely(ledger);
            }

            // P1-FIX: Recover FAILED payouts (from PAYOUT.FAILED webhook).
            // Without this, webhook-failed payouts were dead-lettered — no scheduler
            // query matched the FAILED status. Retry-eligible ones are requeued to
            // ELIGIBLE; exhausted ones are escalated to MANUAL_REVIEW.
            List<PayoutLedger> retryableFailed = payoutLedgerRepository.findRetryEligibleFailedPayouts(now);
            for (PayoutLedger ledger : retryableFailed) {
                log.info("[PaymentScheduler] Retrying FAILED payout for booking {} (attempt {}/{})",
                        ledger.getBookingId(), ledger.getAttemptCount(), ledger.getMaxAttempts());
                markPayoutEligibleSafely(ledger);
            }

            List<PayoutLedger> exhaustedFailed = payoutLedgerRepository.findExhaustedFailedPayouts();
            for (PayoutLedger ledger : exhaustedFailed) {
                escalateExhaustedPayoutSafely(ledger);
            }
        } finally {
            schedulerLockStore.releaseLock(LOCK_PAYOUT_EXEC);
        }
    }

    /** Delegates to {@link SchedulerItemProcessor#markPayoutEligibleSafely} for isolated transaction. */
    public void markPayoutEligibleSafely(PayoutLedger ledger) {
        itemProcessor.markPayoutEligibleSafely(ledger);
    }

    /** Delegates to {@link SchedulerItemProcessor#executePayoutSafely} for isolated transaction. */
    public void executePayoutSafely(PayoutLedger ledger) {
        itemProcessor.executePayoutSafely(ledger);
    }

    /** Delegates to {@link SchedulerItemProcessor#escalateStaleAsyncPayoutSafely} for isolated transaction. */
    public void escalateStaleAsyncPayoutSafely(PayoutLedger ledger) {
        itemProcessor.escalateStaleAsyncPayoutSafely(ledger);
    }

    /** Delegates to {@link SchedulerItemProcessor#escalateExhaustedPayoutSafely} for isolated transaction. */
    public void escalateExhaustedPayoutSafely(PayoutLedger ledger) {
        itemProcessor.escalateExhaustedPayoutSafely(ledger);
    }

    // ========== P0-6: AUTH EXPIRY REAUTH JOB ==========

    /**
     * Runs every 6 hours. Finds bookings whose payment authorisation is expiring within
     * the next 48 hours and marks them as REAUTH_REQUIRED so the owner / renter can take action.
     */
    @Scheduled(cron = "0 0 */6 * * *") // Every 6 hours
    public void reauthExpiredBookings() {
        if (!schedulerLockStore.tryAcquireLock(LOCK_REAUTH, LOCK_TTL_SIXHRLY)) {
            log.debug("[PaymentScheduler] Reauth lock held by another pod — skipping");
            return;
        }
        try {
            Instant thresholdTime = Instant.now().plus(48, ChronoUnit.HOURS);
            List<Booking> expiring = bookingRepository.findBookingsWithExpiringAuth(
                    thresholdTime, ChargeLifecycleStatus.AUTHORIZED);
            log.info("[PaymentScheduler] Reauth check: {} booking(s) have auth expiring before {}",
                    expiring.size(), thresholdTime);
            for (Booking booking : expiring) {
                markReauthRequiredSafely(booking);
            }
        } finally {
            schedulerLockStore.releaseLock(LOCK_REAUTH);
        }
    }

    /** Delegates to {@link SchedulerItemProcessor#markReauthRequiredSafely} for isolated transaction. */
    public void markReauthRequiredSafely(Booking booking) {
        itemProcessor.markReauthRequiredSafely(booking);
    }

    // ========== JOB 8: H-11 DEPOSIT AUTH EXPIRY MONITORING ==========

    /**
     * H-11 FIX: Deposit-specific auth expiry monitoring.
     *
     * <p>The existing {@link #reauthExpiredBookings()} only checks charge authorization
     * expiry ({@code bookingAuthExpiresAt}). This job independently monitors deposit
     * authorization expiry ({@code depositAuthExpiresAt}) so that expiring deposit
     * holds are flagged before they silently lapse at the gateway.
     *
     * <p>Runs every 6 hours. Finds bookings whose deposit auth expires within 48h and
     * marks the deposit lifecycle as EXPIRED with operator notification.
     */
    @Scheduled(cron = "0 15 */6 * * *") // Every 6 hours at :15 past
    public void monitorDepositAuthExpiry() {
        if (!schedulerLockStore.tryAcquireLock(LOCK_DEP_REAUTH, LOCK_TTL_SIXHRLY)) {
            log.debug("[PaymentScheduler] monitorDepositAuthExpiry — lock held, skipping");
            return;
        }
        try {
            Instant thresholdTime = Instant.now().plus(48, ChronoUnit.HOURS);
            List<Booking> expiring = bookingRepository.findBookingsWithExpiringDepositAuth(thresholdTime);
            log.info("[PaymentScheduler] Deposit auth expiry check: {} booking(s) have deposit auth expiring before {}",
                    expiring.size(), thresholdTime);
            for (Booking booking : expiring) {
                markDepositExpiryWarningSafely(booking);
            }
        } finally {
            schedulerLockStore.releaseLock(LOCK_DEP_REAUTH);
        }
    }

    /** Delegates to {@link SchedulerItemProcessor#markDepositExpiryWarningSafely} for isolated transaction. */
    public void markDepositExpiryWarningSafely(Booking booking) {
        itemProcessor.markDepositExpiryWarningSafely(booking);
    }

    // ========== JOB 9: RECONCILIATION — STALE TRANSACTION ESCALATION ==========

    /**
     * Detect stale non-terminal payment transactions and escalate to appropriate terminal states.
     *
     * <p><b>PROCESSING / FAILED_RETRYABLE</b> transactions older than {@code reconciliationStaleHours}
     * are transitioned to {@code MANUAL_REVIEW} — the provider call either timed out with no webhook,
     * or retry attempts were exhausted without scheduler pickup.
     *
     * <p><b>REDIRECT_REQUIRED / PENDING_CONFIRMATION</b> (3DS redirects the guest never completed)
     * are transitioned to {@code FAILED_TERMINAL} — the authorization window has passed and the
     * guest must restart the payment flow.
     *
     * <p>Each transaction is processed in its own {@code REQUIRES_NEW} transaction via
     * {@link SchedulerItemProcessor} so one failure doesn't affect others.
     */
    @Scheduled(cron = "0 */30 * * * *") // Every 30 minutes
    public void reconcileStaleNonTerminalTransactions() {
        if (!schedulerLockStore.tryAcquireLock(LOCK_RECONCILIATION, LOCK_TTL_SHORT)) {
            log.debug("[PaymentScheduler] reconcileStaleNonTerminalTransactions — lock held, skipping");
            return;
        }
        try {
            Instant staleBefore = Instant.now().minus(reconciliationStaleHours, ChronoUnit.HOURS);
            List<PaymentTransaction> stale = transactionRepository.findStaleNonTerminalTransactions(staleBefore);
            if (stale.isEmpty()) {
                return;
            }

            reconciliationFlaggedCounter.increment(stale.size());
            log.warn("[PaymentScheduler][AUDIT-T10] Reconciliation escalating {} stale non-terminal payment transaction(s) older than {}",
                    stale.size(), staleBefore);

            for (PaymentTransaction tx : stale) {
                itemProcessor.reconcileStaleTransactionSafely(tx);
            }
        } finally {
            schedulerLockStore.releaseLock(LOCK_RECONCILIATION);
        }
    }

    // ========== HELPERS ==========

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }
}


