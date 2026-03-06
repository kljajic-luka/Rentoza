package org.example.rentoza.payment;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Idempotent store for incoming provider webhook events.
 *
 * <p>Before processing any webhook, look up {@code providerEventId}.
 * If already present the event is a duplicate — skip processing and
 * return 200 immediately (acknowledge to stop provider retries).
 *
 * <p>Raw payload is stored as JSONB for audit and replay.
 */
@Entity
@Table(
    name = "provider_events",
    indexes = {
        @Index(name = "idx_pe_provider_event_id", columnList = "provider_event_id", unique = true),
        @Index(name = "idx_pe_booking_id",        columnList = "booking_id"),
        @Index(name = "idx_pe_processed_at",      columnList = "processed_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProviderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Provider-assigned globally unique event identifier.
     * Used as the deduplication key — must be UNIQUE in DB.
     */
    @Column(name = "provider_event_id", nullable = false, length = 150, unique = true)
    private String providerEventId;

    @Column(name = "provider_name", length = 50, nullable = false)
    @Builder.Default
    private String providerName = "monri";

    /** Associated booking, if determinable from the payload. */
    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "provider_authorization_id", length = 150)
    private String providerAuthorizationId;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    /** Full raw webhook payload stored as JSONB for audit and replay. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    /** Signature header value received (stored for audit). */
    @Column(name = "signature_header", length = 500)
    private String signatureHeader;

    /** Whether the HMAC signature was verified against the shared secret. */
    @Column(name = "signature_verified", nullable = false)
    @Builder.Default
    private boolean signatureVerified = false;

    /**
     * Whether the event has been fully processed.
     * {@code null} means not yet processed (or still in-flight).
     */
    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "processing_error", length = 500)
    private String processingError;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
