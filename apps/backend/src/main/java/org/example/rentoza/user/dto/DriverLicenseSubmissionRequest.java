package org.example.rentoza.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.example.rentoza.user.document.RenterDocumentType;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * Request DTO for submitting driver license documents.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverLicenseSubmissionRequest {
    
    /**
     * Document type being submitted.
     */
    @NotNull(message = "Document type is required")
    private RenterDocumentType documentType;
    
    /**
     * Document expiry date (required for license documents).
     */
    private LocalDate expiryDate;
    
    /**
     * Driver license number (optional, can be OCR extracted).
     */
    private String licenseNumber;
    
    /**
     * Country that issued the license (ISO 3166-1 alpha-3).
     */
    private String licenseCountry;
    
    /**
     * License categories (e.g., "B", "B,C").
     */
    private String licenseCategories;
}
