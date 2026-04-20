package org.example.rentoza.user.gdpr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for querying persisted data access audit logs.
 *
 * @see DataAccessLog
 */
@Repository
public interface DataAccessLogRepository extends JpaRepository<DataAccessLog, Long> {

    /**
     * Find access log entries for a user within a time window, newest first.
     *
     * @param userId the user whose data was accessed
     * @param since  earliest timestamp to include
     * @return access log entries ordered by timestamp descending
     */
    @Query("""
        SELECT d FROM DataAccessLog d
        WHERE d.userId = :userId AND d.timestamp >= :since
        ORDER BY d.timestamp DESC
        """)
    List<DataAccessLog> findByUserIdAndTimestampAfter(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since);
}
