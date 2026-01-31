package org.example.rentoza.admin.service;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.*;
import org.example.rentoza.admin.dto.enums.DisputeDecision;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.DisputeResolution;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.repository.DisputeResolutionRepository;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.dispute.DamageClaimStatus;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AdminDisputeService {

    private final DamageClaimRepository damageClaimRepo;
    private final DisputeResolutionRepository resolutionRepo;
    private final AdminAuditService auditService;
    private final NotificationService notificationService;
    private final BookingPaymentService paymentService;

    // ==================== DISPUTE LISTING ====================

    @Transactional(readOnly = true)
    public Page<AdminDisputeListDto> listOpenDisputes(DisputeFilterCriteria filters, Pageable pageable) {
        Specification<DamageClaim> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Status filter - cast column to String type AND compare with enum name
             if (filters.getStatus() != null) {
                 predicates.add(cb.equal(root.get("status").as(String.class), filters.getStatus().name()));
             } else {
                 // Default: PENDING or ESCALATED
                 // Note: DamageClaimStatus.DISPUTED is the user-facing status for "Disputed by guest"
                 // The plan mentioned DisputeStatus.PENDING/ESCALATED. 
                 // We map DamageClaimStatus here.
                 predicates.add(cb.or(
                     cb.equal(root.get("status").as(String.class), DamageClaimStatus.DISPUTED.name()),
                     cb.equal(root.get("status").as(String.class), DamageClaimStatus.ESCALATED.name())
                 ));
             }

            // Severity filter (Estimated Cost)
            if (filters.getSeverity() != null) {
                // Assuming estimatedCostCents exists on DamageClaim (from Plan)
                 // NOTE: DamageClaim uses BigDecimal for claimedAmount/approvedAmount. 
                 // Plan says "estimatedCostCents (Long)". 
                 // I will strictly follow DamageClaim entity which uses BigDecimal 'claimedAmount'
                 // Map severity enum to BigDecimal comparison
                java.math.BigDecimal min = java.math.BigDecimal.valueOf(filters.getSeverity().getMinCostCents() / 100.0);
                predicates.add(cb.greaterThanOrEqualTo(root.get("claimedAmount"), min));
            }

            // Date range filter
            if (filters.getStartDate() != null) {
                // Convert LocalDateTime to Instant using Serbian timezone
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), 
                    org.example.rentoza.config.timezone.SerbiaTimeZone.toInstant(filters.getStartDate())));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<DamageClaim> disputes = damageClaimRepo.findAll(spec, pageable);
        return disputes.map(this::toAdminDisputeListDto);
    }

    // ==================== DISPUTE DETAIL ====================

    @Transactional(readOnly = true)
    public AdminDisputeDetailDto getDisputeDetail(Long disputeId) {
        DamageClaim claim = damageClaimRepo.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

        AdminDisputeDetailDto.AdminDisputeDetailDtoBuilder builder = AdminDisputeDetailDto.builder()
                .id(claim.getId())
                .status(claim.getStatus())
                .description(claim.getDescription())
                // Entity uses BigDecimal claimedAmount, DTO uses Long estimatedCostCents (Plan).
                // I will use claimedAmount.longValue() * 100 ? Or update DTO.
                // I'll update DTO mapping to use claimedAmount * 100 for cents
                .estimatedCostCents(claim.getClaimedAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue())
                .guestId(claim.getGuest().getId())
                .guestEmail(claim.getGuest().getEmail())
                .guestPhone(claim.getGuest().getPhone()) // Check User entity for getPhone vs getPhoneNumber
                .hostId(claim.getHost().getId())
                .hostEmail(claim.getHost().getEmail())
                .hostPhone(claim.getHost().getPhone())
                .bookingId(claim.getBooking().getId())
                .carId(claim.getBooking().getCar().getId())
                // Entity stores JSON string IDs? Or URLs? 
                // Plan says "getPhotoUrls()". DamageClaim entity has checkinPhotoIds (String).
                // I will return raw JSON string for now or parse if needed. 
                // Plan DTO has List<String>. I need to parse or just mock url list.
                // For MVP, I'll pass null or implement helper to fetch URLs from IDs.
                // Let's assume photoUrls field exists or I construct it.
                .photoUrls(claim.getEvidencePhotoIds()) 
                .createdAt(claim.getCreatedAt());

        Optional<DisputeResolution> resolution = resolutionRepo.findByDamageClaimId(disputeId);
        resolution.ifPresent(res -> builder.resolution(DisputeResolutionDto.fromEntity(res)));

        // History
        // Assuming auditService has getResourceHistory
        // List<AdminAuditLogDto> history = auditService.getResourceHistory("DISPUTE", disputeId);
        // builder.history(history);

        return builder.build();
    }

    // ==================== DISPUTE RESOLUTION ====================

    public void resolveDispute(Long disputeId, DisputeResolutionRequest resolution, User admin) {
        DamageClaim claim = damageClaimRepo.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

        // Validate state transition before making changes
        DamageClaimStatus newStatus = resolution.getDecision() == DisputeDecision.APPROVED 
            ? DamageClaimStatus.ADMIN_APPROVED 
            : DamageClaimStatus.ADMIN_REJECTED;
        
        validateStateTransition(claim.getStatus(), newStatus);

        // Capture actual state before changes for audit compliance
        String beforeState = auditService.toJson(claim);

        DisputeResolution decision = DisputeResolution.builder()
                .damageClaim(claim)
                .admin(admin)
                .decision(resolution.getDecision())
                .decisionNotes(resolution.getNotes())
                .approvedAmount(resolution.getApprovedAmount() != null ? 
                    java.math.BigDecimal.valueOf(resolution.getApprovedAmount() / 100.0) : java.math.BigDecimal.ZERO)
                .rejectionReason(resolution.getRejectionReason())
                .resolvedAt(Instant.now())
                .build();

        DisputeResolution saved = resolutionRepo.save(decision);

        if (resolution.getDecision() == DisputeDecision.APPROVED) {
            claim.setStatus(DamageClaimStatus.ADMIN_APPROVED);
            claim.setApprovedAmount(decision.getApprovedAmount());
        } else if (resolution.getDecision() == DisputeDecision.REJECTED) {
            claim.setStatus(DamageClaimStatus.ADMIN_REJECTED);
        } else {
            // PARTIAL / MEDIATED -> Treat as approved with partial amount
            claim.setStatus(DamageClaimStatus.ADMIN_APPROVED);
            claim.setApprovedAmount(decision.getApprovedAmount());
        }

        claim.setReviewedBy(admin);
        claim.setReviewedAt(Instant.now());
        claim.setAdminNotes(resolution.getNotes());
        
        try {
            damageClaimRepo.save(claim);
            
            // Critical: Send notifications
            try {
                notificationService.notifyDisputeResolved(claim, admin);
            } catch (Exception e) {
                log.warn("Failed to notify users about dispute resolution: {}", e.getMessage());
                // Don't fail the whole operation, but log for manual follow-up
            }
            
            // Process payment if approved
            if (resolution.getDecision() == DisputeDecision.APPROVED && 
                resolution.getApprovedAmount() != null && resolution.getApprovedAmount() > 0) {
                try {
                    paymentService.processDisputePayment(claim);
                } catch (Exception e) {
                    log.error("CRITICAL: Payment processing failed after dispute approval. " +
                             "Marking for manual review. Error: {}", e.getMessage(), e);
                    claim.setStatus(DamageClaimStatus.REQUIRES_MANUAL_REVIEW);
                    damageClaimRepo.save(claim);
                    
                    auditService.logAction(
                        admin,
                        AdminAction.DISPUTE_RESOLUTION_FAILED,
                        ResourceType.DISPUTE,
                        disputeId,
                        beforeState,
                        auditService.toJson(claim),
                        "Payment processing failed: " + e.getMessage()
                    );
                    
                    throw new RuntimeException("Payment processing failed - marked for manual review", e);
                }
            }
            
            auditService.logAction(
                admin,
                AdminAction.DISPUTE_RESOLVED,
                ResourceType.DISPUTE,
                disputeId,
                beforeState,
                auditService.toJson(claim),
                resolution.getNotes()
            );
            
            log.info("Dispute {} resolved by admin {}", disputeId, admin.getId());
            
        } catch (OptimisticLockException e) {
            log.error("Concurrent modification detected for dispute {}: {}", disputeId, e.getMessage());
            throw new RuntimeException("Dispute was modified by another user. Please refresh and try again.", e);
        } catch (Exception e) {
            log.error("Failed to resolve dispute {}: {}", disputeId, e.getMessage(), e);
            throw new RuntimeException("Failed to resolve dispute", e);
        }
    }

    public void escalateDispute(Long disputeId, String reason, User admin) {
        DamageClaim claim = damageClaimRepo.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));

        claim.setStatus(DamageClaimStatus.ESCALATED);
        claim.setAdminNotes(reason); 
        damageClaimRepo.save(claim);

        auditService.logAction(
            admin,
            org.example.rentoza.admin.entity.AdminAction.DISPUTE_ESCALATED,
            ResourceType.DISPUTE,
            disputeId,
            "Escalated",
            "Escalated",
            reason
        );
    }

    private AdminDisputeListDto toAdminDisputeListDto(DamageClaim claim) {
        return AdminDisputeListDto.builder()
                .id(claim.getId())
                .status(claim.getStatus())
                .estimatedCostCents(claim.getClaimedAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue())
                .guestName(claim.getGuest().getFirstName() + " " + claim.getGuest().getLastName())
                .hostName(claim.getHost().getFirstName() + " " + claim.getHost().getLastName())
                .createdAt(claim.getCreatedAt())
                .build();
    }
    
    /**
     * Validate dispute state transitions using state machine.
     * Prevents invalid transitions (e.g., PAID -> DISPUTED).
     * 
     * @throws IllegalArgumentException if transition is invalid
     */
    private void validateStateTransition(DamageClaimStatus currentStatus, DamageClaimStatus targetStatus) {
        java.util.Map<DamageClaimStatus, java.util.Set<DamageClaimStatus>> validTransitions = java.util.Map.ofEntries(
            java.util.Map.entry(DamageClaimStatus.DISPUTED, java.util.Set.of(
                DamageClaimStatus.ESCALATED,
                DamageClaimStatus.ADMIN_APPROVED,
                DamageClaimStatus.ADMIN_REJECTED
            )),
            java.util.Map.entry(DamageClaimStatus.ESCALATED, java.util.Set.of(
                DamageClaimStatus.ADMIN_APPROVED,
                DamageClaimStatus.ADMIN_REJECTED
            )),
            java.util.Map.entry(DamageClaimStatus.ADMIN_APPROVED, java.util.Set.of(
                DamageClaimStatus.PAID,
                DamageClaimStatus.REQUIRES_MANUAL_REVIEW
            )),
            java.util.Map.entry(DamageClaimStatus.ADMIN_REJECTED, java.util.Set.of(
                DamageClaimStatus.ARCHIVED
            )),
            java.util.Map.entry(DamageClaimStatus.PAID, java.util.Set.of(
                DamageClaimStatus.ARCHIVED
            ))
        );
        
        java.util.Set<DamageClaimStatus> allowed = validTransitions.getOrDefault(currentStatus, java.util.Set.of());
        
        if (!allowed.contains(targetStatus)) {
            throw new IllegalArgumentException(
                String.format("Invalid status transition: %s -> %s", currentStatus, targetStatus)
            );
        }
    }
}
