package org.example.rentoza.booking.checkin;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Guest check-in photo entity for dual-party verification.
 * 
 * <p>When the guest arrives for pickup, they capture the same 12-point photos
 * as the host. This creates bilateral photographic evidence for dispute resolution.
 * 
 * <p>Photo comparison logic:
 * <ul>
 *   <li>Host photos establish "departure condition"</li>
 *   <li>Guest photos confirm "acceptance condition"</li>
 *   <li>Any discrepancies are flagged for review before handover completes</li>
 * </ul>
 * 
 * <h2>EXIF Validation</h2>
 * <p>Same validation as host photos:
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
 * @see CheckInPhoto for host photos
 * @see CheckInPhotoType
 * @see ExifValidationStatus
 */
@Entity
@Table(name = "guest_check_in_photos", indexes = {
    @Index(name = "idx_guest_checkin_photo_session", columnList = "check_in_session_id, photo_type"),
    @Index(name = "idx_guest_checkin_photo_booking", columnList = "booking_id, photo_type"),
    @Index(name = "idx_guest_checkin_photo_exif_status", columnList = "exif_validation_status"),
    @Index(name = "idx_guest_checkin_photo_uploader", columnList = "uploaded_by, uploaded_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestCheckInPhoto {

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
     * <p>This is the SAME session ID used for host check-in photos,
     * allowing correlation of host vs guest photos for the same pickup.
     */
    @Column(name = "check_in_session_id", length = 36, nullable = false)
    private String checkInSessionId;

    /**
     * Type/category of photo (exterior, interior, odometer, etc.).
     * <p>Guest photos use the same CheckInPhotoType as host photos
     * (e.g., HOST_EXTERIOR_FRONT becomes the reference for GUEST_EXTERIOR_FRONT).
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
     * Format: guest-checkin/{sessionId}/{photoType}_{timestamp}.jpg
     */
    @Column(name = "storage_key", length = 500, nullable = false)
    private String storageKey;

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

    /**
     * SHA-256 hash of uploaded image bytes for evidence integrity binding.
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

    // ========== UPLOAD METADATA ==========

    /**
     * User who uploaded this photo (always the guest).
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
     * Reuses same bucket structure as host check-in photos.
     */
    public enum StorageBucket {
        /** Standard photos - regular access control */
        CHECKIN_STANDARD,
        
        /** PII photos (ID documents) - restricted access, additional encryption */
        CHECKIN_PII
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

    /**
     * Check if this is a required photo type for guest check-in.
     * <p>Guest must capture same photos as host for dual-party verification.
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

    /**
     * Get corresponding host photo type for comparison.
     * <p>Maps guest photo types to their host counterparts for discrepancy detection.
     * 
     * @return the corresponding host photo type
     */
    public CheckInPhotoType getCorrespondingHostPhotoType() {
        // Guest photos use the same enum values as host photos
        // since CheckInPhotoType already has both HOST_* and GUEST_* variants
        return photoType;
    }
}
