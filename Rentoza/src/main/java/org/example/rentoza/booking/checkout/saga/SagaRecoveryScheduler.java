package org.example.rentoza.booking.checkout.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaRecoveryScheduler {

    private final CheckoutSagaStateRepository sagaRepository;
    private final CheckoutSagaOrchestrator sagaOrchestrator;

    private static final int STUCK_THRESHOLD_MINUTES = 30;
    private static final int MAX_RETRY_BATCH_SIZE = 10;

    /**
     * Recover stuck sagas every 15 minutes.
     * 
     * <p>Sagas that have been in RUNNING state for too long are likely stuck
     * due to server crash or other interruption.
     */
    @Scheduled(fixedDelayString = "${app.saga.recovery.interval:900000}")  // 15 minutes
    @Transactional
    public void recoverStuckSagas() {
        Instant threshold = Instant.now().minus(STUCK_THRESHOLD_MINUTES, ChronoUnit.MINUTES);
        List<CheckoutSagaState> stuckSagas = sagaRepository.findStuckSagas(threshold);

        if (stuckSagas.isEmpty()) {
            log.debug("[Saga-Recovery] No stuck sagas found");
            return;
        }

        log.warn("[Saga-Recovery] Found {} stuck sagas", stuckSagas.size());

        for (CheckoutSagaState saga : stuckSagas) {
            try {
                log.info("[Saga-Recovery] Recovering stuck saga {} (stuck since {})",
                        saga.getSagaId(), saga.getUpdatedAt());

                // Mark as failed and attempt retry
                saga.setStatus(CheckoutSagaState.SagaStatus.FAILED);
                saga.setErrorMessage("Saga stuck for more than " + STUCK_THRESHOLD_MINUTES + " minutes");
                sagaRepository.save(saga);

                // Attempt retry if eligible
                if (saga.canRetry()) {
                    sagaOrchestrator.retrySaga(saga.getSagaId());
                }

            } catch (Exception e) {
                log.error("[Saga-Recovery] Failed to recover saga {}: {}",
                        saga.getSagaId(), e.getMessage());
            }
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
                log.info("[Saga-Recovery] Retrying saga {} (attempt {})",
                        saga.getSagaId(), saga.getRetryCount() + 1);

                sagaOrchestrator.retrySaga(saga.getSagaId());

            } catch (Exception e) {
                log.error("[Saga-Recovery] Retry failed for saga {}: {}",
                        saga.getSagaId(), e.getMessage());
            }
        }
    }

    /**
     * Complete interrupted compensations every 10 minutes.
     */
    @Scheduled(fixedDelayString = "${app.saga.compensation.interval:600000}")  // 10 minutes
    @Transactional
    public void completeCompensations() {
        List<CheckoutSagaState> compensatingSagas = sagaRepository.findSagasNeedingCompensation();

        if (compensatingSagas.isEmpty()) {
            log.debug("[Saga-Recovery] No sagas needing compensation");
            return;
        }

        log.info("[Saga-Recovery] Completing {} interrupted compensations", compensatingSagas.size());

        for (CheckoutSagaState saga : compensatingSagas) {
            try {
                log.info("[Saga-Recovery] Resuming compensation for saga {}", saga.getSagaId());

                sagaOrchestrator.startCompensation(saga);

            } catch (Exception e) {
                log.error("[Saga-Recovery] Compensation failed for saga {}: {}",
                        saga.getSagaId(), e.getMessage());
            }
        }
    }

    /**
     * Log saga statistics every hour for monitoring.
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
