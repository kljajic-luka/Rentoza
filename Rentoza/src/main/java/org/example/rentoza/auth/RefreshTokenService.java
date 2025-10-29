package org.example.rentoza.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Service for managing refresh token lifecycle: issuance, rotation, and revocation.
 * Uses SHA-256 hashing for secure token storage and enforces token rotation on refresh.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 64; // 512 bits
    private static final long TOKEN_EXPIRY_DAYS = 14;

    private final RefreshTokenRepository repo;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repo) {
        this.repo = repo;
    }

    // 🔐 Generate SHA-256 hash
    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("Token hashing failed", e);
        }
    }

    // =====================================================
    // 🧩 ISSUE NEW TOKEN
    // =====================================================
    @Transactional
    public String issue(String email) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        String hash = hashToken(raw);
        RefreshToken entity = new RefreshToken(
                null,
                email,
                hash,
                Instant.now().plusSeconds(60L * 60 * 24 * TOKEN_EXPIRY_DAYS),
                false,
                null
        );
        repo.save(entity);
        log.debug("Issued new refresh token for user: {}", email);
        return raw; // return the raw token (stored only client-side in cookie)
    }

    // =====================================================
    // 🔁 ROTATE TOKEN (atomic operation to prevent race conditions)
    // =====================================================
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RefreshTokenResult rotate(String oldRawToken) {
        String oldHash = hashToken(oldRawToken);

        Optional<RefreshToken> tokenOpt = repo.findByTokenHash(oldHash);
        if (tokenOpt.isEmpty()) {
            log.warn("Token rotation failed: token not found");
            throw new RuntimeException("Invalid or expired refresh token");
        }

        RefreshToken old = tokenOpt.get();
        if (isExpired(old)) {
            log.warn("Token rotation failed: token expired for user {}", old.getUserEmail());
            revokeAll(old.getUserEmail());
            throw new RuntimeException("Refresh token expired");
        }

        String email = old.getUserEmail();

        // Delete old token first
        repo.delete(old);
        repo.flush(); // Ensure deletion is committed before issuing new token

        // Issue new token
        String newRaw = issue(email);

        log.debug("Token rotated successfully for user: {}", email);
        return new RefreshTokenResult(true, email, newRaw);
    }

    // =====================================================
    // 🚫 REVOKE ALL FOR USER
    // =====================================================
    @Transactional
    public void revokeAll(String email) {
        repo.deleteByUserEmail(email);
        log.info("Revoked all refresh tokens for user: {}", email);
    }

    private boolean isExpired(RefreshToken token) {
        return token.getExpiresAt().isBefore(Instant.now());
    }
}