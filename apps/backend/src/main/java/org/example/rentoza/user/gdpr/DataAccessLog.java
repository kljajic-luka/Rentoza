package org.example.rentoza.user.gdpr;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Persisted audit log entry tracking access to user PII data.
 *
 * <p>Implements GDPR Article 15 Right of Access — users can view
 * a factual record of who accessed their data and when.
 *
 * <p>Remediates GAP-5 from production readiness audit: replaces the
 * previous hardcoded stub that returned fabricated sample data.
 *
 * <p>Entries are written by:
 * <ul>
 *   <li>Admin user profile views (AdminUserController)</li>
 *   <li>GDPR data exports</li>
 *   <li>Verification document access</li>
 *   <li>System-initiated data processing</li>
 * </ul>
 *
 * @see DataAccessLogRepository
 * @see GdprService#getDataAccessLog
 */
@Entity
@Table(
        name = "data_access_logs",
        indexes = {
                @Index(name = "idx_data_access_user_id", columnList = "user_id"),
                @Index(name = "idx_data_access_timestamp", columnList = "timestamp"),
                @Index(name = "idx_data_access_user_timestamp", columnList = "user_id, timestamp")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class DataAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The user whose data was accessed.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Who accessed the data (null for system/automated access).
     */
    @Column(name = "accessor_id")
    private Long accessorId;

    /**
     * Type of accessor: USER, ADMIN, SYSTEM.
     */
    @Column(name = "accessor_type", nullable = false, length = 20)
    private String accessorType;

    /**
     * Action performed (e.g., VIEW_PROFILE, EXPORT_DATA, VIEW_DOCUMENT).
     */
    @Column(name = "action", nullable = false, length = 50)
    private String action;

    /**
     * Human-readable description of the access event.
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * Source application or interface (e.g., Web App, Admin Panel, API).
     */
    @Column(name = "source", length = 50)
    private String source;

    /**
     * IP address of the accessor (nullable for system operations).
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    /**
     * Immutability guard — data access log entries must not be modified after creation.
     */
    @PreUpdate
    protected void onPreUpdate() {
        throw new UnsupportedOperationException("Data access log entries are immutable");
    }

    @PreRemove
    protected void onPreRemove() {
        throw new UnsupportedOperationException("Data access log entries cannot be deleted");
    }

    /**
     * Factory method for creating an access log entry.
     */
    public static DataAccessLog of(Long userId, Long accessorId, String accessorType,
                                    String action, String description, String source,
                                    String ipAddress) {
        DataAccessLog entry = new DataAccessLog();
        entry.setUserId(userId);
        entry.setAccessorId(accessorId);
        entry.setAccessorType(accessorType);
        entry.setAction(action);
        entry.setDescription(description);
        entry.setSource(source);
        entry.setIpAddress(ipAddress);
        entry.setTimestamp(LocalDateTime.now());
        return entry;
    }
}
