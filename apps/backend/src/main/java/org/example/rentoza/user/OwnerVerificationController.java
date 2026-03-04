package org.example.rentoza.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.example.rentoza.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for owner identity verification (Serbian compliance).
 * 
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/users/me/owner-verification/individual - Submit JMBG</li>
 *   <li>POST /api/users/me/owner-verification/legal-entity - Submit PIB</li>
 *   <li>GET /api/users/me/owner-verification - Get status</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/users/me/owner-verification")
@RequiredArgsConstructor
public class OwnerVerificationController {
    
    private static final Logger log = LoggerFactory.getLogger(OwnerVerificationController.class);
    
    private final OwnerVerificationService verificationService;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    
    /**
     * Submit individual owner verification (JMBG).
     */
    @PostMapping("/individual")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<OwnerVerificationStatus> submitIndividualVerification(
            @Valid @RequestBody IndividualVerificationRequest request) {
        
        Long userId = currentUser.id();
        verificationService.submitIndividualVerification(
            userId, 
            request.jmbg(), 
            request.bankAccountNumber()
        );
        
        log.info("Individual verification submitted for userId={}", userId);
        return ResponseEntity.ok(getStatus(userId));
    }
    
    /**
     * Submit legal entity owner verification (PIB).
     */
    @PostMapping("/legal-entity")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<OwnerVerificationStatus> submitLegalEntityVerification(
            @Valid @RequestBody LegalEntityVerificationRequest request) {
        
        Long userId = currentUser.id();
        verificationService.submitLegalEntityVerification(
            userId, 
            request.pib(), 
            request.bankAccountNumber()
        );
        
        log.info("Legal entity verification submitted for userId={}", userId);
        return ResponseEntity.ok(getStatus(userId));
    }
    
    /**
     * Get current verification status.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<OwnerVerificationStatus> getVerificationStatus() {
        Long userId = currentUser.id();
        return ResponseEntity.ok(getStatus(userId));
    }
    
    private OwnerVerificationStatus getStatus(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return new OwnerVerificationStatus(
            user.getOwnerType(),
            user.getOwnerType() == OwnerType.INDIVIDUAL ? user.getMaskedJmbg() : user.getMaskedPib(),
            user.getIsIdentityVerified(),
            user.getIsIdentityVerified() ? "VERIFIED" : 
                (user.getJmbg() != null || user.getPib() != null ? "PENDING_REVIEW" : "NOT_SUBMITTED"),
            user.getIdentityVerifiedAt()
        );
    }
    
    // ==================== REQUEST/RESPONSE DTOs ====================
    
    public record IndividualVerificationRequest(
        @NotBlank(message = "JMBG is required")
        @Pattern(regexp = "^\\d{13}$", message = "JMBG must be exactly 13 digits")
        String jmbg,
        
        String bankAccountNumber
    ) {}
    
    public record LegalEntityVerificationRequest(
        @NotBlank(message = "PIB is required")
        @Pattern(regexp = "^\\d{9}$", message = "PIB must be exactly 9 digits")
        String pib,
        
        @NotBlank(message = "Bank account is required for legal entities")
        String bankAccountNumber
    ) {}
    
    public record OwnerVerificationStatus(
        OwnerType ownerType,
        String maskedId,
        boolean isVerified,
        String status,
        java.time.LocalDateTime verifiedAt
    ) {}
}
