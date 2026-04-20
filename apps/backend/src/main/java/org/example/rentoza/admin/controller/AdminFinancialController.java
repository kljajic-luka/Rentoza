package org.example.rentoza.admin.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.*;
import org.example.rentoza.admin.service.AdminFinancialService;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Admin Financial Controller for payout and escrow management.
 * 
 * <p><b>ENDPOINTS:</b>
 * <ul>
 *   <li>GET /api/admin/financial/payouts - Get payout queue</li>
 *   <li>GET /api/admin/financial/escrow - Get escrow balance</li>
 *   <li>POST /api/admin/financial/payouts/batch - Process batch payouts</li>
 *   <li>POST /api/admin/financial/payouts/{bookingId}/retry - Retry failed payout</li>
 * </ul>
 * 
 * <p><b>SECURITY:</b> All endpoints require ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/admin/financial")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminFinancialController {
    
    private final AdminFinancialService financialService;
    private final UserRepository userRepo;
    private final CurrentUser currentUser;
    
    /**
     * Get payout queue (hosts awaiting payment).
     * 
     * <p>Shows completed bookings past holding period (2 days).
     */
    @GetMapping("/payouts")
    public ResponseEntity<Page<PayoutQueueDto>> getPayoutQueue(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        
        log.debug("Admin {} requesting payout queue, page: {}", currentUser.id(), page);
        
        Pageable pageable = PageRequest.of(page, size);
        Page<PayoutQueueDto> payouts = financialService.getPayoutQueue(pageable);
        
        return ResponseEntity.ok(payouts);
    }
    
    /**
     * Get escrow balance summary.
     */
    @GetMapping("/escrow")
    public ResponseEntity<EscrowBalanceDto> getEscrowBalance() {
        log.debug("Admin {} requesting escrow balance", currentUser.id());
        
        EscrowBalanceDto balance = financialService.getEscrowBalance();
        
        return ResponseEntity.ok(balance);
    }
    
    /**
     * Process batch payouts to hosts.
     * 
     * <p><b>DRY RUN:</b> Set dryRun=true to validate without execution.
     * 
     * @param request Batch payout request with booking IDs
     * @return Batch result with success/failure counts
     */
    @PostMapping("/payouts/batch")
    public ResponseEntity<BatchPayoutResult> processBatchPayouts(
            @RequestBody BatchPayoutRequest request) {
        
        log.info("Admin {} processing batch payout: {} bookings, dryRun: {}", 
                 currentUser.id(), request.getBookingIds().size(), request.getDryRun());
        
        User admin = userRepo.findById(currentUser.id())
            .orElseThrow(() -> new RuntimeException("Admin user not found"));
        
        BatchPayoutResult result = financialService.processBatchPayouts(request, admin);
        
        log.info("Batch payout complete: {} success, {} failures", 
                 result.getSuccessCount(), result.getFailureCount());
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * Retry failed payout for specific booking.
     */
    @PostMapping("/payouts/{bookingId}/retry")
    public ResponseEntity<?> retryPayout(@PathVariable @Positive Long bookingId) {
        log.info("Admin {} retrying payout for booking {}", currentUser.id(), bookingId);
        
        User admin = userRepo.findById(currentUser.id())
            .orElseThrow(() -> new RuntimeException("Admin user not found"));
        
        try {
            financialService.retryPayout(bookingId, admin);
            return ResponseEntity.ok(java.util.Map.of("message", "Payout retry successful"));
        } catch (Exception e) {
            log.error("Payout retry failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }
}
