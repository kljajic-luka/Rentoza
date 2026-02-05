package org.example.rentoza.user.gdpr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for user consent records.
 */
@Repository
public interface ConsentRepository extends JpaRepository<UserConsent, Long> {
    
    /**
     * Find all consent records for a user, newest first.
     */
    List<UserConsent> findByUserIdOrderByTimestampDesc(Long userId);
    
    /**
     * Find latest consent record for each consent type.
     */
    @Query("""
        SELECT c FROM UserConsent c 
        WHERE c.userId = :userId 
        AND c.timestamp = (
            SELECT MAX(c2.timestamp) FROM UserConsent c2 
            WHERE c2.userId = c.userId AND c2.consentType = c.consentType
        )
        """)
    List<UserConsent> findLatestByUserId(Long userId);
    
    /**
     * Delete all consent records for a user (GDPR erasure).
     */
    @Modifying
    @Query("DELETE FROM UserConsent c WHERE c.userId = :userId")
    void deleteByUserId(Long userId);
}
