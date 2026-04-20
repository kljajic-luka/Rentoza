package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarDocument;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Detailed car review DTO for admin document verification workflow.
 * 
 * Includes:
 * - Car overview (brand, model, year, photos)
 * - Owner info with identity verification status
 * - All compliance documents with verification state
 * - Key expiry dates (registration, tech inspection, insurance)
 * - Pre-calculated approval state
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCarReviewDetailDto {
    
    // ========== CAR OVERVIEW ==========
    private Long carId;
    private String brand;
    private String model;
    private Integer year;
    private String location;
    
    // ========== OWNER INFO ==========
    private Long ownerId;
    private String ownerName;
    private String ownerEmail;
    
    // Owner identity verification (Serbian compliance)
    private Boolean ownerIdentityVerified;
    private String ownerType; // INDIVIDUAL, LEGAL_ENTITY
    
    // ========== COMPLIANCE DATES ==========
    private LocalDate registrationExpiryDate;
    private LocalDate technicalInspectionDate;
    private LocalDate technicalInspectionExpiryDate;
    private LocalDate insuranceExpiryDate;
    
    // ========== DOCUMENTS ==========
    private List<DocumentReviewDto> documents;
    
    // ========== CAR PHOTOS ==========
    private List<String> imageUrls; // Base64 or URLs for viewing
    
    // ========== APPROVAL STATE ==========
    private ApprovalStateDto approvalState;
    
    // ========== TIMESTAMPS ==========
    private Instant createdAt;
    
    /**
     * Convert Car entity to review detail DTO.
     * Includes all documents and pre-calculated approval state.
     */
    public static AdminCarReviewDetailDto fromEntity(Car car) {
        List<DocumentReviewDto> documents = car.getDocuments() != null
            ? car.getDocuments().stream()
                .map(DocumentReviewDto::fromEntity)
                .collect(Collectors.toList())
            : List.of();

        return fromEntity(car, documents);
    }

    /**
     * Convert Car entity + explicitly loaded document DTOs to review detail DTO.
     *
     * <p>Use this in services when documents were fetched via dedicated queries
     * (e.g., JOIN FETCH) to avoid LazyInitializationException and N+1.
     */
    public static AdminCarReviewDetailDto fromEntity(Car car, List<DocumentReviewDto> documents) {
        List<DocumentReviewDto> safeDocuments = documents != null ? documents : List.of();

        ApprovalStateDto approvalState = calculateApprovalState(car, safeDocuments);

        List<String> imageUrls = car.getImageUrls() != null ? car.getImageUrls() : List.of();

        return AdminCarReviewDetailDto.builder()
            .carId(car.getId())
            .brand(car.getBrand())
            .model(car.getModel())
            .year(car.getYear())
            .location(car.getEffectiveCity())
            .ownerId(car.getOwner() != null ? car.getOwner().getId() : null)
            .ownerName(car.getOwner() != null
                ? car.getOwner().getFirstName() + " " + car.getOwner().getLastName()
                : null)
            .ownerEmail(car.getOwner() != null ? car.getOwner().getEmail() : null)
            .ownerIdentityVerified(car.getOwner() != null
                ? car.getOwner().getIsIdentityVerified()
                : false)
            .ownerType(car.getOwner() != null && car.getOwner().getOwnerType() != null
                ? car.getOwner().getOwnerType().toString()
                : null)
            .registrationExpiryDate(car.getRegistrationExpiryDate())
            .technicalInspectionDate(car.getTechnicalInspectionDate())
            .technicalInspectionExpiryDate(car.getTechnicalInspectionExpiryDate())
            .insuranceExpiryDate(car.getInsuranceExpiryDate())
            .documents(safeDocuments)
            .imageUrls(imageUrls)
            .approvalState(approvalState)
            .createdAt(car.getCreatedAt())
            .build();
    }
    
    /**
     * Calculate approval state based on documents and dates.
     * Determines: missing docs, unverified docs, expired docs, whether approval is possible.
     */
    private static ApprovalStateDto calculateApprovalState(Car car, List<DocumentReviewDto> documents) {
        boolean ownerVerified = car.getOwner() != null && car.getOwner().getIsIdentityVerified();
        
        List<String> missingDocuments = documents.stream()
            .filter(doc -> "PENDING".equals(doc.getStatus()) || "REJECTED".equals(doc.getStatus()))
            .map(DocumentReviewDto::getType)
            .collect(Collectors.toList());
        
        List<String> unverifiedDocuments = documents.stream()
            .filter(doc -> !"VERIFIED".equals(doc.getStatus()))
            .map(DocumentReviewDto::getType)
            .collect(Collectors.toList());
        
        List<String> expiredDocuments = documents.stream()
            .filter(doc -> isExpired(doc.getExpiryDate()))
            .map(DocumentReviewDto::getType)
            .collect(Collectors.toList());
        
        boolean registrationValid = car.getRegistrationExpiryDate() != null 
            && car.getRegistrationExpiryDate().isAfter(LocalDate.now());
        
        boolean techInspectionValid = car.getTechnicalInspectionExpiryDate() != null 
            && car.getTechnicalInspectionExpiryDate().isAfter(LocalDate.now());
        
        boolean insuranceValid = car.getInsuranceExpiryDate() != null 
            && car.getInsuranceExpiryDate().isAfter(LocalDate.now());
        
        // All required documents must be verified, owner verified, dates valid
        boolean canApprove = ownerVerified 
            && unverifiedDocuments.isEmpty() 
            && expiredDocuments.isEmpty()
            && registrationValid 
            && techInspectionValid 
            && insuranceValid;
        
        return ApprovalStateDto.builder()
            .ownerVerified(ownerVerified)
            .missingDocuments(missingDocuments)
            .unverifiedDocuments(unverifiedDocuments)
            .expiredDocuments(expiredDocuments)
            .registrationValid(registrationValid)
            .techInspectionValid(techInspectionValid)
            .insuranceValid(insuranceValid)
            .canApprove(canApprove)
            .build();
    }
    
    private static boolean isExpired(LocalDate expiryDate) {
        return expiryDate != null && !expiryDate.isAfter(LocalDate.now());
    }
    
    /**
     * Summary of approval blockers.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalStateDto {
        private Boolean ownerVerified;
        private List<String> missingDocuments; // Types that are missing/not uploaded
        private List<String> unverifiedDocuments; // Types that are PENDING
        private List<String> expiredDocuments; // Types with expired dates
        private Boolean registrationValid;
        private Boolean techInspectionValid;
        private Boolean insuranceValid;
        private Boolean canApprove; // Pre-calculated: true if all criteria met
    }
}
