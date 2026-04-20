package org.example.rentoza.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.RiskLevel;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Renter verification profile response DTO.
 * 
 * <p>Contains complete verification status and document list for renter.
 * Used by:
 * <ul>
 *   <li>Profile page verification status display</li>
 *   <li>Booking eligibility checking</li>
 *   <li>Admin review dashboard</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RenterVerificationProfileDTO {
    
    // ==================== USER INFO ====================
    
    /**
     * User ID.
     */
    private Long userId;
    
    /**
     * User full name (for admin display).
     */
    private String fullName;
    
    /**
     * User email (for admin display).
     */
    private String email;
    
    // ==================== LICENSE STATUS ====================
    
    /**
     * Current driver license verification status.
     */
    private DriverLicenseStatus status;
    
    /**
     * Serbian display name for status.
     */
    private String statusDisplay;
    
    /**
     * Whether user can currently book a car.
     */
    private boolean canBook;
    
    /**
     * If cannot book, reason why.
     */
    private String bookingBlockedReason;
    
    // ==================== LICENSE DETAILS ====================
    
    /**
     * Masked license number for display.
     */
    private String maskedLicenseNumber;
    
    /**
     * License expiry date.
     */
    private LocalDate licenseExpiryDate;
    
    /**
     * Days until license expires (null if no expiry).
     */
    private Long daysUntilExpiry;
    
    /**
     * Warning if license expires soon (within 30 days).
     */
    private boolean expiryWarning;
    
    /**
     * Country that issued license (ISO 3166-1 alpha-3).
     */
    private String licenseCountry;
    
    /**
     * License categories (e.g., "B", "B,C").
     */
    private String licenseCategories;
    
    /**
     * How long user has held license (months).
     */
    private Integer licenseTenureMonths;
    
    // ==================== VERIFICATION TIMESTAMPS ====================
    
    /**
     * When verification was submitted.
     */
    private LocalDateTime submittedAt;
    
    /**
     * When verification was approved/rejected.
     */
    private LocalDateTime verifiedAt;
    
    /**
     * Who verified (admin name for audit).
     */
    private String verifiedByName;
    
    // ==================== RISK & SCORING ====================
    
    /**
     * User's risk level.
     */
    private RiskLevel riskLevel;
    
    /**
     * Risk level display name.
     */
    private String riskLevelDisplay;
    
    // ==================== DOCUMENTS ====================
    
    /**
     * List of uploaded documents with status.
     */
    private List<RenterDocumentDTO> documents;
    
    /**
     * Whether all required documents are submitted.
     */
    private boolean requiredDocumentsComplete;
    
    /**
     * List of missing required document types.
     */
    private List<String> missingDocuments;
    
    // ==================== UI HELPERS ====================
    
    /**
     * Estimated wait time for review (e.g., "5-30 minutes").
     */
    private String estimatedWaitTime;
    
    /**
     * Whether user can submit/resubmit documents.
     */
    private boolean canSubmit;
    
    /**
     * Rejection reason (if rejected).
     */
    private String rejectionReason;
    
    /**
     * Next steps for user (UI guidance).
     */
    private String nextSteps;
}
