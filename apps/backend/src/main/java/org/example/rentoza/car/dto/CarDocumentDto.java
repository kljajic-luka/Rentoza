package org.example.rentoza.car.dto;

import org.example.rentoza.car.CarDocument;
import org.example.rentoza.car.DocumentType;
import org.example.rentoza.car.DocumentVerificationStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO for car document response.
 */
public record CarDocumentDto(
    Long id,
    DocumentType type,
    String typeSerbianName,
    String originalFilename,
    long fileSize,
    String mimeType,
    LocalDateTime uploadDate,
    LocalDate expiryDate,
    DocumentVerificationStatus status,
    String rejectionReason,
    boolean isExpired,
    long daysUntilExpiry,
    Long verifiedById,
    LocalDateTime verifiedAt
) {
    /**
     * Create DTO from entity.
     */
    public static CarDocumentDto from(CarDocument doc) {
        return new CarDocumentDto(
            doc.getId(),
            doc.getType(),
            doc.getType().getSerbianName(),
            doc.getOriginalFilename(),
            doc.getFileSize(),
            doc.getMimeType(),
            doc.getUploadDate(),
            doc.getExpiryDate(),
            doc.getStatus(),
            doc.getRejectionReason(),
            doc.isExpired(),
            doc.getDaysUntilExpiry(),
            doc.getVerifiedBy() != null ? doc.getVerifiedBy().getId() : null,
            doc.getVerifiedAt()
        );
    }
}
