package org.example.rentoza.auth;

import org.springframework.data.domain.Pageable;
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
    @Query("SELECT COUNT(rt) FROM RefreshToken rt WHERE rt.userEmail = :email AND rt.expiresAt > :now AND rt.revoked = false")
    long countActiveTokensByUser(String email, Instant now);

    /**
     * Find all tokens for a user (for admin/debugging purposes)
     */
    List<RefreshToken> findAllByUserEmail(String email);
    
    // =====================================================
    // 🔐 PHASE 2: SESSION CAPPING & TOKEN LINEAGE
    // =====================================================
    
    /**
     * Find oldest active tokens for a user (for session capping LRU eviction).
     * 
     * SECURITY HARDENING (Phase 2):
     * Used to enforce MAX_ACTIVE_SESSIONS limit per user.
     * Returns oldest tokens first for LRU eviction.
     *
     * @param email User's email
     * @param now Current timestamp for expiration check
     * @param limit Maximum number of tokens to return
     * @return List of oldest active tokens
     */
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.userEmail = :email AND rt.expiresAt > :now AND rt.revoked = false ORDER BY rt.createdAt ASC")
    List<RefreshToken> findOldestActiveTokensByUser(String email, Instant now, Pageable pageable);
    
    /**
     * Convenience method to find oldest tokens with limit.
     */
    default List<RefreshToken> findOldestActiveTokensByUser(String email, Instant now, int limit) {
        return findOldestActiveTokensByUser(email, now, Pageable.ofSize(limit));
    }
    
    /**
     * Find token by its parent token hash (for theft chain detection).
     * 
     * SECURITY HARDENING (Phase 2):
     * If Token A was rotated to Token B, Token B.previousTokenHash = Token A.tokenHash.
     * When an attacker presents Token A again (after it was already rotated),
     * we find Token B and revoke the entire user's session.
     *
     * @param previousTokenHash Hash of the parent token
     * @return Child token if it exists
     */
    Optional<RefreshToken> findByPreviousTokenHash(String previousTokenHash);
    
    /**
     * Find all tokens in a theft chain (for forensic analysis).
     * 
     * @param email User's email
     * @return All tokens with lineage information
     */
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.userEmail = :email AND rt.previousTokenHash IS NOT NULL ORDER BY rt.createdAt DESC")
    List<RefreshToken> findAllWithLineageByUser(String email);
}