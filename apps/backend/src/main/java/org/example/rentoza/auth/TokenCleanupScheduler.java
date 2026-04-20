package org.example.rentoza.auth;

import org.example.rentoza.deprecated.auth.RefreshTokenRepository;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Scheduled task to automatically clean up expired refresh tokens from the database.
 * Runs daily at 2 AM to minimize impact on production traffic.
 */
@Deprecated(since = "2.1.0", forRemoval = true)
@Component
public class TokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupScheduler.class);

    private static final String LOCK_CLEANUP = "token.cleanup.expired";
    private static final String LOCK_FREQUENT = "token.cleanup.frequent";

    private final RefreshTokenRepository repo;
    private final SchedulerIdempotencyService lockService;

    public TokenCleanupScheduler(RefreshTokenRepository repo,
                                  SchedulerIdempotencyService lockService) {
        this.repo = repo;
        this.lockService = lockService;
    }

    /**
     * Cleanup expired tokens daily at 2:00 AM (server time)
     * Cron: second, minute, hour, day of month, month, day of week
     * "0 0 2 * * ?" = At 02:00:00am every day
     */
    @Scheduled(cron = "${refresh-token.cleanup.cron:0 0 2 * * ?}", zone = "Europe/Belgrade")
    @Transactional
    public void cleanupExpiredTokens() {
        if (!lockService.tryAcquireLock(LOCK_CLEANUP, Duration.ofHours(23))) {
            log.debug("[TokenCleanup] Skipping daily cleanup — lock held by another instance");
            return;
        }

        try {
            log.info("Starting scheduled cleanup of expired refresh tokens");
            Instant now = Instant.now();
            int deletedCount = repo.deleteAllExpired(now);

            if (deletedCount > 0) {
                log.info("Cleanup completed: {} expired refresh tokens removed", deletedCount);
            } else {
                log.debug("Cleanup completed: no expired tokens found");
            }
        } catch (Exception e) {
            log.error("Error during token cleanup: {}", e.getMessage(), e);
        } finally {
            lockService.releaseLock(LOCK_CLEANUP);
        }
    }

    /**
     * Additional cleanup every 6 hours (for high-traffic environments)
     * Can be disabled by setting refresh-token.cleanup.frequent.enabled=false
     */
    @Scheduled(cron = "${refresh-token.cleanup.frequent.cron:0 0 */6 * * ?}", zone = "Europe/Belgrade")
    @Transactional
    public void frequentCleanup() {
        if (Boolean.parseBoolean(System.getProperty("refresh-token.cleanup.frequent.enabled", "false"))) {
            if (!lockService.tryAcquireLock(LOCK_FREQUENT, Duration.ofHours(5).plusMinutes(50))) {
                log.debug("[TokenCleanup] Skipping frequent cleanup — lock held by another instance");
                return;
            }

            try {
                log.debug("Running frequent token cleanup");
                Instant now = Instant.now();
                int deletedCount = repo.deleteAllExpired(now);

                if (deletedCount > 0) {
                    log.debug("Frequent cleanup: {} expired tokens removed", deletedCount);
                }
            } catch (Exception e) {
                log.warn("Error during frequent cleanup: {}", e.getMessage());
            } finally {
                lockService.releaseLock(LOCK_FREQUENT);
            }
        }
    }

}
