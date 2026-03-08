package org.example.rentoza.booking;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable rental agreement evidence for a booking.
 *
 * <p>Each booking has exactly one rental agreement that captures:
 * <ul>
 *   <li>Vehicle and terms snapshots at agreement creation time</li>
 *   <li>Content hash (SHA-256) for tamper detection</li>
 *   <li>Acceptance evidence (timestamp, IP, user-agent) for both parties</li>
 * </ul>
 *
 * <p>Core fields (content_hash, snapshots, party IDs, booking_id) are immutable
 * at the database level via triggers (V78 migration).
 */
@Entity
@Table(name = "rental_agreements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RentalAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;

    @Column(name = "agreement_version", nullable = false, length = 20)
    private String agreementVersion;

    @Column(name = "agreement_type", nullable = false, length = 30)
    @Builder.Default
    private String agreementType = "STANDARD_RENTAL";

    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    // ── Owner acceptance evidence ────────────────────────────────────────────

    @Column(name = "owner_accepted_at")
    private Instant ownerAcceptedAt;

    @Column(name = "owner_ip", length = 45)
    private String ownerIp;

    @Column(name = "owner_user_agent", length = 500)
    private String ownerUserAgent;

    // ── Renter acceptance evidence ───────────────────────────────────────────

    @Column(name = "renter_accepted_at")
    private Instant renterAcceptedAt;

    @Column(name = "renter_ip", length = 45)
    private String renterIp;

    @Column(name = "renter_user_agent", length = 500)
    private String renterUserAgent;

    // ── Party references ─────────────────────────────────────────────────────

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "renter_user_id", nullable = false)
    private Long renterUserId;

    // ── Immutable snapshots ──────────────────────────────────────────────────

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vehicle_snapshot_json", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> vehicleSnapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "terms_snapshot_json", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> termsSnapshotJson;

    // ── Status ───────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RentalAgreementStatus status = RentalAgreementStatus.PENDING;

    @Column(name = "terms_template_id", length = 50)
    private String termsTemplateId;

    @Column(name = "terms_template_hash", length = 128)
    private String termsTemplateHash;

    // ── Timestamps ───────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Whether both parties have accepted this agreement.
     */
    public boolean isFullyAccepted() {
        return status == RentalAgreementStatus.FULLY_ACCEPTED;
    }
}
