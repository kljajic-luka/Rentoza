package org.example.rentoza.admin.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.*;
import org.example.rentoza.admin.dto.enums.DisputeSeverity;
import org.example.rentoza.admin.service.AdminDisputeService;
import org.example.rentoza.booking.dispute.DamageClaimStatus;
import org.example.rentoza.config.HateoasAssembler;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDateTime;

import static org.springframework.data.domain.Sort.Direction.DESC;

@RestController
@RequestMapping("/api/admin/disputes")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class AdminDisputeController {

    private final AdminDisputeService disputeService;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final HateoasAssembler hateoasAssembler;

    /**
     * GET /api/admin/disputes
     * List open disputes with filtering and pagination with HATEOAS links.
     */
    @GetMapping
    public PagedModel<EntityModel<AdminDisputeListDto>> listDisputes(
            @RequestParam(required = false) DamageClaimStatus status,
            @RequestParam(required = false) DisputeSeverity severity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable) {

        DisputeFilterCriteria filters = DisputeFilterCriteria.builder()
                .status(status)
                .severity(severity)
                .startDate(startDate)
                .endDate(endDate)
                .build();

        Page<AdminDisputeListDto> page = disputeService.listOpenDisputes(filters, pageable);
        return hateoasAssembler.toModel(page);
    }

    /**
     * GET /api/admin/disputes/{id}
     * Get full dispute detail with evidence and history.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AdminDisputeDetailDto> getDispute(@PathVariable Long id) {
        return ResponseEntity.ok(disputeService.getDisputeDetail(id));
    }

    /**
     * POST /api/admin/disputes/{id}/resolve
     * Resolve dispute with admin decision.
     */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolveDispute(
            @PathVariable Long id,
            @RequestBody @Valid DisputeResolutionRequest request) {

        User admin = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin user not found"));
        
        disputeService.resolveDispute(id, request, admin);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/admin/disputes/{id}/escalate
     * Escalate dispute to senior admin.
     */
    @PostMapping("/{id}/escalate")
    public ResponseEntity<Void> escalateDispute(
            @PathVariable Long id,
            @RequestBody @Valid EscalateDisputeRequest request) {

        User admin = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin user not found"));
        
        disputeService.escalateDispute(id, request.getReason(), admin);
        return ResponseEntity.ok().build();
    }
    
    // ==================== VAL-004: CHECK-IN DISPUTE ENDPOINTS ====================
    
    /**
     * GET /api/admin/disputes/check-in/pending
     * List all pending check-in disputes that need admin review.
     */
    @GetMapping("/check-in/pending")
    public PagedModel<EntityModel<AdminDisputeListDto>> listPendingCheckInDisputes(
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable) {
        
        Page<AdminDisputeListDto> page = disputeService.listPendingCheckInDisputes(pageable);
        return hateoasAssembler.toModel(page);
    }
    
    /**
     * POST /api/admin/disputes/check-in/{id}/resolve
     * Resolve a check-in dispute with one of three decisions:
     * - PROCEED: Document damage and continue booking
     * - CANCEL: Cancel booking with full refund
     * - DECLINE: Reject dispute (guest must accept or self-cancel)
     */
    @PostMapping("/check-in/{id}/resolve")
    public ResponseEntity<Void> resolveCheckInDispute(
            @PathVariable Long id,
            @RequestBody @Valid CheckInDisputeResolutionDTO request) {

        User admin = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin user not found"));
        
        log.info("[VAL-004] Admin {} resolving check-in dispute {}", admin.getId(), id);
        disputeService.resolveCheckInDispute(id, request, admin);
        return ResponseEntity.ok().build();
    }
    
    // ==================== VAL-010: CHECKOUT DAMAGE DISPUTE ENDPOINTS ====================
    
    /**
     * GET /api/admin/disputes/checkout/pending
     * List all pending checkout damage disputes that need admin review.
     * These are disputes where guest contested host's damage claim.
     */
    @GetMapping("/checkout/pending")
    public PagedModel<EntityModel<AdminDisputeListDto>> listPendingCheckoutDisputes(
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable) {
        
        Page<AdminDisputeListDto> page = disputeService.listPendingCheckoutDisputes(pageable);
        return hateoasAssembler.toModel(page);
    }
    
    /**
     * POST /api/admin/disputes/checkout/{id}/resolve
     * Resolve a checkout damage dispute.
     * 
     * <h2>VAL-010: Damage Claims Block Deposit Release</h2>
     * 
     * Decision options:
     * - APPROVE: Host's damage claim approved, deposit captured for damage payment
     * - REJECT: Host's claim rejected, deposit released back to guest
     * - PARTIAL: Partial approval - reduced damage amount
     * 
     * After resolution, the checkout saga will resume.
     */
    @PostMapping("/checkout/{id}/resolve")
    public ResponseEntity<CheckoutDisputeResolutionResponseDTO> resolveCheckoutDispute(
            @PathVariable Long id,
            @RequestBody @Valid CheckoutDisputeResolutionDTO request) {

        User admin = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin user not found"));
        
        log.info("[VAL-010] Admin {} resolving checkout damage dispute {} with decision {}",
                admin.getId(), id, request.getDecision());
        
        CheckoutDisputeResolutionResponseDTO response = disputeService.resolveCheckoutDispute(id, request, admin);
        return ResponseEntity.ok(response);
    }
    
    /**
     * GET /api/admin/disputes/checkout/{bookingId}/timeline
     * Get the complete dispute timeline for a checkout damage claim.
     * Includes: damage report, guest response, evidence, escalation, resolution.
     */
    @GetMapping("/checkout/{bookingId}/timeline")
    public ResponseEntity<CheckoutDisputeTimelineDTO> getCheckoutDisputeTimeline(
            @PathVariable Long bookingId) {
        
        return ResponseEntity.ok(disputeService.getCheckoutDisputeTimeline(bookingId));
    }
}
