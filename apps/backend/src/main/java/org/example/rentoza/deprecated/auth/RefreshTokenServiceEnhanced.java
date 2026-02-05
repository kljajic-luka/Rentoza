package org.example.rentoza.deprecated.auth;

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
@Deprecated(since = "2.1.0", forRemoval = true)
@Service
public class RefreshTokenServiceEnhanced {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenServiceEnhanced.class);
    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY_AUDIT");
    private static final int TOKEN_BYTES = 64; // 512 bits
    
    /**
     * Grace period (in seconds) for token reuse after rotation.
     * This handles race conditions where concurrent requests may use the same token
     * before the new cookie is synced to the browser.
     * 
     * Set to 60 seconds to handle slow networks and browser cookie sync delays.
     */
    private static final int TOKEN_ROTATION_GRACE_PERIOD_SECONDS = 60;

    private final RefreshTokenRepository repo;
    private final SecureRandom random = new SecureRandom();
    private final long tokenExpiryDays;
    private final boolean fingerprintEnabled;
    private final int maxActiveSessions;

    public RefreshTokenServiceEnhanced(
            RefreshTokenRepository repo,
            @Value("${refresh-token.expiration-days:14}") long tokenExpiryDays,
            @Value("${refresh-token.fingerprint.enabled:false}") boolean fingerprintEnabled,
            @Value("${app.auth.max-sessions:5}") int maxActiveSessions) {
        this.repo = repo;
        this.tokenExpiryDays = tokenExpiryDays;
        this.fingerprintEnabled = fingerprintEnabled;
        this.maxActiveSessions = maxActiveSessions;

        log.info("Refresh token service initialized: expiryDays={}, fingerprintEnabled={}, maxActiveSessions={}, gracePeriodSeconds={}",
                tokenExpiryDays, fingerprintEnabled, maxActiveSessions, TOKEN_ROTATION_GRACE_PERIOD_SECONDS);
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
        return issue(email, ipAddress, userAgent, null);
    }
    
    /**
     * Issue a new refresh token with optional fingerprinting and token lineage.
     *
     * SECURITY HARDENING (Phase 2):
     * - Session Capping: Limits active tokens per user to MAX_ACTIVE_SESSIONS
     * - Token Lineage: Tracks previousTokenHash for chain-of-custody detection
     *
     * @param email User's email
     * @param ipAddress Client IP address (optional, for fingerprinting)
     * @param userAgent Client User-Agent (optional, for fingerprinting)
     * @param previousTokenHash Hash of the parent token (for rotation lineage)
     * @return Raw token string (sent to client in HttpOnly cookie)
     */
    @Transactional
    public String issue(String email, String ipAddress, String userAgent, String previousTokenHash) {
        // PHASE 2: SESSION CAPPING
        // Enforce maximum active sessions per user
        enforceSessionCap(email);
        
        // Generate cryptographically secure random token
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hashToken(raw);

        // Calculate expiration
        Instant expiresAt = Instant.now().plusSeconds(60L * 60 * 24 * tokenExpiryDays);

        // Build token entity with lineage tracking
        RefreshToken.RefreshTokenBuilder builder = RefreshToken.builder()
                .userEmail(email)
                .tokenHash(hash)
                .expiresAt(expiresAt)
                .createdAt(Instant.now())
                .revoked(false)
                .used(false)
                .previousTokenHash(previousTokenHash);  // PHASE 2: Token Lineage

        // Add fingerprinting if enabled
        if (fingerprintEnabled && ipAddress != null) {
            builder.ipAddress(ipAddress);
            builder.userAgent(userAgent != null ? truncate(userAgent, 500) : null);
        }

        RefreshToken entity = builder.build();
        repo.save(entity);

        log.debug("Issued new refresh token: user={}, expiresAt={}, fingerprinted={}, hasLineage={}",
                email, expiresAt, fingerprintEnabled && ipAddress != null, previousTokenHash != null);

        return raw;
    }
    
    /**
     * Enforce session cap by removing oldest tokens if limit exceeded.
     * 
     * SECURITY HARDENING (Phase 2):
     * - Prevents "Zombie Sessions" and database bloat
     * - Ensures user cannot have unlimited active sessions
     * - Removes oldest token first (LRU eviction)
     * - Handles race conditions gracefully when multiple logins occur simultaneously
     *
     * @param email User's email
     */
    private void enforceSessionCap(String email) {
        Instant now = Instant.now();
        long activeCount = repo.countActiveTokensByUser(email, now);
        
        if (activeCount >= maxActiveSessions) {
            // Find and delete oldest tokens to make room
            int tokensToRemove = (int) (activeCount - maxActiveSessions + 1);
            
            repo.findOldestActiveTokensByUser(email, now, tokensToRemove)
                    .forEach(token -> {
                        try {
                            log.info("SESSION_CAP: Evicting oldest token for user={}, tokenId={}, createdAt={}",
                                    email, token.getId(), token.getCreatedAt());
                            securityLog.info("SESSION_CAP: Evicted token - user={}, reason=MAX_SESSIONS_EXCEEDED", email);
                            repo.delete(token);
                        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                            // Token was already deleted by a concurrent transaction - this is fine
                            log.debug("SESSION_CAP: Token {} already evicted by concurrent transaction (race condition handled)", 
                                    token.getId());
                        } catch (org.springframework.dao.DataIntegrityViolationException e) {
                            // Token was already deleted - handle gracefully
                            log.debug("SESSION_CAP: Token {} no longer exists (concurrent deletion)", token.getId());
                        }
                    });
            
            try {
                repo.flush();
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                // Flush failed due to concurrent modifications - this is acceptable
                log.debug("SESSION_CAP: Flush skipped due to concurrent token eviction");
            }
        }
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
     * SECURITY HARDENING (Phase 2):
     * - Token Lineage: New token stores hash of parent token
     * - Theft Chain Detection: Reused token triggers cascade revocation
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
            
            // PHASE 2: THEFT CHAIN DETECTION
            // Check if this token was previously rotated (it might be a stolen token)
            detectTheftChain(oldHash, ipAddress);
            
            throw new InvalidRefreshTokenException("Invalid or expired refresh token");
        }

        RefreshToken old = tokenOpt.get();
        String email = old.getUserEmail();

        // Check if token was already used (replay attack detection)
        // GRACE PERIOD: Allow reuse within grace period for race conditions
        if (old.wasReused()) {
            Instant usedAt = old.getUsedAt();
            Instant graceDeadline = Instant.now().minusSeconds(TOKEN_ROTATION_GRACE_PERIOD_SECONDS);
            
            if (usedAt != null && usedAt.isBefore(graceDeadline)) {
                // Token was used more than GRACE_PERIOD ago - this IS suspicious
                log.error("SECURITY ALERT: Token reuse detected for user: {} from IP: {} (used {}s ago)", 
                        email, ipAddress, java.time.Duration.between(usedAt, Instant.now()).getSeconds());
                securityLog.error("SECURITY ALERT: Possible token theft detected - user: {}, IP: {}, previousUse: {}",
                        email, ipAddress, usedAt);

                // PHASE 2: CASCADE REVOCATION
                // Revoke ALL tokens for this user AND any child tokens in the lineage
                revokeAllWithLineage(email, oldHash);
                throw new InvalidRefreshTokenException("Token reuse detected - all sessions invalidated for security");
            } else {
                // Token was used very recently - race condition, not theft
                // Just reject this request but don't revoke all tokens
                log.info("Token reuse within grace period ({}s) for user: {} - treating as race condition",
                        TOKEN_ROTATION_GRACE_PERIOD_SECONDS, email);
                throw new InvalidRefreshTokenException("Token already rotated - please retry with new token");
            }
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

        // PHASE 2: TOKEN LINEAGE - Issue new token with parent hash
        // This creates a chain: Token A -> Token B -> Token C
        // If Token A is reused after Token B exists, we can detect the theft
        String newRaw = issue(email, ipAddress, userAgent, oldHash);

        log.debug("Token rotated successfully: user={}, lineage={}", email, oldHash.substring(0, 10));
        return new RefreshTokenResult(true, email, newRaw);
    }
    
    /**
     * Detect theft chain by checking if a token was previously rotated.
     * 
     * SECURITY HARDENING (Phase 2):
     * If someone presents Token A after it was already rotated to Token B,
     * this is a strong indicator of token theft. We find Token B (which has
     * Token A as its previousTokenHash) and revoke the entire user's session.
     *
     * GRACE PERIOD (Race Condition Fix):
     * To handle legitimate race conditions where concurrent requests use the same
     * token before cookie sync completes, we only trigger theft detection if the
     * child token was created more than GRACE_PERIOD seconds ago.
     *
     * @param stolenTokenHash Hash of the potentially stolen token
     * @param ipAddress IP address of the attacker
     */
    private void detectTheftChain(String stolenTokenHash, String ipAddress) {
        Optional<RefreshToken> childToken = repo.findByPreviousTokenHash(stolenTokenHash);
        
        if (childToken.isPresent()) {
            RefreshToken child = childToken.get();
            String email = child.getUserEmail();
            
            // GRACE PERIOD CHECK: Only treat as theft if the rotation happened
            // more than GRACE_PERIOD seconds ago
            Instant graceDeadline = Instant.now().minusSeconds(TOKEN_ROTATION_GRACE_PERIOD_SECONDS);
            
            if (child.getCreatedAt().isBefore(graceDeadline)) {
                // Token was rotated a while ago - this IS suspicious
                log.error("SECURITY ALERT: THEFT CHAIN DETECTED! Token was already rotated ({}s ago).",
                        java.time.Duration.between(child.getCreatedAt(), Instant.now()).getSeconds());
                log.error("SECURITY ALERT: user={}, attackerIP={}, childTokenCreated={}", 
                        email, ipAddress, child.getCreatedAt());
                securityLog.error("THEFT_CHAIN_DETECTED: user={}, attackerIP={}, stolenTokenHash={}...", 
                        email, ipAddress, stolenTokenHash.substring(0, 10));
                
                // Revoke ALL tokens for this user - the attacker has the old token
                revokeAll(email, "THEFT_CHAIN_DETECTED");
            } else {
                // Token was rotated very recently - likely a race condition, not theft
                log.info("Token reuse within grace period ({}s) - treating as race condition, not theft. user={}, IP={}",
                        TOKEN_ROTATION_GRACE_PERIOD_SECONDS, email, ipAddress);
                
                // Return the CURRENT valid token info so the frontend can retry
                // Actually, we should just let the request fail with 401 and 
                // let the frontend use the new cookie it should have received
                // Don't revoke anything - this is likely a legitimate race condition
            }
        }
    }
    
    /**
     * Revoke all tokens for a user, including any in the theft chain lineage.
     * 
     * @param email User's email
     * @param compromisedTokenHash Hash of the token that was reused
     */
    private void revokeAllWithLineage(String email, String compromisedTokenHash) {
        // First, find and log any child tokens (for forensics)
        Optional<RefreshToken> childToken = repo.findByPreviousTokenHash(compromisedTokenHash);
        if (childToken.isPresent()) {
            log.error("THEFT_FORENSICS: Found child token in theft chain - childId={}, createdAt={}",
                    childToken.get().getId(), childToken.get().getCreatedAt());
        }
        
        // Revoke all tokens for this user
        revokeAll(email, "TOKEN_REUSE_CASCADE");
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


