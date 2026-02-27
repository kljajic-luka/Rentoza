package org.example.rentoza.user.gdpr;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GDPR Article 17 Deletion Executor — processes users whose 30-day grace period has expired.
 *
 * <p>Runs daily at 03:00 Serbia time. Queries users where
 * {@code deletionScheduledAt < now AND deleted = false}, then calls
 * {@link GdprService#permanentlyDeleteUser(Long)} for each result.
 *
 * <p>Uses distributed locking via {@link SchedulerIdempotencyService} to prevent
 * duplicate execution across Cloud Run instances.
 *
 * <p>Remediates GAP-1 from production readiness audit: the 30-day deletion grace
 * period was previously set but never executed — {@code permanentlyDeleteUser} had
 * zero callers.
 *
 * @see GdprService#initiateAccountDeletion
 * @see GdprService#permanentlyDeleteUser
 */
@Component
@Slf4j
public class GdprDeletionScheduler {

    private static final String LOCK_KEY = "gdpr-deletion-executor";
    private static final Duration LOCK_TTL = Duration.ofHours(23);

    private final UserRepository userRepository;
    private final GdprService gdprService;
    private final SchedulerIdempotencyService lockService;

    private final Counter deletionsExecuted;
    private final Counter deletionsFailed;
    private final AtomicLong pendingDeletionsGauge = new AtomicLong(0);

    public GdprDeletionScheduler(
            UserRepository userRepository,
            GdprService gdprService,
            SchedulerIdempotencyService lockService,
            MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.gdprService = gdprService;
        this.lockService = lockService;

        this.deletionsExecuted = Counter.builder("gdpr.deletion.executed")
                .description("Users permanently deleted by GDPR scheduler")
                .register(meterRegistry);
        this.deletionsFailed = Counter.builder("gdpr.deletion.failed")
                .description("GDPR deletion failures requiring investigation")
                .register(meterRegistry);

        Gauge.builder("gdpr.deletion.pending", pendingDeletionsGauge, AtomicLong::doubleValue)
                .description("Users awaiting permanent deletion (grace period expired)")
                .register(meterRegistry);
    }

    /**
     * Execute pending GDPR deletions daily at 03:00 Serbia time.
     *
     * <p>The cron expression runs in the Europe/Belgrade timezone to match
     * the scheduler timezone configuration used by all other scheduled jobs.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Belgrade")
    public void executePendingDeletions() {
        if (!lockService.tryAcquireLock(LOCK_KEY, LOCK_TTL)) {
            log.debug("[GdprDeletion] Lock held by another instance, skipping");
            return;
        }

        log.info("[GdprDeletion] Starting GDPR deletion executor run");

        try {
            LocalDateTime now = LocalDateTime.now();
            List<User> pendingUsers = userRepository
                    .findByDeletionScheduledAtBeforeAndDeletedFalse(now);

            pendingDeletionsGauge.set(pendingUsers.size());

            if (pendingUsers.isEmpty()) {
                log.info("[GdprDeletion] No users pending deletion");
                return;
            }

            log.info("[GdprDeletion] Found {} users with expired grace period", pendingUsers.size());

            int successCount = 0;
            int failureCount = 0;

            for (User user : pendingUsers) {
                try {
                    log.info("[GdprDeletion] Executing permanent deletion for user {} " +
                            "(scheduled at: {})", user.getId(), user.getDeletionScheduledAt());

                    gdprService.permanentlyDeleteUser(user.getId());
                    deletionsExecuted.increment();
                    successCount++;

                    log.info("[GdprDeletion] Successfully deleted user {}", user.getId());
                } catch (Exception e) {
                    deletionsFailed.increment();
                    failureCount++;
                    log.error("[GdprDeletion] Failed to delete user {} — manual investigation required",
                            user.getId(), e);
                }
            }

            log.info("[GdprDeletion] Run complete: {} succeeded, {} failed out of {} total",
                    successCount, failureCount, pendingUsers.size());

            if (failureCount > 0) {
                log.error("[GdprDeletion] ALERT: {} deletion(s) failed — GDPR compliance at risk. " +
                        "Check gdpr.deletion.failed metric and investigate immediately.", failureCount);
            }
        } catch (Exception e) {
            log.error("[GdprDeletion] Scheduler run failed entirely", e);
        }
    }
}
