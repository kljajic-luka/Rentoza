package org.example.rentoza.security.password;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for password history records.
 *
 * @since Phase 3 - Security Hardening
 */
@Repository
public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {

    /**
     * Get the N most recent password hashes for a user, ordered newest first.
     */
    @Query("SELECT ph FROM PasswordHistory ph WHERE ph.userId = :userId ORDER BY ph.createdAt DESC")
    List<PasswordHistory> findTopNByUserIdOrderByCreatedAtDesc(
            @Param("userId") Long userId,
            org.springframework.data.domain.Pageable pageable
    );

    /**
     * Get the last N password hashes for reuse checking.
     */
    default List<PasswordHistory> findLastNPasswords(Long userId, int count) {
        return findTopNByUserIdOrderByCreatedAtDesc(userId,
                org.springframework.data.domain.PageRequest.of(0, count));
    }

    /**
     * Delete all password history for a user (GDPR erasure).
     */
    void deleteByUserId(Long userId);
}
