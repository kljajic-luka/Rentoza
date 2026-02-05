package org.example.rentoza.booking.checkin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.checkin.CheckInPhotoType;

import java.util.List;

/**
 * DTO for validating photo sequence completeness.
 * Returns information about what photos are missing or invalid.
 * 
 * @since Enterprise Upgrade Phase 2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoSequenceValidationDTO {
    
    /**
     * Whether the sequence is valid (all required photos present).
     */
    private boolean valid;
    
    /**
     * List of missing photo types.
     */
    private List<CheckInPhotoType> missingTypes;
    
    /**
     * List of photo types that have issues (e.g., EXIF invalid).
     */
    private List<CheckInPhotoType> invalidTypes;
    
    /**
     * Total photos uploaded.
     */
    private int uploadedCount;
    
    /**
     * Total photos required.
     */
    private int requiredCount;
    
    /**
     * Completion percentage (0-100).
     */
    private int completionPercentage;
    
    /**
     * Human-readable message in Serbian.
     */
    private String messageSr;
    
    /**
     * Human-readable message in English.
     */
    private String messageEn;
    
    /**
     * Whether the sequence is complete enough to proceed to handshake.
     */
    private boolean readyForHandshake;
    
    /**
     * Static factory for a valid sequence.
     */
    public static PhotoSequenceValidationDTO valid(int totalPhotos) {
        return PhotoSequenceValidationDTO.builder()
                .valid(true)
                .uploadedCount(totalPhotos)
                .requiredCount(totalPhotos)
                .completionPercentage(100)
                .readyForHandshake(true)
                .messageSr("Sve obavezne fotografije su otpremljene")
                .messageEn("All required photos have been uploaded")
                .build();
    }
    
    /**
     * Static factory for an invalid/incomplete sequence.
     */
    public static PhotoSequenceValidationDTO invalid(
            List<CheckInPhotoType> missing,
            int uploaded,
            int required) {
        int percentage = required > 0 ? (uploaded * 100) / required : 0;
        return PhotoSequenceValidationDTO.builder()
                .valid(false)
                .missingTypes(missing)
                .uploadedCount(uploaded)
                .requiredCount(required)
                .completionPercentage(percentage)
                .readyForHandshake(false)
                .messageSr("Nedostaje " + missing.size() + " obaveznih fotografija")
                .messageEn("Missing " + missing.size() + " required photos")
                .build();
    }
}
