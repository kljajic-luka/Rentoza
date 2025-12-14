package org.example.rentoza.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.car.CarDocument;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO for a single document in the admin review workflow.
 * 
 * Includes:
 * - Document type and status
 * - Upload and expiry dates
 * - Admin verification info (who verified, when)
 * - Rejection reason (if applicable)
 * - Signed URL for viewing
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentReviewDto {
    
    private Long id;
    
    private String type; // REGISTRATION, TECHNICAL_INSPECTION, LIABILITY_INSURANCE, AUTHORIZATION
    
    private String status; // PENDING, VERIFIED, REJECTED, EXPIRED_AUTO
    
    private LocalDateTime uploadDate;
    
    private LocalDate expiryDate;
    
    /**
     * Document is expired if expiryDate < today.
     * Calculated for display.
     */
    private Boolean isExpired;
    
    /**
     * Days until expiry (negative if expired).
     */
    private Long daysUntilExpiry;
    
    // ========== VERIFICATION INFO ==========
    
    /**
     * Admin name who verified (if status = VERIFIED).
     */
    private String verifiedByName;
    
    /**
     * When admin verified (if status = VERIFIED or REJECTED).
     */
    private LocalDateTime verifiedAt;
    
    /**
     * Rejection reason (if status = REJECTED).
     */
    private String rejectionReason;
    
    // ========== DOCUMENT ACCESS ==========
    
    /**
     * Signed URL for viewing document (PDF/image).
     * Valid for limited time only.
     */
    private String documentUrl;
    
    /**
     * SHA256 hash for integrity verification.
     */
    private String documentHash;
    
    /**
     * Original filename for display.
     */
    private String originalFilename;
    
    /**
     * MIME type (application/pdf, image/jpeg, etc.).
     */
    private String mimeType;
    
    /**
     * File size in bytes.
     */
    private Long fileSize;
    
    /**
     * Convert CarDocument entity to review DTO.
     */
    public static DocumentReviewDto fromEntity(CarDocument doc) {
        LocalDate today = LocalDate.now();
        boolean isExpired = doc.isExpired();
        long daysUntilExpiry = doc.getDaysUntilExpiry();
        
        return DocumentReviewDto.builder()
            .id(doc.getId())
            .type(doc.getType().name())
            .status(doc.getStatus().name())
            .uploadDate(doc.getUploadDate())
            .expiryDate(doc.getExpiryDate())
            .isExpired(isExpired)
            .daysUntilExpiry(daysUntilExpiry)
            .verifiedByName(doc.getVerifiedBy() != null 
                ? doc.getVerifiedBy().getFirstName() + " " + doc.getVerifiedBy().getLastName()
                : null)
            .verifiedAt(doc.getVerifiedAt())
            .rejectionReason(doc.getRejectionReason())
            // IMPORTANT: Never expose storage paths/keys to clients.
            .documentUrl("/api/admin/documents/" + doc.getId() + "/download")
            .documentHash(doc.getDocumentHash())
            .originalFilename(doc.getOriginalFilename())
            .mimeType(doc.getMimeType())
            .fileSize(doc.getFileSize())
            .build();
    }
}
