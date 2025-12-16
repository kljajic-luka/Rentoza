package org.example.rentoza.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.document.RenterDocumentType;
import org.example.rentoza.user.dto.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;

/**
 * REST Controller for renter driver license verification.
 * 
 * <p>Endpoints for renters to:
 * <ul>
 *   <li>GET /api/users/me/verification - Get verification profile</li>
 *   <li>POST /api/users/me/verification/license/submit - Submit license document</li>
 *   <li>POST /api/users/me/verification/license/resubmit - Re-submit after rejection</li>
 *   <li>GET /api/users/me/verification/booking-eligible - Check booking eligibility</li>
 *   <li>GET /api/users/me/verification/status - Quick status check</li>
 * </ul>
 * 
 * <p>All endpoints require authentication and use the /me pattern to automatically
 * resolve the current authenticated user. No userId path variable needed.
 * 
 * <p>Admin endpoints for viewing/managing other users' verification data are in 
 * {@link org.example.rentoza.admin.controller.AdminRenterVerificationController}
 */
@RestController
@RequestMapping("/api/users/me/verification")
@Tag(name = "Renter Verification", description = "Driver license verification for booking eligibility")
@Slf4j
@RequiredArgsConstructor
public class RenterVerificationController {
    
    private final RenterVerificationService verificationService;
    private final CurrentUser currentUser;
    
    // ==================== PROFILE RETRIEVAL ====================
    
    @Operation(
        summary = "Get verification profile",
        description = "Returns complete verification status, documents, and booking eligibility for the current user"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RenterVerificationProfileDTO> getVerificationProfile() {
        Long userId = currentUser.id();
        log.debug("Getting verification profile for userId={}", userId);
        
        RenterVerificationProfileDTO profile = verificationService.getVerificationProfile(userId);
        return ResponseEntity.ok(profile);
    }
    
    // ==================== DOCUMENT SUBMISSION ====================
    
    /**
     * Submit both sides of the driver's license in a single call.
     * 
     * This is the PRIMARY endpoint used by the frontend. It accepts both the front and back
     * images of the license and processes them atomically. If either upload fails, the entire
     * operation is rolled back.
     * 
     * Frontend sends: { licenseFront: File, licenseBack: File, expiryDate?: string }
     */
    @Operation(
        summary = "Submit driver license (front and back)",
        description = "Upload both sides of the driver's license for verification in a single request. " +
                     "This is the primary submission endpoint used by the web UI. " +
                     "Returns 202 ACCEPTED immediately; document processing happens asynchronously. " +
                     "Poll GET /verification to check processing status."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "License documents accepted for processing",
            content = @Content(schema = @Schema(implementation = RenterVerificationProfileDTO.class))),
        @ApiResponse(responseCode = "400", description = "Invalid file(s) or request"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "403", description = "Cannot submit in current status (e.g., already approved)"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PostMapping(value = "/license/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RenterVerificationProfileDTO> submitLicense(
            @Parameter(description = "Front side of driver's license (JPEG/PNG, max 10MB)") 
            @RequestParam("licenseFront") MultipartFile licenseFront,
            @Parameter(description = "Back side of driver's license (JPEG/PNG, max 10MB)") 
            @RequestParam("licenseBack") MultipartFile licenseBack,
            @Parameter(description = "License expiry date (ISO format: YYYY-MM-DD)") 
            @RequestParam(value = "expiryDate", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
            @Parameter(description = "Optional selfie for liveness verification (JPEG/PNG, max 5MB)")
            @RequestParam(value = "selfie", required = false) MultipartFile selfie
    ) throws IOException {
        
        Long userId = currentUser.id();
        log.info("License submission (front+back): userId={}, expiryDate={}, hasSelfie={}", 
            userId, expiryDate, selfie != null);
        
        // Submit front side
        DriverLicenseSubmissionRequest frontRequest = DriverLicenseSubmissionRequest.builder()
            .documentType(RenterDocumentType.DRIVERS_LICENSE_FRONT)
            .expiryDate(expiryDate)
            .build();
        verificationService.submitDocument(userId, licenseFront, frontRequest);
        
        // Submit back side
        DriverLicenseSubmissionRequest backRequest = DriverLicenseSubmissionRequest.builder()
            .documentType(RenterDocumentType.DRIVERS_LICENSE_BACK)
            .expiryDate(expiryDate)
            .build();
        verificationService.submitDocument(userId, licenseBack, backRequest);
        
        // Submit selfie if provided (optional - enhances verification security)
        if (selfie != null && !selfie.isEmpty()) {
            DriverLicenseSubmissionRequest selfieRequest = DriverLicenseSubmissionRequest.builder()
                .documentType(RenterDocumentType.SELFIE)
                .build();
            verificationService.submitDocument(userId, selfie, selfieRequest);
            log.info("Selfie submitted for enhanced verification: userId={}", userId);
        }
        
        // Return updated profile with 202 ACCEPTED (async processing in progress)
        RenterVerificationProfileDTO profile = verificationService.getVerificationProfile(userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(profile);
    }
    
    /**
     * Submit a single document for verification.
     * 
     * This endpoint supports individual document uploads (e.g., selfie with license,
     * or uploading front/back separately). Use /license/submit for the standard flow
     * where both sides are uploaded together.
     */
    @Operation(
        summary = "Submit single document",
        description = "Upload a single document for verification (license front, back, selfie, etc.). " +
                     "Returns 202 ACCEPTED immediately; processing happens asynchronously."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Document accepted for processing"),
        @ApiResponse(responseCode = "400", description = "Invalid file or request"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "403", description = "Cannot submit in current status"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PostMapping(value = "/document/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RenterDocumentDTO> submitDocument(
            @Parameter(description = "Document image file (JPEG/PNG, max 10MB)") 
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Document type") 
            @RequestParam("documentType") RenterDocumentType documentType,
            @Parameter(description = "Document expiry date (required for license)") 
            @RequestParam(value = "expiryDate", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
            @Parameter(description = "License number (optional, can be OCR extracted)") 
            @RequestParam(value = "licenseNumber", required = false) String licenseNumber,
            @Parameter(description = "Issuing country (ISO 3166-1 alpha-3)") 
            @RequestParam(value = "licenseCountry", required = false) String licenseCountry,
            @Parameter(description = "License categories (e.g., 'B', 'B,C')") 
            @RequestParam(value = "licenseCategories", required = false) String licenseCategories
    ) throws IOException {
        
        Long userId = currentUser.id();
        
        DriverLicenseSubmissionRequest request = DriverLicenseSubmissionRequest.builder()
            .documentType(documentType)
            .expiryDate(expiryDate)
            .licenseNumber(licenseNumber)
            .licenseCountry(licenseCountry)
            .licenseCategories(licenseCategories)
            .build();
        
        log.info("Single document submission: userId={}, type={}", userId, documentType);
        
        RenterDocumentDTO result = verificationService.submitDocument(userId, file, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
    
    /**
     * Re-submit license documents after rejection.
     */
    @Operation(
        summary = "Re-submit license after rejection",
        description = "Submit corrected license documents after a previous rejection"
    )
    @PostMapping(value = "/license/resubmit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RenterVerificationProfileDTO> resubmitLicense(
            @RequestParam("licenseFront") MultipartFile licenseFront,
            @RequestParam("licenseBack") MultipartFile licenseBack,
            @RequestParam(value = "expiryDate", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate,
            @RequestParam(value = "selfie", required = false) MultipartFile selfie
    ) throws IOException {
        // Resubmission uses the same logic - service tracks via audit log
        return submitLicense(licenseFront, licenseBack, expiryDate, selfie);
    }
    
    // ==================== ELIGIBILITY CHECK ====================
    
    @Operation(
        summary = "Check booking eligibility",
        description = "Quick check if current user can book. Call before showing booking form to gate unverified users."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Eligibility check complete",
            content = @Content(schema = @Schema(implementation = BookingEligibilityDTO.class))),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping("/booking-eligible")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BookingEligibilityDTO> checkBookingEligibility(
            @Parameter(description = "Trip end date (to validate license doesn't expire before trip ends). Accepts ISO date (YYYY-MM-DD) or ISO datetime (YYYY-MM-DDTHH:mm:ss).") 
            @RequestParam(value = "tripEndDate", required = false) String tripEndDateStr
    ) {
        Long userId = currentUser.id();
        
        // Enterprise-grade: Accept both ISO date and ISO datetime formats
        LocalDate tripEndDate = parseDateFlexibly(tripEndDateStr, "tripEndDate");
        
        log.debug("Checking booking eligibility for userId={}, tripEndDate={}", userId, tripEndDate);
        
        BookingEligibilityDTO eligibility = verificationService.checkBookingEligibility(userId, tripEndDate);
        return ResponseEntity.ok(eligibility);
    }
    
    /**
     * Parse date string flexibly - accepts both ISO date (YYYY-MM-DD) and ISO datetime formats.
     * Enterprise-grade: Handles frontend inconsistencies where datetime is sent instead of date.
     * 
     * @param dateStr The date string to parse (nullable)
     * @param paramName Parameter name for error messages
     * @return Parsed LocalDate, or null if input is null/blank
     */
    private LocalDate parseDateFlexibly(String dateStr, String paramName) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        
        String trimmed = dateStr.trim();
        
        try {
            // Try ISO date first (YYYY-MM-DD)
            if (trimmed.length() == 10) {
                return LocalDate.parse(trimmed);
            }
            
            // Handle ISO datetime (YYYY-MM-DDTHH:mm:ss or YYYY-MM-DDTHH:mm:ss.SSS)
            if (trimmed.contains("T")) {
                // Extract just the date part
                String datePart = trimmed.split("T")[0];
                return LocalDate.parse(datePart);
            }
            
            // Fallback: try standard ISO parsing
            return LocalDate.parse(trimmed);
            
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("Failed to parse {} parameter '{}': {}", paramName, dateStr, e.getMessage());
            throw new IllegalArgumentException(
                String.format("Invalid date format for %s: '%s'. Expected ISO format (YYYY-MM-DD).", paramName, dateStr)
            );
        }
    }
    
    // ==================== SHORTCUT ENDPOINTS ====================
    
    @Operation(
        summary = "Get verification status (shortcut)",
        description = "Quick status check returning just the status enum and key flags"
    )
    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VerificationStatusResponse> getVerificationStatus() {
        Long userId = currentUser.id();
        
        RenterVerificationProfileDTO profile = verificationService.getVerificationProfile(userId);
        
        return ResponseEntity.ok(VerificationStatusResponse.builder()
            .status(profile.getStatus())
            .statusDisplay(profile.getStatusDisplay())
            .canBook(profile.isCanBook())
            .canSubmit(profile.isCanSubmit())
            .requiredDocumentsComplete(profile.isRequiredDocumentsComplete())
            .build());
    }
    
    // ==================== RESPONSE CLASSES ====================
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class VerificationStatusResponse {
        private DriverLicenseStatus status;
        private String statusDisplay;
        private boolean canBook;
        private boolean canSubmit;
        private boolean requiredDocumentsComplete;
    }
}
