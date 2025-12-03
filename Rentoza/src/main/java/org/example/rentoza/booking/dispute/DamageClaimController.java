package org.example.rentoza.booking.dispute;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.dispute.dto.DamageClaimDTO;
import org.example.rentoza.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST controller for damage claims and dispute resolution.
 * 
 * <h2>Endpoints</h2>
 * <pre>
 * GET  /api/damage-claims                        - Get user's claims
 * GET  /api/damage-claims/{id}                   - Get specific claim
 * GET  /api/damage-claims/booking/{bookingId}    - Get claim by booking
 * POST /api/damage-claims/{id}/accept            - Guest accepts claim
 * POST /api/damage-claims/{id}/dispute           - Guest disputes claim
 * 
 * Admin endpoints:
 * GET  /api/admin/damage-claims/review           - Get claims awaiting review
 * POST /api/admin/damage-claims/{id}/approve     - Admin approves claim
 * POST /api/admin/damage-claims/{id}/reject      - Admin rejects claim
 * </pre>
 */
@RestController
@Slf4j
public class DamageClaimController {

    private final DamageClaimService claimService;
    private final CurrentUser currentUser;

    public DamageClaimController(DamageClaimService claimService, CurrentUser currentUser) {
        this.claimService = claimService;
        this.currentUser = currentUser;
    }

    // ========== USER ENDPOINTS ==========

    @GetMapping("/api/damage-claims")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DamageClaimDTO>> getMyClaims() {
        Long userId = currentUser.id();
        List<DamageClaimDTO> claims = claimService.getClaimsForUser(userId);
        return ResponseEntity.ok(claims);
    }

    @GetMapping("/api/damage-claims/booking/{bookingId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DamageClaimDTO> getClaimByBooking(@PathVariable Long bookingId) {
        Long userId = currentUser.id();
        DamageClaimDTO claim = claimService.getClaimByBooking(bookingId, userId);
        return ResponseEntity.ok(claim);
    }

    @PostMapping("/api/damage-claims/{id}/accept")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DamageClaimDTO> acceptClaim(
            @PathVariable Long id,
            @RequestBody(required = false) GuestResponseRequest request) {
        
        Long userId = currentUser.id();
        log.info("[DamageClaim] User {} accepting claim {}", userId, id);
        
        String response = request != null ? request.getResponse() : null;
        DamageClaimDTO claim = claimService.acceptClaim(id, response, userId);
        return ResponseEntity.ok(claim);
    }

    @PostMapping("/api/damage-claims/{id}/dispute")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DamageClaimDTO> disputeClaim(
            @PathVariable Long id,
            @Valid @RequestBody GuestResponseRequest request) {
        
        Long userId = currentUser.id();
        log.info("[DamageClaim] User {} disputing claim {}", userId, id);
        
        DamageClaimDTO claim = claimService.disputeClaim(id, request.getResponse(), userId);
        return ResponseEntity.ok(claim);
    }

    // ========== ADMIN ENDPOINTS ==========

    @GetMapping("/api/admin/damage-claims/review")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<DamageClaimDTO>> getClaimsAwaitingReview() {
        List<DamageClaimDTO> claims = claimService.getClaimsAwaitingReview();
        return ResponseEntity.ok(claims);
    }

    @PostMapping("/api/admin/damage-claims/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DamageClaimDTO> adminApprove(
            @PathVariable Long id,
            @Valid @RequestBody AdminApproveRequest request) {
        
        Long adminId = currentUser.id();
        log.info("[DamageClaim] Admin {} approving claim {} for {} RSD", adminId, id, request.getApprovedAmount());
        
        DamageClaimDTO claim = claimService.adminApprove(id, request.getApprovedAmount(), request.getNotes(), adminId);
        return ResponseEntity.ok(claim);
    }

    @PostMapping("/api/admin/damage-claims/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DamageClaimDTO> adminReject(
            @PathVariable Long id,
            @Valid @RequestBody AdminRejectRequest request) {
        
        Long adminId = currentUser.id();
        log.info("[DamageClaim] Admin {} rejecting claim {}", adminId, id);
        
        DamageClaimDTO claim = claimService.adminReject(id, request.getNotes(), adminId);
        return ResponseEntity.ok(claim);
    }

    // ========== REQUEST DTOs ==========

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GuestResponseRequest {
        @NotBlank(message = "Odgovor je obavezan")
        private String response;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminApproveRequest {
        @NotNull(message = "Iznos je obavezan")
        @Positive(message = "Iznos mora biti pozitivan")
        private BigDecimal approvedAmount;
        
        private String notes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminRejectRequest {
        @NotBlank(message = "Obrazloženje je obavezno")
        private String notes;
    }

    // ========== EXCEPTION HANDLERS ==========

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("[DamageClaim] Illegal state: {}", ex.getMessage());
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "error", "INVALID_STATE",
            "message", ex.getMessage()
        ));
    }
}


