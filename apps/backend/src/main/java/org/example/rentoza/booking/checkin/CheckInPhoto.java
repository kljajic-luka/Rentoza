package org.example.rentoza.booking.checkin;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Check-in photo entity with EXIF validation for fraud prevention.
 * 
 * <p>Each photo captured during check-in is validated for:
 * <ul>
 *   <li><b>EXIF Timestamp:</b> Must be recent (within 30 minutes of upload)</li>
 *   <li><b>EXIF GPS:</b> Must be near car/pickup location (within 1km)</li>
 *   <li><b>EXIF Presence:</b> Screenshots and camera roll uploads are detected</li>
 * </ul>
 * 
 * <h2>Storage Buckets</h2>
 * <ul>
 *   <li>{@code CHECKIN_STANDARD}: Regular photos (exterior, interior, readings)</li>
 *   <li>{@code CHECKIN_PII}: ID document photos (restricted access, additional encryption)</li>
 * </ul>
 * 
 * <h2>Soft Delete</h2>
 * <p>Photos are never hard-deleted due to legal retention requirements.
 * Soft-deleted photos are marked with {@code deletedAt} timestamp.
 *
 * @see CheckInPhotoType
 * @see ExifValidationStatus
 */
@Entity
@Table(name = "check_in_photos", indexes = {
    @Index(name = "idx_checkin_photo_session", columnList = "check_in_session_id, photo_type"),
    @Index(name = "idx_checkin_photo_booking", columnList = "booking_id, photo_type"),
    @Index(name = "idx_checkin_photo_exif_status", columnList = "exif_validation_status"),
    @Index(name = "idx_checkin_photo_uploader", columnList = "uploaded_by, uploaded_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckInPhoto {

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
     * UUID correlating all photos for a single check-in session.
     */
    @Column(name = "check_in_session_id", length = 36, nullable = false)
    private String checkInSessionId;

    /**
     * Type/category of photo (exterior, interior, odometer, etc.).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "photo_type", nullable = false)
    private CheckInPhotoType photoType;

    // ========== STORAGE ==========

    /**
     * Storage bucket for access control.
     * PII bucket has stricter access and additional encryption.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "storage_bucket", nullable = false)
    @Builder.Default
    private StorageBucket storageBucket = StorageBucket.CHECKIN_STANDARD;

    /**
     * Path to the photo in cloud storage.
     * Format: checkin/{sessionId}/{photoType}_{timestamp}.jpg
     */
    @Column(name = "storage_key", length = 500, nullable = false)
    private String storageKey;

    /**
     * Path to original photo with EXIF in admin-only audit bucket.
     * Used for dispute resolution where GPS/timestamp evidence is needed.
     * NULL if audit backup is disabled or not applicable.
     * 
     * @since VAL-001 - EXIF GPS Privacy Stripping
     */
    @Column(name = "audit_storage_key")
    private String auditStorageKey;

    /**
     * Durable upload lifecycle for storage-backed photos.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false, length = 32)
    @Builder.Default
    private UploadStatus uploadStatus = UploadStatus.COMPLETED;

    /**
     * Number of storage/finalize attempts for this photo row.
     */
    @Column(name = "upload_attempts", nullable = false)
    @Builder.Default
    private Integer uploadAttempts = 0;

    /**
     * Last time the storage workflow attempted to upload or finalize this photo.
     */
    @Column(name = "last_upload_attempt_at")
    private Instant lastUploadAttemptAt;

    /**
     * When the stripped standard-bucket photo upload succeeded.
     */
    @Column(name = "standard_uploaded_at")
    private Instant standardUploadedAt;

    /**
     * When the overall upload workflow was finalized and made visible.
     */
    @Column(name = "upload_finalized_at")
    private Instant uploadFinalizedAt;

    /**
     * Latest storage/finalize error for reconciliation visibility.
     */
    @Column(name = "last_upload_error", length = 1000)
    private String lastUploadError;

    /**
     * Audit-bucket upload state, tracked separately because standard upload may succeed
     * even when the EXIF-preserving audit copy fails.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "audit_upload_status", nullable = false, length = 32)
    @Builder.Default
    private AuditUploadStatus auditUploadStatus = AuditUploadStatus.NOT_REQUIRED;

    /**
     * When the audit-bucket upload succeeded.
     */
    @Column(name = "audit_uploaded_at")
    private Instant auditUploadedAt;

    // ========== FILE METADATA ==========

    /**
     * Original filename from client upload.
     */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /**
     * MIME type (e.g., image/jpeg, image/png).
     */
    @Column(name = "mime_type", length = 50, nullable = false)
    private String mimeType;

    /**
     * File size in bytes.
     */
    @Column(name = "file_size_bytes", nullable = false)
    private Integer fileSizeBytes;

    /**
     * Image width in pixels.
     */
    @Column(name = "image_width")
    private Integer imageWidth;

    /**
     * Image height in pixels.
     */
    @Column(name = "image_height")
    private Integer imageHeight;

    // ========== IMAGE INTEGRITY ==========

    /**
     * SHA-256 hash of the original uploaded image bytes (before EXIF stripping).
     * Used for:
     * <ul>
     *   <li>Duplicate/recycled photo fraud detection across bookings</li>
     *   <li>Verifying audit bucket integrity (DB hash must match stored blob)</li>
     *   <li>Chain-of-custody evidence linking DB record to storage artifact</li>
     * </ul>
     *
     * @since V61 - Pre-production hardening (column added)
     * @since R1/R2 - Trust & evidence audit remediation (application code populated)
     */
    @Column(name = "image_hash", length = 128)
    private String imageHash;

    // ========== EXIF VALIDATION ==========

    /**
     * Photo capture timestamp from EXIF DateTimeOriginal.
     */
    @Column(name = "exif_timestamp")
    private Instant exifTimestamp;

    /**
     * GPS latitude from EXIF.
     */
    @Column(name = "exif_latitude", precision = 10, scale = 8)
    private BigDecimal exifLatitude;

    /**
     * GPS longitude from EXIF.
     */
    @Column(name = "exif_longitude", precision = 11, scale = 8)
    private BigDecimal exifLongitude;

    /**
     * Camera manufacturer from EXIF.
     */
    @Column(name = "exif_device_make", length = 100)
    private String exifDeviceMake;

    /**
     * Camera model from EXIF.
     */
    @Column(name = "exif_device_model", length = 100)
    private String exifDeviceModel;

    /**
     * EXIF validation result.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "exif_validation_status", nullable = false)
    @Builder.Default
    private ExifValidationStatus exifValidationStatus = ExifValidationStatus.PENDING;

    /**
     * Human-readable validation result message.
     */
    @Column(name = "exif_validation_message", length = 500)
    private String exifValidationMessage;

    /**
     * When EXIF validation was performed.
     */
    @Column(name = "exif_validated_at")
    private Instant exifValidatedAt;

    // ========== PHASE 4E: PHOTO DEADLINE EVIDENCE WEIGHT ==========

    /**
     * Evidence weight based on upload timing relative to trip end.
     * 
     * <p><b>Phase 4E Safety Improvement:</b> Photos uploaded late (after deadline)
     * are marked as SECONDARY evidence to reduce their weight in disputes.
     * 
     * <p><b>Deadline:</b> Configurable (default 24 hours after trip end)
     * <ul>
     *   <li><b>PRIMARY:</b> Uploaded on time - full evidence weight</li>
     *   <li><b>SECONDARY:</b> Uploaded late - reduced evidence weight, noted in disputes</li>
     * </ul>
     * 
     * @see EvidenceWeight
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_weight", length = 20)
    @Builder.Default
    private EvidenceWeight evidenceWeight = EvidenceWeight.PRIMARY;

    /**
     * When the evidence weight was downgraded (if applicable).
     * Null for PRIMARY weight photos.
     */
    @Column(name = "evidence_weight_downgraded_at")
    private Instant evidenceWeightDowngradedAt;

    /**
     * Reason for evidence weight downgrade.
     */
    @Column(name = "evidence_weight_downgrade_reason", length = 255)
    private String evidenceWeightDowngradeReason;

    // ========== UPLOAD METADATA ==========

    /**
     * User who uploaded this photo.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    /**
     * Server-side upload timestamp.
     */
    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    /**
     * Client-side timestamp for offline uploads.
     */
    @Column(name = "client_uploaded_at")
    private Instant clientUploadedAt;

    // ========== SOFT DELETE ==========

    /**
     * Soft delete timestamp. Null means photo is active.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * User who deleted this photo.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private User deletedBy;

    /**
     * Reason for deletion (for audit).
     */
    @Column(name = "deleted_reason", length = 255)
    private String deletedReason;

    // ========== ENUMS ==========

    /**
     * Storage bucket for access control and encryption levels.
     */
    public enum StorageBucket {
        /** Standard photos - regular access control */
        CHECKIN_STANDARD,
        
        /** PII photos (ID documents) - restricted access, additional encryption */
        CHECKIN_PII
    }

    public enum UploadStatus {
        PENDING_UPLOAD,
        PENDING_FINALIZE,
        COMPLETED,
        FAILED_TERMINAL
    }

    public enum AuditUploadStatus {
        NOT_REQUIRED,
        PENDING,
        COMPLETED,
        FAILED
    }

    // ========== HELPER METHODS ==========

    /**
     * Check if this photo has been soft-deleted.
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Check if EXIF validation passed (photo can be used).
     */
    public boolean isExifValid() {
        return exifValidationStatus.isAccepted();
    }

    public boolean isCompletedUpload() {
        return uploadStatus == UploadStatus.COMPLETED;
    }

    /**
     * Check if this is a required photo type for host check-in.
     */
    public boolean isRequired() {
        return photoType.isRequiredForHost();
    }

    /**
     * Soft delete this photo.
     */
    public void softDelete(User deletedBy, String reason) {
        this.deletedAt = Instant.now();
        this.deletedBy = deletedBy;
        this.deletedReason = reason;
    }

    public void markUploadPending(boolean auditRequired) {
        this.uploadStatus = UploadStatus.PENDING_UPLOAD;
        this.auditUploadStatus = auditRequired ? AuditUploadStatus.PENDING : AuditUploadStatus.NOT_REQUIRED;
        this.uploadAttempts = 0;
        this.lastUploadAttemptAt = null;
        this.standardUploadedAt = null;
        this.uploadFinalizedAt = null;
        this.lastUploadError = null;
        this.auditUploadedAt = null;
    }

    public void recordUploadAttempt() {
        this.uploadAttempts = (this.uploadAttempts == null ? 0 : this.uploadAttempts) + 1;
        this.lastUploadAttemptAt = Instant.now();
    }

    public void markPendingFinalize(AuditUploadStatus auditStatus, String auditStorageKey, String errorMessage) {
        this.uploadStatus = UploadStatus.PENDING_FINALIZE;
        this.standardUploadedAt = Instant.now();
        this.auditUploadStatus = auditStatus;
        this.auditStorageKey = auditStorageKey;
        if (auditStatus == AuditUploadStatus.COMPLETED) {
            this.auditUploadedAt = Instant.now();
        }
        this.lastUploadError = trimUploadError(errorMessage);
    }

    public void markCompleted() {
        this.uploadStatus = UploadStatus.COMPLETED;
        if (this.standardUploadedAt == null) {
            this.standardUploadedAt = Instant.now();
        }
        this.uploadFinalizedAt = Instant.now();
    }

    public void markTerminalFailure(String errorMessage) {
        this.uploadStatus = UploadStatus.FAILED_TERMINAL;
        this.lastUploadError = trimUploadError(errorMessage);
    }

    private String trimUploadError(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        return errorMessage.length() <= 1000 ? errorMessage : errorMessage.substring(0, 1000);
    }
}
