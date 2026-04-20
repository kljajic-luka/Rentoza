package org.example.rentoza.security.password;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for password reset tokens.
 *
 * @since Phase 3 - Security Hardening
 */
@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /**
     * Find a valid (unused, non-expired) token by its hash.
     */
    @Query("SELECT t FROM PasswordResetToken t WHERE t.tokenHash = :tokenHash AND t.used = false AND t.expiresAt > :now")
    Optional<PasswordResetToken> findValidToken(
            @Param("tokenHash") String tokenHash,
            @Param("now") Instant now
    );

    /**
     * Invalidate all existing tokens for a user (called before creating a new one).
     */
    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.used = true, t.usedAt = :now WHERE t.userId = :userId AND t.used = false")
    int invalidateAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Cleanup expired tokens (scheduled maintenance).
     */
    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredTokens(@Param("cutoff") Instant cutoff);

    /**
     * Delete all tokens for a user (GDPR erasure).
     */
    void deleteByUserId(Long userId);
}
