package org.example.rentoza.booking.checkout.dto;

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
 * Request DTO for host checkout photo submission.
 * 
 * <p>When the vehicle is returned, the host captures photos to verify
 * the return condition matches what the guest documented. This creates
 * bilateral photographic evidence for any damage disputes.
 * 
 * <h2>Required Photos</h2>
 * <ul>
 *   <li>HOST_CHECKOUT_EXTERIOR_FRONT - Front of vehicle</li>
 *   <li>HOST_CHECKOUT_EXTERIOR_REAR - Rear of vehicle</li>
 *   <li>HOST_CHECKOUT_EXTERIOR_LEFT - Left side</li>
 *   <li>HOST_CHECKOUT_EXTERIOR_RIGHT - Right side</li>
 *   <li>HOST_CHECKOUT_INTERIOR_DASHBOARD - Dashboard/interior front</li>
 *   <li>HOST_CHECKOUT_INTERIOR_REAR - Rear seats</li>
 *   <li>HOST_CHECKOUT_ODOMETER - Odometer reading</li>
 *   <li>HOST_CHECKOUT_FUEL_GAUGE - Fuel gauge reading</li>
 * </ul>
 * 
 * <h2>Optional Photos</h2>
 * <ul>
 *   <li>HOST_CHECKOUT_DAMAGE_EVIDENCE - New damage found during return</li>
 *   <li>HOST_CHECKOUT_CUSTOM - Any additional photos</li>
 * </ul>
 * 
 * @see HostCheckoutPhotoResponseDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HostCheckoutPhotoSubmissionDTO {

    /**
     * List of photos being submitted.
     * Each photo includes type and base64-encoded image data.
     */
    @NotEmpty(message = "At least one photo is required")
    private List<PhotoItem> photos;

    /**
     * Client-side timestamp when photos were captured.
     * Used for offline support.
     */
    private Instant clientCapturedAt;

    /**
     * Whether the host confirms the vehicle was returned in acceptable condition.
     */
    private Boolean conditionAccepted;

    /**
     * Optional notes from host about return condition.
     */
    private String conditionNotes;

    /**
     * Whether the host is reporting new damage.
     */
    private Boolean newDamageReported;

    /**
     * Description of new damage (if reporting).
     */
    private String damageDescription;

    /**
     * Estimated damage cost in RSD (if reporting).
     */
    private Integer estimatedDamageCostRsd;

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
         * Must be a HOST_CHECKOUT_* type from CheckInPhotoType.
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

        /**
         * Whether this photo documents damage.
         */
        private Boolean isDamagePhoto;
    }
}
