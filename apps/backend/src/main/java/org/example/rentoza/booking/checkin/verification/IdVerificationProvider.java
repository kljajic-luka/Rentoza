package org.example.rentoza.booking.checkin.verification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.checkin.CheckInIdVerification.DocumentType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Interface for external ID verification providers.
 * 
 * <p>Implementations may integrate with:
 * <ul>
 *   <li>Onfido - Global identity verification</li>
 *   <li>Veriff - European-focused verification</li>
 *   <li>AWS Rekognition - Liveness detection</li>
 *   <li>Mock provider - For development/testing</li>
 * </ul>
 *
 * <h2>Verification Flow</h2>
 * <ol>
 *   <li>Guest initiates verification</li>
 *   <li>Frontend captures selfie and document photos</li>
 *   <li>Photos are submitted to this provider</li>
 *   <li>Provider returns liveness score, document data, and match result</li>
 * </ol>
 */
public interface IdVerificationProvider {

    /**
     * Get the provider name.
     * @return Provider identifier (e.g., "ONFIDO", "VERIFF", "MOCK")
     */
    String getProviderName();

    /**
     * Perform liveness detection on a selfie image.
     * 
     * @param selfieImageBytes The selfie image bytes
     * @param mimeType Image MIME type (e.g., "image/jpeg")
     * @return Liveness check result
     */
    LivenessResult checkLiveness(byte[] selfieImageBytes, String mimeType);

    /**
     * Extract document data via OCR.
     * 
     * @param frontImageBytes Front of document image
     * @param backImageBytes Back of document image (may be null)
     * @param mimeType Image MIME type
     * @return Extracted document data
     */
    DocumentExtraction extractDocumentData(byte[] frontImageBytes, byte[] backImageBytes, String mimeType);

    /**
     * Verify that the selfie matches the document photo.
     * 
     * @param selfieImageBytes Selfie image
     * @param documentImageBytes Document photo
     * @param mimeType Image MIME type
     * @return Face match result
     */
    FaceMatchResult matchFaces(byte[] selfieImageBytes, byte[] documentImageBytes, String mimeType);

    // ========== RESULT CLASSES ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class LivenessResult {
        private boolean passed;
        private BigDecimal score;
        private String errorCode;
        private String errorMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class DocumentExtraction {
        private boolean success;
        private DocumentType documentType;
        private String firstName;
        private String lastName;
        private String documentNumber;
        private LocalDate expiryDate;
        private String countryCode;
        private String errorCode;
        private String errorMessage;
        
        /**
         * Date of birth extracted from document.
         * CRITICAL: Used for age verification and booking eligibility.
         */
        private LocalDate dateOfBirth;
        
        /**
         * License categories (e.g., 'B', 'B,C', 'B+E').
         * Used to verify user can drive the booked vehicle type.
         */
        private String licenseCategories;
        
        /**
         * Issue date of the document.
         * Used to calculate license tenure for risk scoring.
         */
        private LocalDate issueDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class FaceMatchResult {
        private boolean matched;
        private BigDecimal confidence;
        private String errorCode;
        private String errorMessage;
    }
}


