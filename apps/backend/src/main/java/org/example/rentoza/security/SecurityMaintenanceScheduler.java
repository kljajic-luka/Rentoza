package org.example.rentoza.security;

import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.security.password.PasswordResetTokenRepository;
import org.example.rentoza.security.token.DeniedTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Scheduled maintenance for security tables.
 *
 * <p>Cleans up:
 * <ul>
 *   <li>Expired password reset tokens (every 6 hours)</li>
 *   <li>Expired JWT denylist entries (every 2 hours)</li>
 * </ul>
 *
 * <p>Uses distributed locking to prevent duplicate execution in multi-instance deployments.
 *
 * @since Phase 3 - Security Hardening
 */
@Component
public class SecurityMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(SecurityMaintenanceScheduler.class);

    private static final String LOCK_RESET_TOKENS = "security.cleanup.reset-tokens";
    private static final String LOCK_DENYLIST = "security.cleanup.denylist";

    private final PasswordResetTokenRepository resetTokenRepository;
    private final DeniedTokenRepository deniedTokenRepository;
    private final SchedulerIdempotencyService lockService;

    public SecurityMaintenanceScheduler(
            PasswordResetTokenRepository resetTokenRepository,
            DeniedTokenRepository deniedTokenRepository,
            SchedulerIdempotencyService lockService
    ) {
        this.resetTokenRepository = resetTokenRepository;
        this.deniedTokenRepository = deniedTokenRepository;
        this.lockService = lockService;
    }

    /**
     * Clean up expired password reset tokens every 6 hours.
     */
    @Scheduled(fixedDelay = 21600000) // 6 hours
    @Transactional
    public void cleanupExpiredResetTokens() {
        if (!lockService.tryAcquireLock(LOCK_RESET_TOKENS, Duration.ofHours(5).plusMinutes(50))) {
            log.debug("[SecurityMaintenance] Skipping reset token cleanup — lock held by another instance");
            return;
        }

        try {
            int deleted = resetTokenRepository.deleteExpiredTokens(Instant.now());
            if (deleted > 0) {
                log.info("Cleaned up {} expired password reset tokens", deleted);
            }
        } finally {
            lockService.releaseLock(LOCK_RESET_TOKENS);
        }
    }

    /**
     * Clean up expired JWT denylist entries every 2 hours.
     *
     * <p>This is the single authoritative cleanup for the denylist. The duplicate
     * cleanup in TokenDenylistService has been removed to avoid double-execution.</p>
     */
    @Scheduled(fixedDelay = 7200000) // 2 hours
    @Transactional
    public void cleanupExpiredDenylistEntries() {
        if (!lockService.tryAcquireLock(LOCK_DENYLIST, Duration.ofHours(1).plusMinutes(50))) {
            log.debug("[SecurityMaintenance] Skipping denylist cleanup — lock held by another instance");
            return;
        }

        try {
            int deleted = deniedTokenRepository.deleteExpiredEntries(Instant.now());
            if (deleted > 0) {
                log.info("Cleaned up {} expired JWT denylist entries", deleted);
            }
        } finally {
            lockService.releaseLock(LOCK_DENYLIST);
        }
    }
}
