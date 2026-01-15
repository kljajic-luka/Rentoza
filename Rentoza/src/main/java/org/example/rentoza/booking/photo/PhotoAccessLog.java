package org.example.rentoza.booking.photo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * P2-9/P1 FIX: Audit log for all photo access events.
 * 
 * <p>Logs every photo view/download for:
 * <ul>
 *   <li>Compliance: GDPR "who accessed my data and when"</li>
 *   <li>Security: Detect unauthorized access attempts and mass scraping</li>
 *   <li>Disputes: Prove which party accessed photos when</li>
 * </ul>
 * 
 * <h2>Retention</h2>
 * Logs are retained for 7 years per booking retention policy.
 * Can be exported as part of GDPR data export.
 */
@Entity
@Table(name = "photo_access_logs", indexes = {
        @Index(name = "idx_photo_access_user", columnList = "user_id, accessed_at DESC"),
        @Index(name = "idx_photo_access_booking", columnList = "booking_id, accessed_at DESC"),
        @Index(name = "idx_photo_access_ip", columnList = "ip_address, accessed_at DESC")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhotoAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The user who accessed the photo.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The booking the photo belongs to.
     */
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /**
     * The photo ID accessed (if available, else null for batch operations).
     */
    @Column(name = "photo_id")
    private Long photoId;

    /**
     * Type of access: GET_SINGLE, LIST_PHOTOS, DOWNLOAD_ZIP, etc.
     */
    @Column(name = "access_type", length = 50, nullable = false)
    private String accessType;

    /**
     * HTTP status code of the response (200, 403, 404, 429, etc.).
     */
    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    /**
     * Why was the photo accessed? E.g., "VIEW", "DOWNLOAD", "PRINT", "SHARE".
     */
    @Column(name = "purpose", length = 100)
    private String purpose;

    /**
     * Client IP address for suspicious pattern detection.
     */
    @Column(name = "ip_address", length = 45)  // IPv6 addresses can be up to 45 chars
    private String ipAddress;

    /**
     * User agent / browser info.
     */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /**
     * Timestamp when access occurred.
     */
    @CreationTimestamp
    @Column(name = "accessed_at", nullable = false, updatable = false)
    private Instant accessedAt;

    /**
     * Additional context (e.g., "Viewed in photo comparison", "Downloaded for dispute").
     */
    @Column(name = "context", length = 500)
    private String context;

    /**
     * Whether access was granted (true) or denied (false).
     */
    @Column(name = "access_granted", nullable = false)
    private Boolean accessGranted;

    /**
     * If access was denied, why? (e.g., "NOT_PARTICIPANT", "RATE_LIMIT_EXCEEDED", "BOOKING_CLOSED").
     */
    @Column(name = "denial_reason", length = 255)
    private String denialReason;
}
