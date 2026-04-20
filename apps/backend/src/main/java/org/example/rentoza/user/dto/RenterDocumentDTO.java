package org.example.rentoza.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.example.rentoza.car.DocumentVerificationStatus;
import org.example.rentoza.user.document.RenterDocument;
import org.example.rentoza.user.document.RenterDocumentType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Renter document response DTO.
 * 
 * <p>Used for:
 * <ul>
 *   <li>Document list in verification profile</li>
 *   <li>Admin review queue items</li>
 *   <li>Document upload response</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RenterDocumentDTO {
    
    /**
     * Document ID.
     */
    private Long id;
    
    /**
     * Document type.
     */
    private RenterDocumentType type;
    
    /**
     * Serbian display name for type.
     */
    private String typeDisplay;
    
    /**
     * Original filename.
     */
    private String filename;
    
    /**
     * When uploaded.
     */
    private LocalDateTime uploadedAt;
    
    /**
     * Document expiry date (if applicable).
     */
    private LocalDate expiryDate;
    
    /**
     * Whether document is expired.
     */
    private boolean expired;
    
    /**
     * Verification status.
     */
    private DocumentVerificationStatus status;
    
    /**
     * Status display name.
     */
    private String statusDisplay;
    
    /**
     * Rejection reason (if rejected).
     */
    private String rejectionReason;
    
    // ==================== PROCESSING INFO ====================
    
    /**
     * Processing status (PENDING, PROCESSING, COMPLETED, FAILED).
     */
    private RenterDocument.ProcessingStatus processingStatus;
    
    /**
     * Processing error message (if failed).
     */
    private String processingError;
    
    // ==================== OCR RESULTS (Admin View) ====================
    
    /**
     * OCR confidence score (0-100%).
     */
    private Integer ocrConfidencePercent;
    
    /**
     * OCR extracted name (for name matching).
     */
    private String ocrExtractedName;
    
    /**
     * OCR extracted document number.
     */
    private String ocrExtractedNumber;
    
    /**
     * OCR extracted expiry date.
     */
    private LocalDate ocrExtractedExpiry;
    
    // ==================== BIOMETRIC RESULTS (Admin View) ====================
    
    /**
     * Whether liveness check passed.
     */
    private Boolean livenessPassed;
    
    /**
     * Face match score (0-100%).
     */
    private Integer faceMatchPercent;
    
    /**
     * Name match score (0-100%).
     */
    private Integer nameMatchPercent;
    
    // ==================== ADMIN VIEW ====================
    
    /**
     * User ID (for admin context).
     */
    private Long userId;
    
    /**
     * User name (for admin context).
     */
    private String userName;
    
    // ==================== FACTORY METHOD ====================
    
    /**
     * Create DTO from entity (basic view, no sensitive data).
     */
    public static RenterDocumentDTO fromEntity(RenterDocument doc) {
        return RenterDocumentDTO.builder()
            .id(doc.getId())
            .type(doc.getType())
            .typeDisplay(doc.getType().getSerbianName())
            .filename(doc.getOriginalFilename())
            .uploadedAt(doc.getUploadDate())
            .expiryDate(doc.getExpiryDate())
            .expired(doc.isExpired())
            .status(doc.getStatus())
            .statusDisplay(mapStatusDisplay(doc.getStatus()))
            .rejectionReason(doc.getRejectionReason())
            .processingStatus(doc.getProcessingStatus())
            .processingError(doc.getProcessingError())
            .build();
    }
    
    /**
     * Create DTO from entity (admin view with OCR/biometric data).
     */
    public static RenterDocumentDTO fromEntityForAdmin(RenterDocument doc) {
        RenterDocumentDTO dto = fromEntity(doc);
        dto.setUserId(doc.getUser().getId());
        dto.setUserName(doc.getUser().getFirstName() + " " + doc.getUser().getLastName());
        
        // OCR results
        if (doc.getOcrConfidence() != null) {
            dto.setOcrConfidencePercent(doc.getOcrConfidence().multiply(BigDecimal.valueOf(100)).intValue());
        }
        
        // Biometric results
        dto.setLivenessPassed(doc.getLivenessPassed());
        if (doc.getFaceMatchScore() != null) {
            dto.setFaceMatchPercent(doc.getFaceMatchScore().multiply(BigDecimal.valueOf(100)).intValue());
        }
        if (doc.getNameMatchScore() != null) {
            dto.setNameMatchPercent(doc.getNameMatchScore().multiply(BigDecimal.valueOf(100)).intValue());
        }
        
        return dto;
    }
    
    private static String mapStatusDisplay(DocumentVerificationStatus status) {
        return switch (status) {
            case PENDING -> "Čeka pregled";
            case VERIFIED -> "Verifikovano";
            case REJECTED -> "Odbijeno";
            case EXPIRED_AUTO -> "Isteklo";
        };
    }
}
