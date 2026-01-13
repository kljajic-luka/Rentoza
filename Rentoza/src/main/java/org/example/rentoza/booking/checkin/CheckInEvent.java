package org.example.rentoza.booking.checkin;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.rentoza.booking.Booking;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable audit log entry for check-in workflow events.
 * 
 * <p>This entity is <b>append-only</b> - rows cannot be updated or deleted.
 * Immutability is enforced at three levels:
 * <ul>
 *   <li><b>JPA:</b> {@code @Immutable} annotation prevents Hibernate updates</li>
 *   <li><b>Java:</b> No setters on persisted fields (except ID for JPA)</li>
 *   <li><b>Database:</b> Triggers prevent UPDATE and DELETE</li>
 * </ul>
 * 
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Insurance claims: "Who was holding the keys at 14:35?"</li>
 *   <li>Dispute resolution: "Did the guest acknowledge this damage?"</li>
 *   <li>Fraud detection: "How many check-ins were done from this device?"</li>
 *   <li>Compliance: "Show me all actions by this user in the last 30 days"</li>
 * </ul>
 * 
 * <h2>Regional Context</h2>
 * <p>Timestamps use {@link Instant} (UTC) but are displayed in Europe/Belgrade timezone.
 * The database stores TIMESTAMP(3) for millisecond precision.
 *
 * @see CheckInEventType
 * @see CheckInActorRole
 */
@Entity
@Table(name = "check_in_events", indexes = {
    @Index(name = "idx_checkin_event_session", columnList = "check_in_session_id, event_timestamp"),
    @Index(name = "idx_checkin_event_booking", columnList = "booking_id, event_type"),
    @Index(name = "idx_checkin_event_actor", columnList = "actor_id, event_type")
})
@Immutable // Hibernate: entity cannot be updated
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA requires no-arg constructor
public class CheckInEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Reference to the parent booking.
     * Cannot be null - orphan events are not allowed.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    private Booking booking;

    /**
     * UUID correlating all events for a single check-in session.
     * Generated when check-in window opens (T-24h).
     */
    @Column(name = "check_in_session_id", length = 36, nullable = false, updatable = false)
    private String checkInSessionId;

    /**
     * Type of event that occurred.
     * @see CheckInEventType for all possible values
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private CheckInEventType eventType;

    /**
     * User ID who triggered this event.
     * Use 0 for SYSTEM-triggered events (scheduler, background jobs).
     */
    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    /**
     * Role of the actor (HOST, GUEST, or SYSTEM).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, updatable = false)
    private CheckInActorRole actorRole;

    /**
     * Server-side timestamp when event was recorded.
     * TIMESTAMP(3) in MySQL for millisecond precision.
     * Stored as UTC, displayed as Europe/Belgrade.
     */
    @Column(name = "event_timestamp", nullable = false, updatable = false, columnDefinition = "TIMESTAMP(3)")
    private Instant eventTimestamp;

    /**
     * Client-side timestamp from device (may differ from server time).
     * Useful for offline sync scenarios and timezone debugging.
     */
    @Column(name = "client_timestamp", updatable = false, columnDefinition = "TIMESTAMP(3)")
    private Instant clientTimestamp;

    /**
     * Event-specific metadata as JSON.
     * 
     * <p>Examples:
     * <pre>
     * HOST_PHOTO_UPLOADED: {"photoId": 123, "photoType": "HOST_EXTERIOR_FRONT", "exifValid": true}
     * GUEST_ID_VERIFIED: {"livenessScore": 0.95, "nameMatchScore": 0.88}
     * TRIP_STARTED: {"handshakeMethod": "IN_PERSON", "geofenceStatus": "PASSED"}
     * </pre>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "TEXT", updatable = false)
    private Map<String, Object> metadata;

    /**
     * Client IP address (IPv4 or IPv6).
     * Used for fraud detection and audit.
     */
    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    /**
     * Client User-Agent header.
     * Identifies browser/app version for debugging.
     */
    @Column(name = "user_agent", length = 500, updatable = false)
    private String userAgent;

    /**
     * Optional device fingerprint for fraud detection.
     * Hash of device characteristics (not PII).
     */
    @Column(name = "device_fingerprint", length = 64, updatable = false)
    private String deviceFingerprint;

    // ========== FACTORY METHOD (Builder Pattern for Immutability) ==========

    /**
     * Create a new check-in event.
     * 
     * <p>This is the only way to create a CheckInEvent. The constructor is private
     * to enforce immutability - all fields must be set at creation time.
     *
     * @param booking          The parent booking
     * @param checkInSessionId UUID for the check-in session
     * @param eventType        Type of event
     * @param actorId          User ID (0 for SYSTEM)
     * @param actorRole        Role of the actor
     * @param clientTimestamp  Optional client-side timestamp
     * @param metadata         Event-specific data as JSON map
     * @param ipAddress        Client IP address
     * @param userAgent        Client User-Agent
     * @return New immutable CheckInEvent
     */
    public static CheckInEvent create(
            Booking booking,
            String checkInSessionId,
            CheckInEventType eventType,
            Long actorId,
            CheckInActorRole actorRole,
            Instant clientTimestamp,
            Map<String, Object> metadata,
            String ipAddress,
            String userAgent
    ) {
        return create(booking, checkInSessionId, eventType, actorId, actorRole,
                clientTimestamp, metadata, ipAddress, userAgent, null);
    }

    /**
     * Create a new check-in event with device fingerprint.
     */
    public static CheckInEvent create(
            Booking booking,
            String checkInSessionId,
            CheckInEventType eventType,
            Long actorId,
            CheckInActorRole actorRole,
            Instant clientTimestamp,
            Map<String, Object> metadata,
            String ipAddress,
            String userAgent,
            String deviceFingerprint
    ) {
        CheckInEvent event = new CheckInEvent();
        event.booking = booking;
        event.checkInSessionId = checkInSessionId;
        event.eventType = eventType;
        event.actorId = actorId;
        event.actorRole = actorRole;
        event.eventTimestamp = Instant.now();
        event.clientTimestamp = clientTimestamp;
        event.metadata = metadata;
        event.ipAddress = ipAddress;
        event.userAgent = userAgent;
        event.deviceFingerprint = deviceFingerprint;
        return event;
    }

    /**
     * Convenience factory for SYSTEM-triggered events.
     */
    public static CheckInEvent createSystemEvent(
            Booking booking,
            String checkInSessionId,
            CheckInEventType eventType,
            Map<String, Object> metadata
    ) {
        return create(booking, checkInSessionId, eventType, 0L, CheckInActorRole.SYSTEM,
                null, metadata, null, "SYSTEM");
    }

    // ========== HELPER METHODS ==========

    /**
     * Check if this event was triggered by the system (scheduler/background job).
     */
    public boolean isSystemEvent() {
        return actorRole == CheckInActorRole.SYSTEM || actorId == 0L;
    }

    /**
     * Check if this event was triggered by the host.
     */
    public boolean isHostEvent() {
        return actorRole == CheckInActorRole.HOST;
    }

    /**
     * Check if this event was triggered by the guest.
     */
    public boolean isGuestEvent() {
        return actorRole == CheckInActorRole.GUEST;
    }
}
