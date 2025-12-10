package org.example.rentoza.admin.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.*;
import org.example.rentoza.admin.dto.enums.DisputeSeverity;
import org.example.rentoza.admin.service.AdminDisputeService;
import org.example.rentoza.booking.dispute.DamageClaimStatus;
import org.example.rentoza.config.HateoasAssembler;
import org.example.rentoza.security.CurrentUser; // Assuming this exists or using Authentication
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserService; // To fetch full user object if needed
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final UserService userService; // Or helper to resolve User from Principal
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
            @RequestBody @Valid DisputeResolutionRequest request,
            @AuthenticationPrincipal User principal) { // Spring Security injection

        // If principal is not full entity, fetch it. 
        // Assuming custom UserDetails or simple User principal. 
        // Safer to fetch fresh user from DB to ensure Admin status/existence.
        // User admin = userService.getCurrentUser(); // Implementation dependent
        // For now, using principal cast if it matches User entity
        
        disputeService.resolveDispute(id, request, principal);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/admin/disputes/{id}/escalate
     * Escalate dispute to senior admin.
     */
    @PostMapping("/{id}/escalate")
    public ResponseEntity<Void> escalateDispute(
            @PathVariable Long id,
            @RequestBody @Valid EscalateDisputeRequest request,
            @AuthenticationPrincipal User principal) {
        
        disputeService.escalateDispute(id, request.getReason(), principal);
        return ResponseEntity.ok().build();
    }
}
