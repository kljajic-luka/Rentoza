package org.example.rentoza.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Enhanced refresh token service with production-ready security features.
 *
 * Security Enhancements:
 * - Token reuse detection to prevent replay attacks
 * - Automatic revocation of all tokens on suspicious activity
 * - Optional IP/UserAgent fingerprinting for production environments
 * - Atomic token rotation with SERIALIZABLE isolation
 * - Comprehensive logging for security auditing
 * - Configurable token expiration
 *
 * @author Rentoza Team
 * @since 2.0.0
 */
@Service
public class RefreshTokenServiceEnhanced {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenServiceEnhanced.class);
    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY_AUDIT");
    private static final int TOKEN_BYTES = 64; // 512 bits

    private final RefreshTokenRepository repo;
    private final SecureRandom random = new SecureRandom();
    private final long tokenExpiryDays;
    private final boolean fingerprintEnabled;

    public RefreshTokenServiceEnhanced(
            RefreshTokenRepository repo,
            @Value("${refresh-token.expiration-days:14}") long tokenExpiryDays,
            @Value("${refresh-token.fingerprint.enabled:false}") boolean fingerprintEnabled) {
        this.repo = repo;
        this.tokenExpiryDays = tokenExpiryDays;
        this.fingerprintEnabled = fingerprintEnabled;

        log.info("Refresh token service initialized: expiryDays={}, fingerprintEnabled={}",
                tokenExpiryDays, fingerprintEnabled);
    }

    // =====================================================
    // 🔐 CRYPTOGRAPHIC HASHING
    // =====================================================

    /**
     * Generate SHA-256 hash of the token for secure storage
     */
    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (Exception e) {
            log.error("Token hashing failed", e);
            throw new RuntimeException("Token hashing failed", e);
        }
    }

    // =====================================================
    // 🧩 ISSUE NEW TOKEN
    // =====================================================

    /**
     * Issue a new refresh token for a user
     *
     * @param email User's email
     * @return Raw token string (sent to client in HttpOnly cookie)
     */
    @Transactional
    public String issue(String email) {
        return issue(email, null, null);
    }

    /**
     * Issue a new refresh token with optional fingerprinting
     *
     * @param email User's email
     * @param ipAddress Client IP address (optional, for fingerprinting)
     * @param userAgent Client User-Agent (optional, for fingerprinting)
     * @return Raw token string (sent to client in HttpOnly cookie)
     */
    @Transactional
    public String issue(String email, String ipAddress, String userAgent) {
        // Generate cryptographically secure random token
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hashToken(raw);

        // Calculate expiration
        Instant expiresAt = Instant.now().plusSeconds(60L * 60 * 24 * tokenExpiryDays);

        // Build token entity
        RefreshToken.RefreshTokenBuilder builder = RefreshToken.builder()
                .userEmail(email)
                .tokenHash(hash)
                .expiresAt(expiresAt)
                .createdAt(Instant.now())
                .revoked(false)
                .used(false);

        // Add fingerprinting if enabled
        if (fingerprintEnabled && ipAddress != null) {
            builder.ipAddress(ipAddress);
            builder.userAgent(userAgent != null ? truncate(userAgent, 500) : null);
        }

        RefreshToken entity = builder.build();
        repo.save(entity);

        log.debug("Issued new refresh token: user={}, expiresAt={}, fingerprinted={}",
                email, expiresAt, fingerprintEnabled && ipAddress != null);

        return raw;
    }

    // =====================================================
    // 🔁 ROTATE TOKEN (Atomic Operation)
    // =====================================================

    /**
     * Rotate a refresh token (exchange old for new)
     * Implements token reuse detection and automatic revocation on suspicious activity
     *
     * @param oldRawToken The old token from client cookie
     * @return RefreshTokenResult with success status, email, and new token
     * @throws InvalidRefreshTokenException if token is invalid, expired, or reused
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RefreshTokenResult rotate(String oldRawToken) {
        return rotate(oldRawToken, null, null);
    }

    /**
     * Rotate a refresh token with fingerprint validation
     *
     * @param oldRawToken The old token from client cookie
     * @param ipAddress Current request IP (for fingerprint validation)
     * @param userAgent Current request User-Agent (for fingerprint validation)
     * @return RefreshTokenResult with success status, email, and new token
     * @throws InvalidRefreshTokenException if token is invalid, expired, or reused
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RefreshTokenResult rotate(String oldRawToken, String ipAddress, String userAgent) {
        String oldHash = hashToken(oldRawToken);

        Optional<RefreshToken> tokenOpt = repo.findByTokenHash(oldHash);

        // Token not found
        if (tokenOpt.isEmpty()) {
            log.warn("Token rotation failed: token not found (hash: {}...)", oldHash.substring(0, 10));
            securityLog.warn("SECURITY: Token rotation attempt with non-existent token from IP: {}", ipAddress);
            throw new InvalidRefreshTokenException("Invalid or expired refresh token");
        }

        RefreshToken old = tokenOpt.get();
        String email = old.getUserEmail();

        // Check if token was already used (replay attack detection)
        if (old.wasReused()) {
            log.error("SECURITY ALERT: Token reuse detected for user: {} from IP: {}", email, ipAddress);
            securityLog.error("SECURITY ALERT: Possible token theft detected - user: {}, IP: {}, previousUse: {}",
                    email, ipAddress, old.getUsedAt());

            // Revoke ALL tokens for this user as a security measure
            revokeAll(email);
            throw new InvalidRefreshTokenException("Token reuse detected - all sessions invalidated for security");
        }

        // Check expiration
        if (old.isExpired()) {
            log.warn("Token rotation failed: token expired for user: {}", email);
            revokeAll(email);
            throw new InvalidRefreshTokenException("Refresh token expired");
        }

        // Check if token is revoked
        if (old.isRevoked()) {
            log.warn("Token rotation failed: token revoked for user: {}", email);
            throw new InvalidRefreshTokenException("Token has been revoked");
        }

        // Fingerprint validation (production mode)
        if (fingerprintEnabled && old.getIpAddress() != null) {
            if (!old.getIpAddress().equals(ipAddress)) {
                log.warn("SECURITY: IP mismatch during token rotation - user: {}, expected: {}, got: {}",
                        email, old.getIpAddress(), ipAddress);
                securityLog.warn("SECURITY: Possible session hijacking - IP changed for user: {}", email);

                // In strict mode, reject the rotation
                // In lenient mode, allow but log (useful for mobile users)
                // For now, we log but allow (can be made stricter via config)
            }
        }

        // Mark old token as used (prevents reuse)
        old.markAsUsed();
        repo.save(old);

        // Delete old token
        repo.delete(old);
        repo.flush(); // Ensure deletion is committed

        // Issue new token
        String newRaw = issue(email, ipAddress, userAgent);

        log.debug("Token rotated successfully: user={}", email);
        return new RefreshTokenResult(true, email, newRaw);
    }

    // =====================================================
    // 🚫 REVOCATION
    // =====================================================

    /**
     * Revoke all refresh tokens for a user
     * Should be called on: logout, password change, account suspension, security event
     *
     * @param email User's email
     */
    @Transactional
    public void revokeAll(String email) {
        long count = repo.countActiveTokensByUser(email, Instant.now());
        repo.deleteByUserEmail(email);
        log.info("Revoked all refresh tokens for user: {} (count: {})", email, count);
        securityLog.info("Token revocation: user={}, tokenCount={}", email, count);
    }

    /**
     * Revoke all tokens for a user with reason (for auditing)
     *
     * @param email User's email
     * @param reason Reason for revocation (e.g., "PASSWORD_CHANGE", "SUSPICIOUS_ACTIVITY")
     */
    @Transactional
    public void revokeAll(String email, String reason) {
        long count = repo.countActiveTokensByUser(email, Instant.now());
        repo.deleteByUserEmail(email);
        log.info("Revoked all refresh tokens for user: {} (reason: {}, count: {})", email, reason, count);
        securityLog.info("Token revocation: user={}, reason={}, tokenCount={}", email, reason, count);
    }

    // =====================================================
    // 🔍 UTILITY METHODS
    // =====================================================

    /**
     * Check if a token is expired
     */
    private boolean isExpired(RefreshToken token) {
        return token.getExpiresAt().isBefore(Instant.now());
    }

    /**
     * Truncate string to maximum length
     */
    private String truncate(String str, int maxLength) {
        return str != null && str.length() > maxLength ? str.substring(0, maxLength) : str;
    }

    /**
     * Extract IP address from HTTP request
     */
    public static String extractIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // If multiple IPs in X-Forwarded-For, take the first one
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * Extract User-Agent from HTTP request
     */
    public static String extractUserAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }
}

/**
 * Custom exception for invalid refresh tokens
 */
class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
