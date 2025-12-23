package org.example.rentoza.booking.checkout;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.checkin.CheckInPhotoType;
import org.example.rentoza.booking.checkin.ExifValidationStatus;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Host checkout photo entity for dual-party verification at return.
 * 
 * <p>When the vehicle is returned, the host captures the same 12-point photos
 * that the guest captured at departure. This creates bilateral photographic 
 * evidence for dispute resolution on damage or condition changes during rental.
 * 
 * <p>Photo comparison flow:
 * <ul>
 *   <li>Guest checkout photos establish "return condition"</li>
 *   <li>Host checkout photos confirm "acceptance of return"</li>
 *   <li>Discrepancies flagged before checkout completes</li>
 *   <li>Side-by-side comparison: Check-in vs Checkout photos</li>
 * </ul>
 * 
 * <h2>EXIF Validation</h2>
 * <p>Same validation as all other photos:
 * <ul>
 *   <li><b>EXIF Timestamp:</b> Must be recent (within 30 minutes of upload)</li>
 *   <li><b>EXIF GPS:</b> Must be near return location</li>
 *   <li><b>EXIF Presence:</b> Screenshots and camera roll uploads detected</li>
 * </ul>
 * 
 * <h2>Storage</h2>
 * <p>Uses dedicated CHECKOUT_STANDARD bucket for organization and access control.
 * 
 * <h2>Soft Delete</h2>
 * <p>Photos never hard-deleted due to legal retention requirements.
 *
 * @see CheckInPhotoType for photo type definitions
 * @see ExifValidationStatus for validation states
 */
@Entity
@Table(name = "host_checkout_photos", indexes = {
    @Index(name = "idx_host_checkout_photo_session", columnList = "checkout_session_id, photo_type"),
    @Index(name = "idx_host_checkout_photo_booking", columnList = "booking_id, photo_type"),
    @Index(name = "idx_host_checkout_photo_exif_status", columnList = "exif_validation_status"),
    @Index(name = "idx_host_checkout_photo_uploader", columnList = "uploaded_by, uploaded_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostCheckoutPhoto {

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
     * UUID correlating all photos for a single checkout session.
     * <p>Separate from check-in session ID to distinguish pickup vs return photos.
     */
    @Column(name = "checkout_session_id", length = 36, nullable = false)
    private String checkoutSessionId;

    /**
     * Type/category of photo (exterior, interior, odometer, etc.).
     * <p>Uses checkout-specific photo types from CheckInPhotoType enum
     * (e.g., HOST_CHECKOUT_EXTERIOR_FRONT, HOST_CHECKOUT_ODOMETER).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "photo_type", nullable = false)
    private CheckInPhotoType photoType;

    // ========== STORAGE ==========

    /**
     * Storage bucket for access control.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "storage_bucket", nullable = false)
    @Builder.Default
    private StorageBucket storageBucket = StorageBucket.CHECKOUT_STANDARD;

    /**
     * Path to the photo in cloud storage.
     * Format: host-checkout/{sessionId}/{photoType}_{timestamp}.jpg
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
     * User who uploaded this photo (always the host).
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
     * Storage bucket for checkout photos.
     */
    public enum StorageBucket {
        /** Standard checkout photos */
        CHECKOUT_STANDARD,
        
        /** PII checkout photos (if any documents are captured) */
        CHECKOUT_PII
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
     * Check if this is a required photo type for host checkout.
     */
    public boolean isRequired() {
        return photoType.isHostCheckoutPhoto();
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
     * Get corresponding guest checkout photo type for comparison.
     * <p>Maps host checkout photo types to their guest counterparts.
     * 
     * @return the corresponding guest checkout photo type
     */
    public CheckInPhotoType getCorrespondingGuestPhotoType() {
        // Map HOST_CHECKOUT_* types to CHECKOUT_* (guest) types
        String hostType = photoType.name();
        if (hostType.startsWith("HOST_CHECKOUT_")) {
            String guestTypeName = "CHECKOUT_" + hostType.substring("HOST_CHECKOUT_".length());
            try {
                return CheckInPhotoType.valueOf(guestTypeName);
            } catch (IllegalArgumentException e) {
                // If no corresponding guest type exists, return the same type
                return photoType;
            }
        }
        return photoType;
    }
}
