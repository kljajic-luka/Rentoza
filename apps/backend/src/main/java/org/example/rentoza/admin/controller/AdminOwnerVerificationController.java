package org.example.rentoza.admin.controller;

import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.example.rentoza.admin.dto.AdminUserDetailDto;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.OwnerVerificationService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.OwnerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin endpoints for owner identity verification (Serbian compliance).
 */
@RestController
@RequestMapping("/api/admin/owners")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminOwnerVerificationController {
    
    private static final Logger log = LoggerFactory.getLogger(AdminOwnerVerificationController.class);
    
    private final OwnerVerificationService verificationService;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final AdminAuditService auditService;
    
    /**
     * Get all pending owner verifications.
     */
    @GetMapping("/pending")
    public ResponseEntity<List<PendingOwnerDto>> getPendingVerifications() {
        List<User> pendingOwners = userRepository.findPendingVerificationOwners();
        
        List<PendingOwnerDto> dtos = pendingOwners.stream()
            .map(u -> new PendingOwnerDto(
                u.getId(),
                u.getEmail(),
                u.getFirstName() + " " + u.getLastName(),
                u.getOwnerType(),
                u.getOwnerType() == OwnerType.INDIVIDUAL ? u.getMaskedJmbg() : u.getMaskedPib(),
                u.getBankAccountNumber() != null
            ))
            .toList();
        
        log.info("Fetched {} pending owner verifications", dtos.size());
        return ResponseEntity.ok(dtos);
    }
    
    /**
     * Approve owner identity verification.
     */
    @PostMapping("/{userId}/approve")
    public ResponseEntity<Void> approveVerification(@PathVariable Long userId) {
        
        Long adminId = currentUser.id();
        User admin = userRepository.findById(adminId)
            .orElseThrow(() -> new IllegalStateException("Admin not found"));

        User targetBefore = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalStateException("User not found"));
        String beforeState = auditService.toJson(AdminUserDetailDto.fromEntity(targetBefore));
        
        verificationService.approveIdentityVerification(userId, admin);

        User targetAfter = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalStateException("User not found"));
        String afterState = auditService.toJson(AdminUserDetailDto.fromEntity(targetAfter));

        auditService.logAction(
            admin,
            AdminAction.USER_VERIFIED_ID,
            ResourceType.USER,
            userId,
            beforeState,
            afterState,
            "Owner identity verified"
        );

        log.info("Owner {} approved by admin {}", userId, adminId);
        return ResponseEntity.ok().build();
    }
    
    /**
     * Reject owner identity verification.
     */
    @PostMapping("/{userId}/reject")
    public ResponseEntity<Void> rejectVerification(
            @PathVariable Long userId,
            @Valid @RequestBody RejectRequest request) {

        Long adminId = currentUser.id();
        User admin = userRepository.findById(adminId)
            .orElseThrow(() -> new IllegalStateException("Admin not found"));

        User targetBefore = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalStateException("User not found"));
        String beforeState = auditService.toJson(AdminUserDetailDto.fromEntity(targetBefore));

        verificationService.rejectIdentityVerification(userId, request.reason());

        User targetAfter = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalStateException("User not found"));
        String afterState = auditService.toJson(AdminUserDetailDto.fromEntity(targetAfter));

        auditService.logAction(
            admin,
            AdminAction.USER_VERIFICATION_REJECTED,
            ResourceType.USER,
            userId,
            beforeState,
            afterState,
            request.reason()
        );

        log.warn("Owner {} rejected by adminId={}", userId, adminId);
        return ResponseEntity.ok().build();
    }
    
    // ==================== DTOs ====================
    
    public record PendingOwnerDto(
        Long userId,
        String email,
        String fullName,
        OwnerType ownerType,
        String maskedId,
        boolean hasBankAccount
    ) {}
    
    public record RejectRequest(
        @NotBlank(message = "Reason is required")
        @Size(min = 5, max = 500, message = "Reason must be between 5 and 500 characters")
        String reason
    ) {}
}
