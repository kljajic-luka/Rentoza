package org.example.rentoza.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Transactional
    @Modifying
    void deleteByUserEmail(String email);

    /**
     * Find all tokens that have expired
     */
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.expiresAt < :now")
    List<RefreshToken> findAllExpired(Instant now);

    /**
     * Delete all expired tokens (bulk operation for cleanup)
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :now")
    int deleteAllExpired(Instant now);

    /**
     * Count active tokens for a user
     */
    @Query("SELECT COUNT(rt) FROM RefreshToken rt WHERE rt.userEmail = :email AND rt.expiresAt > :now")
    long countActiveTokensByUser(String email, Instant now);

    /**
     * Find all tokens for a user (for admin/debugging purposes)
     */
    List<RefreshToken> findAllByUserEmail(String email);
}