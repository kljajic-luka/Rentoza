package org.example.rentoza.booking.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.checkin.dto.CheckInPhotoDTO;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO for host checkout photo submission.
 * 
 * <p>Returns the results of processing host checkout photos, including:
 * <ul>
 *   <li>Which photos were accepted/rejected</li>
 *   <li>EXIF validation results</li>
 *   <li>Completion status</li>
 *   <li>Any detected discrepancies with guest checkout photos</li>
 *   <li>Comparison with check-in photos for damage detection</li>
 * </ul>
 * 
 * @see HostCheckoutPhotoSubmissionDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HostCheckoutPhotoResponseDTO {

    /**
     * Overall success status.
     * true if at least one photo was accepted.
     */
    private boolean success;

    /**
     * HTTP status code for the response.
     */
    private int httpStatus;

    /**
     * User-friendly message in Serbian.
     */
    private String userMessage;

    /**
     * List of processed photos with individual results.
     */
    private List<CheckInPhotoDTO> processedPhotos;

    /**
     * Count of accepted photos.
     */
    private int acceptedCount;

    /**
     * Count of rejected photos.
     */
    private int rejectedCount;

    /**
     * Whether all required photos have been submitted.
     */
    private boolean allRequiredPhotosSubmitted;

    /**
     * Whether the host checkout photo requirement is complete.
     */
    private boolean hostCheckoutPhotosComplete;

    /**
     * Number of required photo types still missing.
     */
    private int missingRequiredCount;

    /**
     * List of missing required photo types.
     */
    private List<String> missingPhotoTypes;

    /**
     * Discrepancies detected when comparing with guest checkout photos.
     */
    private List<PhotoDiscrepancySummaryDTO> checkoutDiscrepancies;

    /**
     * Comparison with check-in photos showing changes during rental.
     */
    private List<ConditionComparisonDTO> conditionChanges;

    /**
     * Whether new damage was detected (either by host report or photo analysis).
     */
    private boolean newDamageDetected;

    /**
     * Timestamp when processing completed.
     */
    private Instant processedAt;

    /**
     * Session ID for this batch of uploads.
     */
    private String sessionId;

    /**
     * Summary of a detected discrepancy.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhotoDiscrepancySummaryDTO {
        
        private Long discrepancyId;
        private String photoType;
        private String severity;
        private String description;
        private boolean blocksCheckout;
    }

    /**
     * Comparison between check-in and checkout photos.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConditionComparisonDTO {
        
        /**
         * Photo type being compared (e.g., EXTERIOR_FRONT).
         */
        private String photoType;

        /**
         * URL to check-in photo for this angle.
         */
        private String checkInPhotoUrl;

        /**
         * URL to checkout photo for this angle.
         */
        private String checkOutPhotoUrl;

        /**
         * Whether a change was detected.
         */
        private boolean changeDetected;

        /**
         * Description of the detected change.
         */
        private String changeDescription;

        /**
         * Severity of the change (NONE, MINOR, MODERATE, SEVERE).
         */
        private String changeSeverity;
    }

    // ========== Factory Methods ==========

    /**
     * Create a success response when all photos were processed.
     */
    public static HostCheckoutPhotoResponseDTO success(
            List<CheckInPhotoDTO> processedPhotos,
            int acceptedCount,
            int rejectedCount,
            boolean complete,
            String sessionId) {
        
        return HostCheckoutPhotoResponseDTO.builder()
            .success(true)
            .httpStatus(acceptedCount > 0 ? 201 : 400)
            .userMessage(complete 
                ? "Sve fotografije povratka su uspešno sačuvane." 
                : "Fotografije su obrađene. Neki snimci nedostaju.")
            .processedPhotos(processedPhotos)
            .acceptedCount(acceptedCount)
            .rejectedCount(rejectedCount)
            .hostCheckoutPhotosComplete(complete)
            .processedAt(Instant.now())
            .sessionId(sessionId)
            .build();
    }

    /**
     * Create an error response when processing failed.
     */
    public static HostCheckoutPhotoResponseDTO error(String errorMessage) {
        return HostCheckoutPhotoResponseDTO.builder()
            .success(false)
            .httpStatus(500)
            .userMessage(errorMessage)
            .processedAt(Instant.now())
            .build();
    }
}
