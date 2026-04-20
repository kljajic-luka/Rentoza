package org.example.rentoza.booking.checkin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO for guest check-in photo submission.
 * 
 * <p>Returns the results of processing guest photos, including:
 * <ul>
 *   <li>Which photos were accepted/rejected</li>
 *   <li>EXIF validation results</li>
 *   <li>Completion status (all required photos present)</li>
 *   <li>Any detected discrepancies with host photos</li>
 * </ul>
 * 
 * @see GuestCheckInPhotoSubmissionDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GuestCheckInPhotoResponseDTO {

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
     * Whether all required photos have been submitted (not necessarily accepted).
     */
    private boolean allRequiredPhotosSubmitted;

    /**
     * Whether the guest check-in photo requirement is complete.
     * true if all required photos are accepted.
     */
    private boolean guestPhotosComplete;

    /**
     * Number of required photo types still missing.
     */
    private int missingRequiredCount;

    /**
     * List of missing required photo types.
     */
    private List<String> missingPhotoTypes;

    /**
     * Discrepancies detected when comparing with host photos.
     */
    private List<PhotoDiscrepancySummaryDTO> detectedDiscrepancies;

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
        
        /**
         * ID of the discrepancy record.
         */
        private Long discrepancyId;

        /**
         * Photo type where discrepancy was detected.
         */
        private String photoType;

        /**
         * Severity level (LOW, MEDIUM, HIGH, CRITICAL).
         */
        private String severity;

        /**
         * Brief description of the discrepancy.
         */
        private String description;

        /**
         * Whether this discrepancy blocks handover.
         */
        private boolean blocksHandover;
    }

    // ========== Factory Methods ==========

    /**
     * Create a success response when all photos were processed.
     */
    public static GuestCheckInPhotoResponseDTO success(
            List<CheckInPhotoDTO> processedPhotos,
            int acceptedCount,
            int rejectedCount,
            boolean complete,
            String sessionId) {
        
        return GuestCheckInPhotoResponseDTO.builder()
            .success(true)
            .httpStatus(acceptedCount > 0 ? 201 : 400)
            .userMessage(complete 
                ? "Sve fotografije su uspešno sačuvane." 
                : "Fotografije su obrađene. Neki snimci nedostaju.")
            .processedPhotos(processedPhotos)
            .acceptedCount(acceptedCount)
            .rejectedCount(rejectedCount)
            .guestPhotosComplete(complete)
            .processedAt(Instant.now())
            .sessionId(sessionId)
            .build();
    }

    /**
     * Create an error response when processing failed.
     */
    public static GuestCheckInPhotoResponseDTO error(String errorMessage) {
        return GuestCheckInPhotoResponseDTO.builder()
            .success(false)
            .httpStatus(500)
            .userMessage(errorMessage)
            .processedAt(Instant.now())
            .build();
    }
}
