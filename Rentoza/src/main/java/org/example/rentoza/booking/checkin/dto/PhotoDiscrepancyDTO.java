package org.example.rentoza.booking.checkin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.checkin.CheckInPhotoType;
import org.example.rentoza.booking.checkin.PhotoDiscrepancy;

import java.time.Instant;

/**
 * DTO for photo discrepancy details.
 * 
 * <p>Used to present discrepancies in the admin dashboard and 
 * during check-in/checkout workflows for both parties.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PhotoDiscrepancyDTO {

    /**
     * Unique identifier for the discrepancy.
     */
    private Long id;

    /**
     * Booking ID this discrepancy belongs to.
     */
    private Long bookingId;

    /**
     * Context type (CHECK_IN or CHECK_OUT).
     */
    private String discrepancyType;

    /**
     * Photo type where discrepancy was detected.
     */
    private CheckInPhotoType photoType;

    /**
     * Human-readable description.
     */
    private String description;

    /**
     * Severity level (LOW, MEDIUM, HIGH, CRITICAL).
     */
    private String severity;

    /**
     * Current resolution status.
     */
    private String resolutionStatus;

    /**
     * Whether this discrepancy blocks the workflow.
     */
    private boolean blocksWorkflow;

    /**
     * URL to host's photo for comparison.
     */
    private String hostPhotoUrl;

    /**
     * URL to guest's photo for comparison.
     */
    private String guestPhotoUrl;

    /**
     * ID of host's photo.
     */
    private Long hostPhotoId;

    /**
     * ID of guest's photo.
     */
    private Long guestPhotoId;

    /**
     * AI detection confidence (if AI-detected).
     */
    private Double aiConfidenceScore;

    /**
     * When the discrepancy was detected.
     */
    private Instant createdAt;

    /**
     * When the discrepancy was resolved (if resolved).
     */
    private Instant resolvedAt;

    /**
     * Email of admin who resolved (if resolved).
     */
    private String resolvedByEmail;

    /**
     * Resolution notes.
     */
    private String resolutionNotes;

    /**
     * Convert from entity to DTO.
     */
    public static PhotoDiscrepancyDTO fromEntity(
            PhotoDiscrepancy entity, 
            String hostPhotoUrl, 
            String guestPhotoUrl) {
        
        return PhotoDiscrepancyDTO.builder()
            .id(entity.getId())
            .bookingId(entity.getBooking().getId())
            .discrepancyType(entity.getDiscrepancyType().name())
            .photoType(entity.getPhotoType())
            .description(entity.getDescription())
            .severity(entity.getSeverity().name())
            .resolutionStatus(entity.getResolutionStatus().name())
            .blocksWorkflow(entity.blocksHandover())
            .hostPhotoUrl(hostPhotoUrl)
            .guestPhotoUrl(guestPhotoUrl)
            .hostPhotoId(entity.getHostPhoto() != null ? entity.getHostPhoto().getId() : null)
            .guestPhotoId(entity.getGuestPhotoId())
            .aiConfidenceScore(entity.getAiConfidenceScore() != null 
                ? entity.getAiConfidenceScore().doubleValue() : null)
            .createdAt(entity.getCreatedAt())
            .resolvedAt(entity.getResolvedAt())
            .resolvedByEmail(entity.getResolvedBy() != null 
                ? entity.getResolvedBy().getEmail() : null)
            .resolutionNotes(entity.getResolutionNotes())
            .build();
    }
}
