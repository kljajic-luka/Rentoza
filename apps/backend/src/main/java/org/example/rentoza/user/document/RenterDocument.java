package org.example.rentoza.user.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.car.DocumentVerificationStatus;
import org.example.rentoza.config.timezone.SerbiaTimeZone;
import org.example.rentoza.user.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents a single uploaded document for renter identity verification.
 * 
 * <p>Mirrors {@link org.example.rentoza.car.CarDocument} structure for consistency.
 * 
 * <p>Document types:
 * <ul>
 *   <li>DRIVERS_LICENSE_FRONT - Front of driver's license (photo, name)</li>
 *   <li>DRIVERS_LICENSE_BACK - Back of driver's license (categories, expiry)</li>
 *   <li>SELFIE - Liveness check and face matching</li>
 *   <li>ID_CARD_FRONT/BACK - Optional national ID</li>
 *   <li>PASSPORT - For international renters</li>
 * </ul>
 * 
 * <p>Processing flow:
 * <ol>
 *   <li>User uploads document → processingStatus = PENDING</li>
 *   <li>Async job runs OCR/liveness → processingStatus = PROCESSING</li>
 *   <li>Results stored → processingStatus = COMPLETED</li>
 *   <li>Auto-approve or manual review based on results</li>
 * </ol>
 */
@Entity
@Table(
    name = "renter_documents",
    indexes = {
        @Index(name = "idx_renter_documents_user_id", columnList = "user_id"),
        @Index(name = "idx_renter_documents_type", columnList = "document_type"),
        @Index(name = "idx_renter_documents_status", columnList = "verification_status"),
        @Index(name = "idx_renter_documents_expiry", columnList = "expiry_date"),
        @Index(name = "idx_renter_documents_processing", columnList = "processing_status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RenterDocument {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * User (renter) this document belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;
    
    /**
     * Document type (DRIVERS_LICENSE_FRONT, SELFIE, etc.).
     */
    @Column(name = "document_type", nullable = false, length = 50)
    private RenterDocumentType type;
    
    /**
     * Storage path (local) or URL (S3/Cloudinary).
     */
    @Column(name = "document_url", nullable = false, length = 500)
    private String documentUrl;
    
    /**
     * Original filename for display.
     */
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;
    
    /**
     * SHA256 hash for integrity verification & duplicate detection.
     */
    @Column(name = "document_hash", nullable = false, length = 64)
    private String documentHash;
    
    /**
     * File size in bytes.
     */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;
    
    /**
     * MIME type (image/jpeg, image/png, application/pdf).
     */
    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;
    
    /**
     * When user uploaded document.
     */
    @Column(name = "upload_date", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime uploadDate = SerbiaTimeZone.now();
    
    /**
     * When document expires (for licenses, IDs).
     * Extracted from OCR.
     */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;
    
    /**
     * Verification status (reuses car document enum for consistency).
     */
    @Column(name = "verification_status", nullable = false, length = 50)
    @Builder.Default
    private DocumentVerificationStatus status = DocumentVerificationStatus.PENDING;
    
    /**
     * Admin who verified/rejected.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by")
    private User verifiedBy;
    
    /**
     * When verified/rejected.
     */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;
    
    /**
     * Rejection reason (if rejected).
     */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;
    
    // ================= OCR & BIOMETRIC FIELDS =================
    
    /**
     * OCR extracted data (JSON format).
     * Contains: firstName, lastName, documentNumber, expiryDate, categories, etc.
     */
    @Column(name = "ocr_extracted_data", columnDefinition = "TEXT")
    private String ocrExtractedData;
    
    /**
     * OCR confidence score (0.0-1.0).
     * Used for auto-approve threshold checking.
     */
    @Column(name = "ocr_confidence", precision = 3, scale = 2)
    private BigDecimal ocrConfidence;
    
    /**
     * Whether liveness check passed (for selfie documents).
     */
    @Column(name = "liveness_passed")
    private Boolean livenessPassed;
    
    /**
     * Face match score between selfie and ID photo (0.0-1.0).
     */
    @Column(name = "face_match_score", precision = 3, scale = 2)
    private BigDecimal faceMatchScore;
    
    /**
     * Name match score between OCR name and profile name (0.0-1.0).
     */
    @Column(name = "name_match_score", precision = 3, scale = 2)
    private BigDecimal nameMatchScore;
    
    // ================= PROCESSING STATUS =================
    
    /**
     * Processing status for async verification.
     */
    @Column(name = "processing_status", nullable = false, length = 20)
    @Builder.Default
    private ProcessingStatus processingStatus = ProcessingStatus.PENDING;
    
    /**
     * Error message if processing failed.
     */
    @Column(name = "processing_error", length = 500)
    private String processingError;
    
    // ================= TIMESTAMPS =================
    
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = SerbiaTimeZone.now();
    
    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = SerbiaTimeZone.now();
    
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = SerbiaTimeZone.now();
    }
    
    // ================= HELPER METHODS =================
    
    /**
     * Check if document is expired.
     */
    public boolean isExpired() {
        return expiryDate != null && expiryDate.isBefore(SerbiaTimeZone.today());
    }
    
    /**
     * Check if document will expire within N days.
     */
    public boolean willExpireWithin(int days) {
        if (expiryDate == null) return false;
        LocalDate warningDate = SerbiaTimeZone.today().plusDays(days);
        return expiryDate.isBefore(warningDate) && expiryDate.isAfter(SerbiaTimeZone.today());
    }
    
    /**
     * Get days until expiry (negative if expired).
     */
    public long getDaysUntilExpiry() {
        if (expiryDate == null) return Long.MAX_VALUE;
        return java.time.temporal.ChronoUnit.DAYS.between(SerbiaTimeZone.today(), expiryDate);
    }
    
    /**
     * Check if OCR processing is complete.
     */
    public boolean isOcrComplete() {
        return processingStatus == ProcessingStatus.COMPLETED 
            && ocrConfidence != null;
    }
    
    /**
     * Check if verification is complete (approved or rejected).
     */
    public boolean isVerificationComplete() {
        return status == DocumentVerificationStatus.VERIFIED 
            || status == DocumentVerificationStatus.REJECTED;
    }
    
    /**
     * Check if OCR confidence meets threshold for auto-approve.
     */
    public boolean meetsOcrThreshold(double threshold) {
        return ocrConfidence != null 
            && ocrConfidence.doubleValue() >= threshold;
    }
    
    /**
     * Check if name match score meets threshold.
     */
    public boolean meetsNameMatchThreshold(double threshold) {
        return nameMatchScore != null 
            && nameMatchScore.doubleValue() >= threshold;
    }
    
    /**
     * Processing status for async verification jobs.
     */
    public enum ProcessingStatus {
        /** Document uploaded, waiting for processing */
        PENDING,
        /** OCR/liveness check in progress */
        PROCESSING,
        /** Processing completed successfully */
        COMPLETED,
        /** Processing failed (see processingError) */
        FAILED
    }
}
