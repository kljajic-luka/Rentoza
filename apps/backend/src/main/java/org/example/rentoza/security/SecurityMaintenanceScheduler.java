package org.example.rentoza.security;

import org.example.rentoza.security.password.PasswordResetTokenRepository;
import org.example.rentoza.security.token.DeniedTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Scheduled maintenance for security tables.
 *
 * <p>Cleans up:
 * <ul>
 *   <li>Expired password reset tokens (every 6 hours)</li>
 *   <li>Expired JWT denylist entries (every hour, via TokenDenylistService)</li>
 * </ul>
 *
 * @since Phase 3 - Security Hardening
 */
@Component
public class SecurityMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(SecurityMaintenanceScheduler.class);

    private final PasswordResetTokenRepository resetTokenRepository;
    private final DeniedTokenRepository deniedTokenRepository;

    public SecurityMaintenanceScheduler(
            PasswordResetTokenRepository resetTokenRepository,
            DeniedTokenRepository deniedTokenRepository
    ) {
        this.resetTokenRepository = resetTokenRepository;
        this.deniedTokenRepository = deniedTokenRepository;
    }

    /**
     * Clean up expired password reset tokens every 6 hours.
     */
    @Scheduled(fixedDelay = 21600000) // 6 hours
    @Transactional
    public void cleanupExpiredResetTokens() {
        int deleted = resetTokenRepository.deleteExpiredTokens(Instant.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired password reset tokens", deleted);
        }
    }

    /**
     * Clean up expired JWT denylist entries every 2 hours.
     */
    @Scheduled(fixedDelay = 7200000) // 2 hours
    @Transactional
    public void cleanupExpiredDenylistEntries() {
        int deleted = deniedTokenRepository.deleteExpiredEntries(Instant.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired JWT denylist entries", deleted);
        }
    }
}
