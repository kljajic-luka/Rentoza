package org.example.rentoza.auth;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Service
public class RefreshTokenService {

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
    public String issue(String email) {
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        String hash = hashToken(raw);
        RefreshToken entity = new RefreshToken(
                null,
                email,
                hash,
                Instant.now().plusSeconds(60L * 60 * 24 * 14),
                false,
                null
        );
        repo.save(entity);
        return raw; // return the raw token (stored only client-side in cookie)
    }

    // =====================================================
    // 🔁 ROTATE TOKEN
    // =====================================================
    public RefreshTokenResult rotate(String oldRawToken) {
        String oldHash = hashToken(oldRawToken);

        Optional<RefreshToken> tokenOpt = repo.findByTokenHash(oldHash);
        if (tokenOpt.isEmpty()) {
            return new RefreshTokenResult(false, null, null);
        }

        RefreshToken old = tokenOpt.get();
        if (isExpired(old)) {
            revokeAll(old.getUserEmail());
            return new RefreshTokenResult(false, old.getUserEmail(), null);
        }

        // Delete old token to enforce rotation
        repo.delete(old);

        // Issue new token
        String newRaw = issue(old.getUserEmail());
        return new RefreshTokenResult(true, old.getUserEmail(), newRaw);
    }

    // =====================================================
    // 🚫 REVOKE ALL FOR USER
    // =====================================================
    public void revokeAll(String email) {
        repo.deleteByUserEmail(email);
    }

    private boolean isExpired(RefreshToken token) {
        return token.getExpiresAt().isBefore(Instant.now());
    }
}