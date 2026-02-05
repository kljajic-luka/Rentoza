package org.example.rentoza.booking.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.checkin.CheckInPhotoType;
import org.example.rentoza.booking.checkin.ExifValidationStatus;

import java.time.LocalDateTime;

/**
 * Response DTO for check-in photos.
 * 
 * <p>Extended with rejection fields to support the zero-storage policy:
 * <ul>
 *   <li>{@code accepted} - whether the photo passed EXIF validation</li>
 *   <li>{@code rejectionReason} - user-friendly rejection message (Serbian)</li>
 *   <li>{@code remediationHint} - actionable hint to fix the issue</li>
 * </ul>
 * 
 * <p>For rejected photos, only photoType, exifValidationStatus, and rejection fields are populated.
 * The photoId and url will be null since rejected photos are not stored.
 * 
 * @see org.example.rentoza.booking.checkin.PhotoRejectionService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckInPhotoDTO {

    private Long photoId;
    private CheckInPhotoType photoType;
    
    /**
     * Pre-signed URL for photo access (expires in 1 hour).
     * In current implementation, returns direct storage path.
     * Will be null for rejected photos (zero-storage policy).
     */
    private String url;
    
    private LocalDateTime uploadedAt;
    private ExifValidationStatus exifValidationStatus;
    private String exifValidationMessage;
    
    // Photo metadata
    private Integer width;
    private Integer height;
    private String mimeType;
    
    // EXIF data (if available)
    private LocalDateTime exifTimestamp;
    private Double exifLatitude;
    private Double exifLongitude;
    private String deviceModel;
    
    // ========== Rejection Fields (Phase 1: Rejected Photo Infrastructure) ==========
    
    /**
     * Whether the photo was accepted by EXIF validation.
     * false = rejected (not stored), true = accepted (stored to DB).
     */
    @Builder.Default
    private boolean accepted = true;
    
    /**
     * User-friendly rejection reason in Serbian.
     * Only populated when accepted=false.
     * Example: "Fotografija je prestara. Mora biti snimljena u poslednjih 30 minuta."
     */
    private String rejectionReason;
    
    /**
     * Actionable hint for the user to fix the rejection issue.
     * Only populated when accepted=false.
     * Example: "Otvorite kameru na telefonu i napravite novu fotografiju."
     */
    private String remediationHint;
}
