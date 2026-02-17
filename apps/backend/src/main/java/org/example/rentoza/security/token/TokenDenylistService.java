package org.example.rentoza.security.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * Service to manage the JWT access token denylist.
 *
 * <p>When a user logs out, their access token is added to the denylist.
 * The SupabaseJwtAuthFilter checks this service before granting access.
 *
 * <p>Expired entries are automatically cleaned up hourly.
 *
 * @since Phase 3 - Security Hardening (Turo standard: logout invalidates JWT)
 */
@Service
public class TokenDenylistService {

    private static final Logger log = LoggerFactory.getLogger(TokenDenylistService.class);

    private final DeniedTokenRepository deniedTokenRepository;

    public TokenDenylistService(DeniedTokenRepository deniedTokenRepository) {
        this.deniedTokenRepository = deniedTokenRepository;
    }

    /**
     * Deny a JWT access token (add to blacklist).
     *
     * @param accessToken The raw JWT string
     * @param expiresAt   When the JWT was originally set to expire
     * @param userEmail   User's email for audit trail
     */
    @Transactional
    public void denyToken(String accessToken, Instant expiresAt, String userEmail) {
        String tokenHash = hashToken(accessToken);

        if (deniedTokenRepository.existsByTokenHash(tokenHash)) {
            log.debug("Token already denied for user={}", userEmail);
            return;
        }

        DeniedToken entry = new DeniedToken(tokenHash, expiresAt, userEmail);
        deniedTokenRepository.save(entry);
        log.info("JWT access token denied for user={}", userEmail);
    }

    /**
     * Check if a JWT is denied (blacklisted).
     *
     * @param accessToken The raw JWT string
     * @return true if the token is denied
     */
    public boolean isTokenDenied(String accessToken) {
        String tokenHash = hashToken(accessToken);
        return deniedTokenRepository.existsByTokenHash(tokenHash);
    }

    /**
     * Scheduled cleanup of expired denylist entries.
     * Runs every hour.
     */
    @Scheduled(fixedDelay = 3600000) // 1 hour
    @Transactional
    public void cleanupExpiredEntries() {
        int deleted = deniedTokenRepository.deleteExpiredEntries(Instant.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired token denylist entries", deleted);
        }
    }

    /**
     * Hash a JWT for storage (we never store raw tokens).
     */
    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
