package org.example.rentoza.booking.checkin;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Tracks discrepancies between host and guest photos during check-in/checkout.
 * 
 * <p>When both parties capture photos of the same vehicle angle/component,
 * this entity records any detected differences for dispute resolution.
 * 
 * <h2>Detection Methods</h2>
 * <ul>
 *   <li><b>Manual Review:</b> Admin compares photos side-by-side</li>
 *   <li><b>AI Detection:</b> Future enhancement - automated damage detection</li>
 *   <li><b>OCR Discrepancy:</b> Odometer/fuel readings differ significantly</li>
 * </ul>
 * 
 * <h2>Workflow</h2>
 * <ol>
 *   <li>Discrepancy detected (manual or automated)</li>
 *   <li>Flagged for review with severity level</li>
 *   <li>Admin reviews and resolves</li>
 *   <li>Resolution affects dispute outcome</li>
 * </ol>
 * 
 * <h2>Severity Levels</h2>
 * <ul>
 *   <li>{@code LOW}: Minor cosmetic difference (e.g., lighting, angle)</li>
 *   <li>{@code MEDIUM}: Notable difference requiring review</li>
 *   <li>{@code HIGH}: Significant damage or condition change</li>
 *   <li>{@code CRITICAL}: Major damage, potential fraud</li>
 * </ul>
 *
 * @see CheckInPhoto
 * @see GuestCheckInPhoto
 */
@Entity
@Table(name = "photo_discrepancies", indexes = {
    @Index(name = "idx_discrepancy_booking", columnList = "booking_id"),
    @Index(name = "idx_discrepancy_status", columnList = "resolution_status"),
    @Index(name = "idx_discrepancy_severity", columnList = "severity, resolution_status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhotoDiscrepancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Reference to the parent booking.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /**
     * Type of discrepancy context (CHECK_IN or CHECK_OUT).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false)
    private DiscrepancyType discrepancyType;

    /**
     * Reference to host's photo (null if host didn't capture this angle).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_photo_id")
    private CheckInPhoto hostPhoto;

    /**
     * Reference to guest's photo ID (null if guest didn't capture this angle).
     * <p>Note: This references guest_check_in_photos table, but we store
     * just the ID to avoid circular dependencies. Use service layer to fetch.
     */
    @Column(name = "guest_photo_id")
    private Long guestPhotoId;

    /**
     * Photo type where discrepancy was detected.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "photo_type", nullable = false)
    private CheckInPhotoType photoType;

    /**
     * Human-readable description of the discrepancy.
     */
    @Column(name = "description", length = 1000, nullable = false)
    private String description;

    /**
     * Severity level for prioritization.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    @Builder.Default
    private Severity severity = Severity.LOW;

    // ========== AI DETECTION (Future Enhancement) ==========

    /**
     * AI confidence score for automated detection (0.00 - 1.00).
     * <p>Null for manual detections.
     */
    @Column(name = "ai_confidence_score", precision = 3, scale = 2)
    private BigDecimal aiConfidenceScore;

    /**
     * Detailed AI detection results as JSON.
     * <p>Structure varies by detection algorithm.
     * Example: {"algorithm": "damage_detection_v2", "bounding_boxes": [...], "labels": [...]}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_detection_details", columnDefinition = "JSON")
    private Map<String, Object> aiDetectionDetails;

    // ========== RESOLUTION ==========

    /**
     * Current resolution status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false)
    @Builder.Default
    private ResolutionStatus resolutionStatus = ResolutionStatus.PENDING;

    /**
     * Admin who resolved this discrepancy.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    /**
     * When the discrepancy was resolved.
     */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * Resolution notes (required for DISMISSED and ESCALATED).
     */
    @Column(name = "resolution_notes", length = 1000)
    private String resolutionNotes;

    // ========== AUDIT ==========

    /**
     * When this discrepancy was created.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ========== ENUMS ==========

    /**
     * Context for when the discrepancy occurred.
     */
    public enum DiscrepancyType {
        /** Discrepancy between host and guest photos at check-in (pickup) */
        CHECK_IN,
        
        /** Discrepancy between guest and host photos at checkout (return) */
        CHECK_OUT
    }

    /**
     * Severity level for prioritization and escalation.
     */
    public enum Severity {
        /**
         * Minor cosmetic difference.
         * Examples: Different lighting, slight angle variation.
         * Action: No action required, informational only.
         */
        LOW,
        
        /**
         * Notable difference requiring review.
         * Examples: Different cleanliness, minor scratch visible in one photo.
         * Action: Admin review before handover completion.
         */
        MEDIUM,
        
        /**
         * Significant damage or condition change.
         * Examples: New dent, broken mirror, tire damage.
         * Action: Hold handover, require acknowledgment from both parties.
         */
        HIGH,
        
        /**
         * Major damage or potential fraud.
         * Examples: Different vehicle, significant undisclosed damage.
         * Action: Escalate to supervisor, block handover.
         */
        CRITICAL
    }

    /**
     * Resolution workflow status.
     */
    public enum ResolutionStatus {
        /** Awaiting admin review */
        PENDING,
        
        /** Admin reviewed and confirmed the discrepancy */
        REVIEWED,
        
        /** Admin dismissed (false positive, lighting, etc.) */
        DISMISSED,
        
        /** Escalated to supervisor or dispute team */
        ESCALATED,
        
        /** Resolved with damage claim filed */
        CLAIM_FILED,
        
        /** Auto-resolved (e.g., both parties acknowledged) */
        AUTO_RESOLVED
    }

    // ========== HELPER METHODS ==========

    /**
     * Check if this discrepancy requires immediate attention.
     */
    public boolean requiresImmediateAttention() {
        return severity == Severity.HIGH || severity == Severity.CRITICAL;
    }

    /**
     * Check if this discrepancy blocks the handover process.
     */
    public boolean blocksHandover() {
        return severity == Severity.CRITICAL && resolutionStatus == ResolutionStatus.PENDING;
    }

    /**
     * Check if this discrepancy has been resolved.
     */
    public boolean isResolved() {
        return resolutionStatus != ResolutionStatus.PENDING && 
               resolutionStatus != ResolutionStatus.ESCALATED;
    }

    /**
     * Resolve this discrepancy.
     */
    public void resolve(User admin, ResolutionStatus status, String notes) {
        this.resolvedBy = admin;
        this.resolvedAt = Instant.now();
        this.resolutionStatus = status;
        this.resolutionNotes = notes;
    }

    /**
     * Escalate this discrepancy.
     */
    public void escalate(User admin, String reason) {
        this.resolvedBy = admin;
        this.resolvedAt = Instant.now();
        this.resolutionStatus = ResolutionStatus.ESCALATED;
        this.resolutionNotes = reason;
    }

    /**
     * Check if this was detected by AI.
     */
    public boolean isAiDetected() {
        return aiConfidenceScore != null;
    }
}
