package org.example.rentoza.security.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Repository for denied (blacklisted) JWT tokens.
 *
 * @since Phase 3 - Security Hardening
 */
@Repository
public interface DeniedTokenRepository extends JpaRepository<DeniedToken, Long> {

    /**
     * Check if a token hash is in the denylist.
     */
    boolean existsByTokenHash(String tokenHash);

    /**
     * Cleanup expired entries (original JWT has expired, so denylist entry is no longer needed).
     */
    @Modifying
    @Query("DELETE FROM DeniedToken d WHERE d.expiresAt < :cutoff")
    int deleteExpiredEntries(@Param("cutoff") Instant cutoff);
}
