package org.example.rentoza.booking.dispute;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Damage claim entity for dispute resolution at check-in or checkout.
 * 
 * <p>Supports two dispute stages:
 * <ul>
 *   <li><b>CHECK_IN:</b> Guest reports pre-existing damage (VAL-004)</li>
 *   <li><b>CHECKOUT:</b> Host reports damage after trip</li>
 * </ul>
 * 
 * <p>Tracks the full lifecycle from claim to resolution.
 * 
 * @since VAL-004 - Extended for check-in disputes
 */
@Entity
@Table(name = "damage_claims", indexes = {
    @Index(name = "idx_damage_claim_booking", columnList = "booking_id"),
    @Index(name = "idx_damage_claim_status", columnList = "status"),
    @Index(name = "idx_damage_claim_host", columnList = "host_id"),
    @Index(name = "idx_damage_claim_guest", columnList = "guest_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DamageClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Changed from @OneToOne to @ManyToOne to allow multiple claims per booking
    // (e.g., check-in dispute + checkout claim on same booking)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", nullable = false)
    private User host;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guest_id", nullable = false)
    private User guest;
    
    // ========== DISPUTE STAGE & TYPE (VAL-004) ==========
    
    /**
     * Stage in booking lifecycle when dispute was raised.
     * CHECK_IN = pre-existing damage, CHECKOUT = damage during trip.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "dispute_stage", nullable = false)
    @Builder.Default
    private DisputeStage disputeStage = DisputeStage.CHECKOUT;
    
    /**
     * Category of dispute (PRE_EXISTING_DAMAGE, CHECKOUT_DAMAGE, etc.).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "dispute_type", nullable = false)
    @Builder.Default
    private DisputeType disputeType = DisputeType.CHECKOUT_DAMAGE;
    
    /**
     * User who initiated the claim.
     * Guest for check-in disputes, host for checkout claims.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_user_id")
    private User reportedBy;
    
    /**
     * Photo IDs guest flagged as showing undisclosed damage.
     * Only used for check-in disputes (DisputeStage.CHECK_IN).
     */
    @Column(name = "disputed_photo_ids", columnDefinition = "BIGINT[]")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.ARRAY)
    private List<Long> disputedPhotoIds;

    // ========== CLAIM DETAILS ==========

    @Column(name = "description", columnDefinition = "TEXT", nullable = false)
    private String description;

    @Column(name = "claimed_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal claimedAmount;

    /**
     * Final approved amount (may differ from claimed amount after review).
     */
    @Column(name = "approved_amount", precision = 19, scale = 2)
    private BigDecimal approvedAmount;

    /**
     * JSON array of check-in photo IDs for reference.
     */
    @Column(name = "checkin_photo_ids", columnDefinition = "TEXT")
    private String checkinPhotoIds;

    /**
     * JSON array of checkout photo IDs showing damage.
     */
    @Column(name = "checkout_photo_ids", columnDefinition = "TEXT")
    private String checkoutPhotoIds;

    /**
     * JSON array of additional damage evidence photo IDs.
     */
    @Column(name = "evidence_photo_ids", columnDefinition = "TEXT")
    private String evidencePhotoIds;

    // ========== STATUS ==========

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private DamageClaimStatus status = DamageClaimStatus.PENDING;

    /**
     * When guest must respond by (72h from creation).
     */
    @Column(name = "response_deadline")
    private Instant responseDeadline;

    // ========== GUEST RESPONSE ==========

    @Column(name = "guest_response", columnDefinition = "TEXT")
    private String guestResponse;

    @Column(name = "guest_responded_at")
    private Instant guestRespondedAt;

    // ========== ADMIN REVIEW ==========

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "admin_notes", columnDefinition = "TEXT")
    private String adminNotes;
    
    /**
     * Documented pre-existing damage items when resolved with PROCEED_WITH_DAMAGE_NOTED.
     * Guest liability is waived for these specific items.
     * 
     * @since VAL-004
     */
    @Column(name = "documented_damage", columnDefinition = "TEXT")
    private String documentedDamage;
    
    /**
     * Reason code when booking is cancelled due to dispute resolution.
     * E.g., "ADMIN_CANCELLED_UNDISCLOSED_DAMAGE"
     * 
     * @since VAL-004
     */
    @Column(name = "cancellation_reason", length = 100)
    private String cancellationReason;
    
    // ========== ESCALATION TRACKING (VAL-004 Phase 6) ==========
    
    /**
     * Whether dispute was escalated to senior admin due to timeout.
     */
    @Column(name = "escalated")
    @Builder.Default
    private Boolean escalated = false;
    
    /**
     * Timestamp when dispute was escalated to senior admin.
     */
    @Column(name = "escalated_at")
    private Instant escalatedAt;
    
    /**
     * Resolution notes from admin/system.
     */
    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;
    
    /**
     * When the dispute was resolved (admin action).
     */
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    
    /**
     * Admin who resolved the dispute.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_user_id")
    private User resolvedBy;

    // ========== PAYMENT ==========

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "paid_at")
    private Instant paidAt;

    // ========== TIMESTAMPS ==========

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    /**
     * Version field for optimistic locking.
     * Prevents concurrent updates causing data corruption.
     */
    @Version
    @Column(name = "version")
    private Long version;

    // ========== HELPER METHODS ==========

    /**
     * Check if guest can still respond.
     */
    public boolean canGuestRespond() {
        return status == DamageClaimStatus.PENDING
            && responseDeadline != null
            && Instant.now().isBefore(responseDeadline);
    }

    /**
     * Check if claim requires admin review.
     */
    public boolean needsAdminReview() {
        return status == DamageClaimStatus.DISPUTED || 
               status == DamageClaimStatus.CHECK_IN_DISPUTE_PENDING;
    }
    
    // ========== CHECK-IN DISPUTE METHODS (VAL-004) ==========
    
    /**
     * Check if this is a check-in stage dispute.
     */
    public boolean isCheckInDispute() {
        return disputeStage == DisputeStage.CHECK_IN;
    }
    
    /**
     * Resolve check-in dispute: Proceed with damage noted.
     * Documents pre-existing damage and waives guest liability.
     */
    public void resolveCheckInProceed(User admin, String notes, String documentedItems) {
        this.status = DamageClaimStatus.CHECK_IN_RESOLVED_PROCEED;
        this.resolvedBy = admin;
        this.resolvedAt = Instant.now();
        this.adminNotes = notes;
        this.documentedDamage = documentedItems;
    }
    
    /**
     * Resolve check-in dispute: Cancel booking.
     * Initiates full refund to guest.
     */
    public void resolveCheckInCancel(User admin, String notes, String reason) {
        this.status = DamageClaimStatus.CHECK_IN_RESOLVED_CANCEL;
        this.resolvedBy = admin;
        this.resolvedAt = Instant.now();
        this.adminNotes = notes;
        this.cancellationReason = reason;
    }
    
    /**
     * Decline check-in dispute (no undisclosed damage found).
     * Guest must accept condition or self-cancel.
     */
    public void declineCheckInDispute(User admin, String notes) {
        this.status = DamageClaimStatus.ADMIN_REJECTED;
        this.resolvedBy = admin;
        this.resolvedAt = Instant.now();
        this.adminNotes = notes;
    }
    
    /**
     * Guest withdrew their check-in dispute.
     */
    public void withdrawCheckInDispute() {
        this.status = DamageClaimStatus.CHECK_IN_GUEST_WITHDREW;
        this.resolvedAt = Instant.now();
    }

    /**
     * Mark claim as accepted by guest.
     */
    public void acceptByGuest(String response) {
        this.status = DamageClaimStatus.ACCEPTED_BY_GUEST;
        this.guestResponse = response;
        this.guestRespondedAt = Instant.now();
        this.approvedAmount = this.claimedAmount;
    }

    /**
     * Mark claim as disputed by guest.
     */
    public void disputeByGuest(String response) {
        this.status = DamageClaimStatus.DISPUTED;
        this.guestResponse = response;
        this.guestRespondedAt = Instant.now();
    }

    /**
     * Admin approves claim.
     */
    public void approveByAdmin(User admin, BigDecimal amount, String notes) {
        this.status = DamageClaimStatus.ADMIN_APPROVED;
        this.reviewedBy = admin;
        this.reviewedAt = Instant.now();
        this.approvedAmount = amount;
        this.adminNotes = notes;
    }

    /**
     * Admin rejects claim.
     */
    public void rejectByAdmin(User admin, String notes) {
        this.status = DamageClaimStatus.ADMIN_REJECTED;
        this.reviewedBy = admin;
        this.reviewedAt = Instant.now();
        this.adminNotes = notes;
    }

    /**
     * Record payment.
     */
    public void markPaid(String paymentRef) {
        this.status = DamageClaimStatus.PAID;
        this.paymentReference = paymentRef;
        this.paidAt = Instant.now();
    }
}


