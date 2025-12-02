package org.example.rentoza.booking.checkin.verification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.checkin.CheckInIdVerification.DocumentType;
import org.example.rentoza.booking.checkin.IdVerificationStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for ID verification status response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdVerificationStatusDTO {
    
    private Long verificationId;
    private Long bookingId;
    
    // ========== STATUS ==========
    
    private IdVerificationStatus status;
    private String statusMessage;
    private boolean canProceed;
    private boolean needsManualReview;
    
    // ========== LIVENESS ==========
    
    private boolean livenessPassed;
    private BigDecimal livenessScore;
    private int livenessAttempts;
    private int maxLivenessAttempts;
    
    // ========== DOCUMENT ==========
    
    private DocumentType documentType;
    private String documentCountry;
    private boolean documentExpiryValid;
    
    // ========== NAME MATCH ==========
    
    private boolean nameMatchPassed;
    private BigDecimal nameMatchScore;
    
    // ========== TIMESTAMPS ==========
    
    private LocalDateTime createdAt;
    private LocalDateTime verifiedAt;
    
    // ========== NEXT STEPS ==========
    
    private String nextStep;
    private String[] requiredActions;
}

