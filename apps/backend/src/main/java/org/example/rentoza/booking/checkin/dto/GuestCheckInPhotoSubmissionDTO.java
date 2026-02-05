package org.example.rentoza.booking.checkin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.checkin.CheckInPhotoType;

import java.time.Instant;
import java.util.List;

/**
 * Request DTO for guest check-in photo submission.
 * 
 * <p>When the guest arrives for pickup, they capture photos of the vehicle
 * to confirm the condition matches what the host documented. This creates
 * bilateral photographic evidence for any disputes.
 * 
 * <h2>Required Photos (same as host)</h2>
 * <ul>
 *   <li>GUEST_EXTERIOR_FRONT - Front of vehicle</li>
 *   <li>GUEST_EXTERIOR_REAR - Rear of vehicle</li>
 *   <li>GUEST_EXTERIOR_LEFT - Left side</li>
 *   <li>GUEST_EXTERIOR_RIGHT - Right side</li>
 *   <li>GUEST_INTERIOR_DASHBOARD - Dashboard/interior front</li>
 *   <li>GUEST_INTERIOR_REAR - Rear seats</li>
 *   <li>GUEST_ODOMETER - Odometer reading</li>
 *   <li>GUEST_FUEL_GAUGE - Fuel gauge reading</li>
 * </ul>
 * 
 * <h2>Optional Photos</h2>
 * <ul>
 *   <li>GUEST_DAMAGE_NOTED - Pre-existing damage the guest wants to document</li>
 *   <li>GUEST_CUSTOM - Any additional photos</li>
 * </ul>
 * 
 * @see GuestCheckInPhotoResponseDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GuestCheckInPhotoSubmissionDTO {

    /**
     * List of photos being submitted.
     * Each photo includes type and base64-encoded image data.
     */
    @NotEmpty(message = "At least one photo is required")
    private List<PhotoItem> photos;

    /**
     * Client-side timestamp when photos were captured.
     * Used for offline support - photos captured without network.
     */
    private Instant clientCapturedAt;

    /**
     * Optional notes from guest about vehicle condition.
     */
    private String conditionNotes;

    /**
     * Individual photo item in the submission.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhotoItem {
        
        /**
         * Type of photo being submitted.
         * Must be a GUEST_* type from CheckInPhotoType.
         */
        @NotNull(message = "Photo type is required")
        private CheckInPhotoType photoType;

        /**
         * Base64-encoded image data.
         * Maximum size: 10MB after decoding.
         */
        @NotNull(message = "Photo data is required")
        private String base64Data;

        /**
         * Original filename from device (optional).
         */
        private String originalFilename;

        /**
         * MIME type of the image.
         * Supported: image/jpeg, image/png, image/heic.
         */
        private String mimeType;

        /**
         * Client-side timestamp for this specific photo.
         */
        private Instant capturedAt;
    }
}
