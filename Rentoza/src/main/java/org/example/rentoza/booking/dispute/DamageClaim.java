package org.example.rentoza.booking.dispute;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Damage claim entity for post-trip dispute resolution.
 * 
 * <p>Created when a host reports damage during checkout.
 * Tracks the full lifecycle from claim to resolution.
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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", nullable = false)
    private User host;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guest_id", nullable = false)
    private User guest;

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
        return status == DamageClaimStatus.DISPUTED;
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

