package org.example.rentoza.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminUserDetailDto;
import org.example.rentoza.admin.dto.AdminUserDto;
import org.example.rentoza.admin.dto.BanUserRequest;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.repository.AdminUserRepository;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.admin.service.AdminUserService;
import org.example.rentoza.config.HateoasAssembler;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final AdminAuditService auditService;
    private final CurrentUser currentUser;
    private final AdminUserRepository adminUserRepository;
    private final HateoasAssembler hateoasAssembler;

    @GetMapping
    public PagedModel<EntityModel<AdminUserDto>> listUsers(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        
        Page<AdminUserDto> users;
        if (search != null && !search.trim().isEmpty()) {
            users = adminUserService.searchUsers(search.trim(), pageable);
        } else {
            users = adminUserService.listUsers(pageable);
        }
        
        return hateoasAssembler.toModel(users);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<AdminUserDetailDto> getUserDetail(@PathVariable Long id) {
        return ResponseEntity.ok(adminUserService.getUserDetail(id));
    }

    @PutMapping("/{id}/ban")
    public ResponseEntity<Void> banUser(
            @PathVariable Long id,
            @Valid @RequestBody BanUserRequest request) {
        
        User admin = adminUserRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
                
        adminUserService.banUser(id, request.getReason(), admin);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/unban")
    public ResponseEntity<Void> unbanUser(@PathVariable Long id) {
        User admin = adminUserRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
                
        adminUserService.unbanUser(id, admin);
        return ResponseEntity.ok().build();
    }
    
    // AUDIT-C1-FIX: Hard-delete is disabled. Permanently deleted accounts create
    // evidence gaps for disputes, payment chargebacks, and regulatory inquiries.
    // Replace with a soft-deactivate/legal-hold workflow when needed.
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteUser(@PathVariable Long id) {
        log.warn("[Admin][AUDIT-C1] Hard-delete user endpoint called for userId={} - endpoint is permanently disabled", id);
        return ResponseEntity.status(org.springframework.http.HttpStatus.GONE)
                .body(Map.of(
                        "error", "HARD_DELETE_DISABLED",
                        "message", "User deletion is not supported. Use account suspension or deactivation workflows.",
                        "code", "HARD_DELETE_DISABLED"
                ));
    }
    
    @GetMapping("/banned")
    public PagedModel<EntityModel<AdminUserDto>> getBannedUsers(Pageable pageable) {
        Page<AdminUserDto> bannedUsers = adminUserService.listBannedUsers(pageable);
        return hateoasAssembler.toModel(bannedUsers);
    }

    /**
     * SECURITY (M-9): List users with pending DOB correction requests.
     * Provides an operations queue for admin review at scale.
     */
    @GetMapping("/dob-corrections/pending")
    public PagedModel<EntityModel<AdminUserDto>> getPendingDobCorrections(
            @PageableDefault(size = 20, sort = "dobCorrectionRequestedAt", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<AdminUserDto> pending = adminUserService.listPendingDobCorrections(pageable);
        return hateoasAssembler.toModel(pending);
    }

    // ========== DOB CORRECTION REVIEW (M-9) ==========

    /**
     * SECURITY (M-9): Approve a pending DOB correction request.
     * Sets the user's dateOfBirth to the requested value and marks as APPROVED.
     */
    @PutMapping("/{id}/dob-correction/approve")
    public ResponseEntity<Map<String, String>> approveDobCorrection(@PathVariable Long id) {
        User admin = adminUserRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
        User targetUser = adminUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));

        if (!"PENDING".equals(targetUser.getDobCorrectionStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "NO_PENDING_REQUEST",
                    "message", "Nema aktivnog zahteva za korekciju datuma rođenja"
            ));
        }

        String beforeState = auditService.toJson(targetUser.getDateOfBirth());
        String afterState = auditService.toJson(targetUser.getDobCorrectionRequestedValue());

        targetUser.setDateOfBirth(targetUser.getDobCorrectionRequestedValue());
        targetUser.setDobCorrectionStatus("APPROVED");
        targetUser.setDobCorrectionRequestedValue(null);
        targetUser.setDobCorrectionRequestedAt(null);
        targetUser.setDobCorrectionReason(null);
        adminUserRepository.save(targetUser);

        auditService.logAction(admin, AdminAction.DOB_CORRECTION_APPROVED,
                ResourceType.USER, id, beforeState, afterState,
                "DOB correction approved by admin");

        log.info("AUDIT: DOB correction approved for userId={} by adminId={}, oldDob={}, newDob={}",
                id, admin.getId(), beforeState, afterState);

        return ResponseEntity.ok(Map.of(
                "status", "APPROVED",
                "message", "Zahtev za korekciju datuma rođenja je odobren"
        ));
    }

    /**
     * SECURITY (M-9): Reject a pending DOB correction request.
     * Marks the correction as REJECTED with an optional admin reason.
     */
    @PutMapping("/{id}/dob-correction/reject")
    public ResponseEntity<Map<String, String>> rejectDobCorrection(
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        User admin = adminUserRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
        User targetUser = adminUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));

        if (!"PENDING".equals(targetUser.getDobCorrectionStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "NO_PENDING_REQUEST",
                    "message", "Nema aktivnog zahteva za korekciju datuma rođenja"
            ));
        }

        String adminReason = reason != null ? reason : "Odbijeno od strane administratora";

        targetUser.setDobCorrectionStatus("REJECTED");
        targetUser.setDobCorrectionReason(adminReason);
        adminUserRepository.save(targetUser);

        auditService.logAction(admin, AdminAction.DOB_CORRECTION_REJECTED,
                ResourceType.USER, id,
                "PENDING", "REJECTED",
                adminReason);

        log.info("AUDIT: DOB correction rejected for userId={} by adminId={}, reason={}",
                id, admin.getId(), adminReason);

        return ResponseEntity.ok(Map.of(
                "status", "REJECTED",
                "message", "Zahtev za korekciju datuma rođenja je odbijen"
        ));
    }
}
