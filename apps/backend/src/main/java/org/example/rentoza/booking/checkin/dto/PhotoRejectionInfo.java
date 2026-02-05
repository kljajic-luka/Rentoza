package org.example.rentoza.booking.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.checkin.ExifValidationStatus;

/**
 * Encapsulates rejection information for a photo that failed EXIF validation.
 * 
 * <p>Used by {@link org.example.rentoza.booking.checkin.PhotoRejectionService} 
 * to provide user-friendly feedback for rejected photos.
 * 
 * <h2>Localization</h2>
 * All text fields (rejectionReason, remediationHint) are in Serbian by default.
 * Future iterations may support i18n via message bundles.
 * 
 * @see org.example.rentoza.booking.checkin.PhotoRejectionService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoRejectionInfo {
    
    /**
     * The EXIF validation status that caused the rejection.
     */
    private ExifValidationStatus status;
    
    /**
     * User-friendly rejection reason in Serbian.
     * Example: "Fotografija je prestara. Mora biti snimljena u poslednjih 30 minuta."
     */
    private String rejectionReason;
    
    /**
     * Actionable hint for the user to fix the issue in Serbian.
     * Example: "Otvorite kameru na telefonu i napravite novu fotografiju."
     */
    private String remediationHint;
    
    /**
     * Machine-readable error code for frontend mapping.
     * Example: "PHOTO_TOO_OLD", "NO_EXIF_DATA"
     */
    private String errorCode;
}
