package org.example.rentoza.booking.checkin;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Guest identity verification record for check-in workflow.
 * 
 * <p><b>IMPORTANT:</b> This table contains PII (Personally Identifiable Information).
 * Access is restricted to the check-in service only. Do not expose in general APIs.
 * 
 * <h2>Verification Steps</h2>
 * <ol>
 *   <li><b>Liveness Check:</b> Anti-spoofing biometric verification</li>
 *   <li><b>Document Upload:</b> Driver's license, passport, or national ID</li>
 *   <li><b>OCR Extraction:</b> Name, expiry date, document number</li>
 *   <li><b>Name Matching:</b> Compare extracted name to user profile (Serbian-aware)</li>
 *   <li><b>Expiry Check:</b> Document must be valid through trip end date</li>
 * </ol>
 * 
 * <h2>Serbian Name Handling</h2>
 * <p>Serbian names contain diacritics (Đ, Ž, Č, Ć, Š) that OCR may extract as
 * ASCII equivalents. The {@code extractedNameNormalized} and {@code profileNameNormalized}
 * fields store ASCII-normalized versions for matching:
 * <ul>
 *   <li>Đorđević → DJORDJEVIC</li>
 *   <li>Živković → ZIVKOVIC</li>
 *   <li>Čabrić → CABRIC</li>
 * </ul>
 *
 * @see IdVerificationStatus
 */
@Entity
@Table(name = "check_in_id_verifications", indexes = {
    @Index(name = "idx_id_verification_guest", columnList = "guest_id"),
    @Index(name = "idx_id_verification_status", columnList = "verification_status"),
    @Index(name = "idx_id_verification_session", columnList = "check_in_session_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_id_verification_booking", columnNames = "booking_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckInIdVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Reference to the parent booking.
     * One verification per booking (unique constraint).
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    /**
     * UUID correlating all verification data for a single check-in session.
     */
    @Column(name = "check_in_session_id", length = 36, nullable = false)
    private String checkInSessionId;

    /**
     * Reference to the guest being verified.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guest_id", nullable = false)
    private User guest;

    // ========== LIVENESS CHECK ==========

    /**
     * Whether liveness check passed (anti-spoofing).
     */
    @Column(name = "liveness_passed", nullable = false)
    @Builder.Default
    private Boolean livenessPassed = false;

    /**
     * Liveness confidence score (0.0000 to 1.0000).
     * Threshold for passing: 0.85 (configurable).
     */
    @Column(name = "liveness_score", precision = 5, scale = 4)
    private BigDecimal livenessScore;

    /**
     * Liveness provider (e.g., "AWS_REKOGNITION", "ONFIDO", "IPROOV").
     */
    @Column(name = "liveness_provider", length = 50)
    private String livenessProvider;

    /**
     * When liveness check was performed.
     */
    @Column(name = "liveness_checked_at")
    private Instant livenessCheckedAt;

    /**
     * Number of liveness check attempts.
     * Max attempts before manual review: 3 (configurable).
     */
    @Column(name = "liveness_attempts", nullable = false)
    @Builder.Default
    private Integer livenessAttempts = 0;

    // ========== DOCUMENT VERIFICATION ==========

    /**
     * Type of document submitted.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type")
    private DocumentType documentType;

    /**
     * Document issuing country (ISO 3166-1 alpha-3, e.g., "SRB").
     */
    @Column(name = "document_country", length = 3)
    private String documentCountry;

    /**
     * Document expiry date.
     */
    @Column(name = "document_expiry")
    private LocalDate documentExpiry;

    /**
     * Whether document expires after trip end date.
     * Calculated field: documentExpiry > booking.endDate
     */
    @Column(name = "document_expiry_valid")
    private Boolean documentExpiryValid;

    // ========== NAME MATCHING (Serbian-Aware) ==========

    /**
     * First name extracted from document via OCR.
     * Raw output, may contain Serbian diacritics.
     */
    @Column(name = "extracted_first_name", length = 100)
    private String extractedFirstName;

    /**
     * Last name extracted from document via OCR.
     * Raw output, may contain Serbian diacritics.
     */
    @Column(name = "extracted_last_name", length = 100)
    private String extractedLastName;

    /**
     * ASCII-normalized extracted name for matching.
     * Serbian: Đorđević → DJORDJEVIC
     */
    @Column(name = "extracted_name_normalized", length = 200)
    private String extractedNameNormalized;

    /**
     * ASCII-normalized user profile name for comparison.
     */
    @Column(name = "profile_name_normalized", length = 200)
    private String profileNameNormalized;

    /**
     * Jaro-Winkler similarity score between normalized names (0.0000 to 1.0000).
     * Threshold for passing: 0.80 (configurable).
     */
    @Column(name = "name_match_score", precision = 5, scale = 4)
    private BigDecimal nameMatchScore;

    /**
     * Whether name matching passed (score >= 0.80).
     */
    @Column(name = "name_match_passed")
    private Boolean nameMatchPassed;

    // ========== OVERALL STATUS ==========

    /**
     * Overall verification status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    @Builder.Default
    private IdVerificationStatus verificationStatus = IdVerificationStatus.PENDING;

    /**
     * Human-readable status message.
     */
    @Column(name = "verification_message", length = 500)
    private String verificationMessage;

    // ========== PII PHOTO STORAGE (Encrypted Bucket) ==========

    /**
     * Path to front of ID document in PII bucket.
     */
    @Column(name = "id_photo_front_storage_key", length = 500)
    private String idPhotoFrontStorageKey;

    /**
     * Path to back of ID document in PII bucket.
     */
    @Column(name = "id_photo_back_storage_key", length = 500)
    private String idPhotoBackStorageKey;

    /**
     * Path to liveness selfie in PII bucket.
     */
    @Column(name = "selfie_storage_key", length = 500)
    private String selfieStorageKey;

    // ========== MANUAL REVIEW ==========

    /**
     * Admin user who manually reviewed this verification.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    /**
     * When manual review was performed.
     */
    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /**
     * Admin notes from manual review.
     */
    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    // ========== TIMESTAMPS ==========

    /**
     * When verification record was created.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When verification was completed (passed or failed).
     */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    // ========== ENUMS ==========

    /**
     * Accepted document types for identity verification.
     */
    public enum DocumentType {
        /** Serbian or international driver's license */
        DRIVERS_LICENSE,
        
        /** Passport (any country) */
        PASSPORT,
        
        /** National ID card (Serbian: lična karta) */
        NATIONAL_ID
    }

    // ========== HELPER METHODS ==========

    /**
     * Check if verification is complete (passed or failed).
     */
    public boolean isComplete() {
        return verificationStatus != IdVerificationStatus.PENDING
            && verificationStatus != IdVerificationStatus.MANUAL_REVIEW;
    }

    /**
     * Check if guest can proceed with check-in.
     */
    public boolean canProceed() {
        return verificationStatus.isPassed();
    }

    /**
     * Check if manual review is required.
     */
    public boolean needsManualReview() {
        return verificationStatus.requiresReview();
    }

    /**
     * Record a liveness attempt.
     */
    public void recordLivenessAttempt(BigDecimal score, boolean passed) {
        this.livenessAttempts++;
        this.livenessScore = score;
        this.livenessPassed = passed;
        this.livenessCheckedAt = Instant.now();
    }

    /**
     * Record name match result.
     */
    public void recordNameMatch(String extractedNormalized, String profileNormalized, BigDecimal score) {
        this.extractedNameNormalized = extractedNormalized;
        this.profileNameNormalized = profileNormalized;
        this.nameMatchScore = score;
        this.nameMatchPassed = score != null && score.compareTo(new BigDecimal("0.80")) >= 0;
    }

    /**
     * Mark verification as passed.
     */
    public void markPassed() {
        this.verificationStatus = IdVerificationStatus.PASSED;
        this.verifiedAt = Instant.now();
    }

    /**
     * Mark verification as failed with reason.
     */
    public void markFailed(IdVerificationStatus failureStatus, String message) {
        if (!failureStatus.isFailed()) {
            throw new IllegalArgumentException("Status must be a failure status");
        }
        this.verificationStatus = failureStatus;
        this.verificationMessage = message;
        this.verifiedAt = Instant.now();
    }

    /**
     * Request manual review.
     */
    public void requestManualReview(String message) {
        this.verificationStatus = IdVerificationStatus.MANUAL_REVIEW;
        this.verificationMessage = message;
    }

    /**
     * Apply manual override (admin approval despite failed checks).
     */
    public void applyOverride(User admin, String notes) {
        this.verificationStatus = IdVerificationStatus.OVERRIDE_APPROVED;
        this.reviewedBy = admin;
        this.reviewedAt = Instant.now();
        this.reviewNotes = notes;
        this.verifiedAt = Instant.now();
    }
}
