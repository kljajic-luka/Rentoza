package org.example.rentoza.booking.checkout.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Scheduled job for saga recovery and monitoring.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Detect and recover stuck sagas</li>
 *   <li>Retry failed sagas with retry budget</li>
 *   <li>Continue compensation for interrupted sagas</li>
 *   <li>Report saga health metrics</li>
 * </ul>
 *
 * <h2>G6: Distributed Locking</h2>
 * <p>All mutating scheduled methods use {@link SchedulerIdempotencyService} to
 * prevent concurrent execution across multiple application instances.
 * {@code logSagaStatistics()} is read-only and intentionally unlocked.</p>
 */
@Component
@Slf4j
public class SagaRecoveryScheduler {

    private static final String LOCK_RECOVER = "saga.recovery.recover-stuck";
    private static final String LOCK_RETRY = "saga.recovery.retry-failed";
    private static final String LOCK_COMPENSATE = "saga.recovery.compensations";
    private static final String LOCK_DISPUTE_ESCALATION = "saga.recovery.dispute-escalation";

    private final CheckoutSagaStateRepository sagaRepository;
    private final CheckoutSagaOrchestrator sagaOrchestrator;
    private final SchedulerIdempotencyService lockService;

    // Recovery metrics
    private final Counter recoveryAttemptedCounter;
    private final Counter recoverySucceededCounter;
    private final Counter recoveryFailedCounter;
    private final Counter disputeEscalationCounter;

    private static final int STUCK_THRESHOLD_MINUTES = 30;
    private static final int MAX_RETRY_BATCH_SIZE = 10;

    public SagaRecoveryScheduler(CheckoutSagaStateRepository sagaRepository,
                                  CheckoutSagaOrchestrator sagaOrchestrator,
                                  SchedulerIdempotencyService lockService,
                                  MeterRegistry meterRegistry) {
        this.sagaRepository = sagaRepository;
        this.sagaOrchestrator = sagaOrchestrator;
        this.lockService = lockService;

        this.recoveryAttemptedCounter = Counter.builder("checkout.saga.recovery.attempted")
                .description("Recovery attempts on stuck/failed sagas")
                .register(meterRegistry);
        this.recoverySucceededCounter = Counter.builder("checkout.saga.recovery.succeeded")
                .description("Successful saga recoveries")
                .register(meterRegistry);
        this.recoveryFailedCounter = Counter.builder("checkout.saga.recovery.failed")
                .description("Failed saga recoveries")
                .register(meterRegistry);
        this.disputeEscalationCounter = Counter.builder("checkout.saga.dispute.auto_escalated")
                .tag("severity", "high")
                .description("Dispute holds auto-escalated after expiry")
                .register(meterRegistry);
    }

    /**
     * AUDIT-C2-FIX + AUDIT-M1-FIX: Escalate suspended sagas where the dispute hold has expired.
     * Runs every 6 hours.
     */
    @Scheduled(fixedDelayString = "${app.saga.recovery.dispute-escalation-interval:21600000}")
    @Transactional
    public void escalateExpiredDisputeHolds() {
        if (!lockService.tryAcquireLock(LOCK_DISPUTE_ESCALATION, Duration.ofHours(5))) {
            return;
        }

        try {
            List<CheckoutSagaState> expired = sagaRepository.findSuspendedWithExpiredHold(Instant.now());

            for (CheckoutSagaState saga : expired) {
                try {
                    log.warn("[Saga][AUDIT-M1] Dispute hold expired for saga {} booking {} - escalating to MANUAL_REVIEW",
                            saga.getSagaId(), saga.getBookingId());
                    saga.setStatus(CheckoutSagaState.SagaStatus.FAILED);
                    saga.setErrorMessage("Dispute hold expired without resolution - escalated to manual review");
                    sagaRepository.save(saga);
                    disputeEscalationCounter.increment();
                } catch (Exception e) {
                    log.error("[Saga] Failed to escalate expired dispute hold for saga {}: {}",
                            saga.getSagaId(), e.getMessage());
                }
            }
        } finally {
            lockService.releaseLock(LOCK_DISPUTE_ESCALATION);
        }
    }

    /**
     * Recover stuck sagas every 15 minutes.
     *
     * <p>Sagas that have been in RUNNING state for too long are likely stuck
     * due to server crash or other interruption.
     */
    @Scheduled(fixedDelayString = "${app.saga.recovery.interval:900000}")  // 15 minutes
    @Transactional
    public void recoverStuckSagas() {
        if (!lockService.tryAcquireLock(LOCK_RECOVER, Duration.ofMinutes(14))) {
            log.debug("[Saga-Recovery] Skipping recover-stuck — lock held by another instance");
            return;
        }

        try {
            Instant threshold = Instant.now().minus(STUCK_THRESHOLD_MINUTES, ChronoUnit.MINUTES);
            List<CheckoutSagaState> stuckSagas = sagaRepository.findStuckSagas(threshold);

            if (stuckSagas.isEmpty()) {
                log.debug("[Saga-Recovery] No stuck sagas found");
                return;
            }

            log.warn("[Saga-Recovery] Found {} stuck sagas", stuckSagas.size());

            for (CheckoutSagaState saga : stuckSagas) {
                try {
                    recoveryAttemptedCounter.increment();

                    log.info("[Saga-Recovery] Recovering stuck saga {} (stuck since {})",
                            saga.getSagaId(), saga.getUpdatedAt());

                    // Mark as failed and attempt retry
                    saga.setStatus(CheckoutSagaState.SagaStatus.FAILED);
                    saga.setErrorMessage("Saga stuck for more than " + STUCK_THRESHOLD_MINUTES + " minutes");
                    sagaRepository.save(saga);

                    // Attempt retry if eligible
                    if (saga.canRetry()) {
                        CheckoutSagaState result = sagaOrchestrator.retrySaga(saga.getSagaId());
                        if (result.getStatus() == CheckoutSagaState.SagaStatus.COMPLETED) {
                            recoverySucceededCounter.increment();
                        } else {
                            recoveryFailedCounter.increment();
                        }
                    } else {
                        recoveryFailedCounter.increment();
                    }

                } catch (Exception e) {
                    recoveryFailedCounter.increment();
                    log.error("[Saga-Recovery] Failed to recover saga {}: {}",
                            saga.getSagaId(), e.getMessage());
                }
            }
        } finally {
            lockService.releaseLock(LOCK_RECOVER);
        }
    }

    /**
     * Retry failed sagas every 30 minutes.
     *
     * <p>Processes a batch of failed sagas that haven't exhausted their retry budget.
     */
    @Scheduled(fixedDelayString = "${app.saga.retry.interval:1800000}")  // 30 minutes
    @Transactional
    public void retryFailedSagas() {
        if (!lockService.tryAcquireLock(LOCK_RETRY, Duration.ofMinutes(29))) {
            log.debug("[Saga-Recovery] Skipping retry-failed — lock held by another instance");
            return;
        }

        try {
            List<CheckoutSagaState> retryableSagas = sagaRepository.findRetryableSagas()
                    .stream()
                    .limit(MAX_RETRY_BATCH_SIZE)
                    .toList();

            if (retryableSagas.isEmpty()) {
                log.debug("[Saga-Recovery] No retryable sagas found");
                return;
            }

            log.info("[Saga-Recovery] Retrying {} failed sagas", retryableSagas.size());

            for (CheckoutSagaState saga : retryableSagas) {
                try {
                    recoveryAttemptedCounter.increment();

                    log.info("[Saga-Recovery] Retrying saga {} (attempt {})",
                            saga.getSagaId(), saga.getRetryCount() + 1);

                    CheckoutSagaState result = sagaOrchestrator.retrySaga(saga.getSagaId());
                    if (result.getStatus() == CheckoutSagaState.SagaStatus.COMPLETED) {
                        recoverySucceededCounter.increment();
                    } else {
                        recoveryFailedCounter.increment();
                    }

                } catch (Exception e) {
                    recoveryFailedCounter.increment();
                    log.error("[Saga-Recovery] Retry failed for saga {}: {}",
                            saga.getSagaId(), e.getMessage());
                }
            }
        } finally {
            lockService.releaseLock(LOCK_RETRY);
        }
    }

    /**
     * Complete interrupted compensations every 10 minutes.
     */
    @Scheduled(fixedDelayString = "${app.saga.compensation.interval:600000}")  // 10 minutes
    @Transactional
    public void completeCompensations() {
        if (!lockService.tryAcquireLock(LOCK_COMPENSATE, Duration.ofMinutes(9))) {
            log.debug("[Saga-Recovery] Skipping compensations — lock held by another instance");
            return;
        }

        try {
            List<CheckoutSagaState> compensatingSagas = sagaRepository.findSagasNeedingCompensation();

            if (compensatingSagas.isEmpty()) {
                log.debug("[Saga-Recovery] No sagas needing compensation");
                return;
            }

            log.info("[Saga-Recovery] Completing {} interrupted compensations", compensatingSagas.size());

            for (CheckoutSagaState saga : compensatingSagas) {
                try {
                    recoveryAttemptedCounter.increment();

                    log.info("[Saga-Recovery] Resuming compensation for saga {}", saga.getSagaId());

                    sagaOrchestrator.startCompensation(saga);

                    if (saga.getStatus() == CheckoutSagaState.SagaStatus.COMPENSATED) {
                        recoverySucceededCounter.increment();
                    } else {
                        recoveryFailedCounter.increment();
                    }

                } catch (Exception e) {
                    recoveryFailedCounter.increment();
                    log.error("[Saga-Recovery] Compensation failed for saga {}: {}",
                            saga.getSagaId(), e.getMessage());
                }
            }
        } finally {
            lockService.releaseLock(LOCK_COMPENSATE);
        }
    }

    /**
     * Log saga statistics every hour for monitoring.
     * Intentionally unlocked — read-only, idempotent.
     */
    @Scheduled(fixedDelayString = "${app.saga.stats.interval:3600000}")  // 1 hour
    public void logSagaStatistics() {
        List<Object[]> stats = sagaRepository.countByStatus();

        StringBuilder sb = new StringBuilder("[Saga-Stats] Checkout saga distribution: ");
        for (Object[] stat : stats) {
            sb.append(stat[0]).append("=").append(stat[1]).append(" ");
        }

        log.info(sb.toString().trim());
    }
}
