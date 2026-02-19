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
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaOrchestrator;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.dispute.DamageClaimStatus;
import org.example.rentoza.booking.dispute.DisputeStage;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
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
    private final BookingRepository bookingRepository;
    private final CheckoutSagaOrchestrator checkoutSagaOrchestrator;

    // ==================== DISPUTE LISTING ====================

    @Transactional(readOnly = true)
    public Page<AdminDisputeListDto> listOpenDisputes(DisputeFilterCriteria filters, Pageable pageable) {
        Specification<DamageClaim> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Status filter - cast column to String type AND compare with enum name
             if (filters.getStatus() != null) {
                 predicates.add(cb.equal(root.get("status").as(String.class), filters.getStatus().name()));
             } else {
                 // Default: show ALL active disputes (legacy + checkout) that need attention
                 predicates.add(cb.or(
                     cb.equal(root.get("status").as(String.class), DamageClaimStatus.PENDING.name()),
                     cb.equal(root.get("status").as(String.class), DamageClaimStatus.DISPUTED.name()),
                     cb.equal(root.get("status").as(String.class), DamageClaimStatus.ESCALATED.name()),
                     cb.equal(root.get("status").as(String.class), DamageClaimStatus.CHECKOUT_PENDING.name()),
                     cb.equal(root.get("status").as(String.class), DamageClaimStatus.CHECKOUT_GUEST_ACCEPTED.name()),
                     cb.equal(root.get("status").as(String.class), DamageClaimStatus.CHECKOUT_GUEST_DISPUTED.name()),
                     cb.equal(root.get("status").as(String.class), DamageClaimStatus.CHECKOUT_TIMEOUT_ESCALATED.name()),
                     cb.equal(root.get("status").as(String.class), DamageClaimStatus.CHECK_IN_DISPUTE_PENDING.name())
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

        String guestName = claim.getGuest().getFirstName() + " " + claim.getGuest().getLastName();
        String hostName = claim.getHost().getFirstName() + " " + claim.getHost().getLastName();
        String reviewedByName = claim.getReviewedBy() != null 
                ? claim.getReviewedBy().getFirstName() + " " + claim.getReviewedBy().getLastName()
                : null;

        AdminDisputeDetailDto.AdminDisputeDetailDtoBuilder builder = AdminDisputeDetailDto.builder()
                .id(claim.getId())
                .status(claim.getStatus())
                .description(claim.getDescription())
                .estimatedCostCents(claim.getClaimedAmount().multiply(java.math.BigDecimal.valueOf(100)).longValue())
                // Party info with names (frontend expects guestName/hostName)
                .guestId(claim.getGuest().getId())
                .guestName(guestName)
                .guestEmail(claim.getGuest().getEmail())
                .guestPhone(claim.getGuest().getPhone())
                .hostId(claim.getHost().getId())
                .hostName(hostName)
                .hostEmail(claim.getHost().getEmail())
                .hostPhone(claim.getHost().getPhone())
                // Booking & vehicle
                .bookingId(claim.getBooking().getId())
                .carId(claim.getBooking().getCar().getId())
                // Amounts in RSD (frontend uses these directly)
                .claimedAmount(claim.getClaimedAmount())
                .approvedAmount(claim.getApprovedAmount())
                // Evidence photos (all three sets)
                .photoUrls(claim.getEvidencePhotoIds())
                .checkinPhotoIds(claim.getCheckinPhotoIds())
                .checkoutPhotoIds(claim.getCheckoutPhotoIds())
                .evidencePhotoIds(claim.getEvidencePhotoIds())
                // Guest response
                .guestResponse(claim.getGuestResponse())
                .guestRespondedAt(claim.getGuestRespondedAt())
                // Admin review
                .reviewedBy(reviewedByName)
                .reviewedAt(claim.getReviewedAt())
                .adminNotes(claim.getAdminNotes())
                // Dispute metadata
                .disputeStage(claim.getDisputeStage() != null ? claim.getDisputeStage().name() : null)
                .disputeType(claim.getDisputeType() != null ? claim.getDisputeType().name() : null)
                .initiator(claim.getInitiator() != null ? claim.getInitiator().name() : null)
                .adminReviewRequired(claim.getAdminReviewRequired())
                .repairQuoteDocumentUrl(claim.getRepairQuoteDocumentUrl())
                .createdAt(claim.getCreatedAt());

        Optional<DisputeResolution> resolution = resolutionRepo.findByDamageClaimId(disputeId);
        resolution.ifPresent(res -> builder.resolution(DisputeResolutionDto.fromEntity(res)));

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
    
    // ==================== VAL-004: CHECK-IN DISPUTE RESOLUTION ====================
    
    /**
     * Resolve a check-in dispute raised by a guest.
     * 
     * <p>Admin can choose from three resolution options:
     * <ul>
     *   <li>PROCEED - Document damage, waive guest liability, continue trip</li>
     *   <li>CANCEL - Cancel booking with full refund to guest</li>
     *   <li>DECLINE - Reject dispute (guest must accept or self-cancel)</li>
     * </ul>
     *
     * @param disputeId The damage claim ID
     * @param resolution The resolution decision and notes
     * @param admin The admin making the decision
     */
    public void resolveCheckInDispute(Long disputeId, CheckInDisputeResolutionDTO resolution, User admin) {
        DamageClaim dispute = damageClaimRepo.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Prijava nije pronađena"));
        
        // Validate this is a check-in dispute
        if (dispute.getDisputeStage() != DisputeStage.CHECK_IN) {
            throw new IllegalArgumentException("Ova prijava nije vezana za check-in fazu");
        }
        
        if (dispute.getStatus() != DamageClaimStatus.CHECK_IN_DISPUTE_PENDING) {
            throw new IllegalArgumentException("Prijava je već rešena. Trenutni status: " + dispute.getStatus());
        }
        
        Booking booking = dispute.getBooking();
        String beforeState = auditService.toJson(dispute);
        
        switch (resolution.getDecision()) {
            case PROCEED -> resolveCheckInProceed(dispute, booking, resolution, admin);
            case CANCEL -> resolveCheckInCancel(dispute, booking, resolution, admin);
            case DECLINE -> resolveCheckInDecline(dispute, booking, resolution, admin);
        }
        
        // Save dispute
        damageClaimRepo.save(dispute);
        bookingRepository.save(booking);
        
        // Audit log
        auditService.logAction(
            admin,
            AdminAction.DISPUTE_RESOLVED,
            ResourceType.DISPUTE,
            disputeId,
            beforeState,
            auditService.toJson(dispute),
            "Check-in dispute: " + resolution.getDecision().name() + " - " + resolution.getNotes()
        );
        
        log.info("[VAL-004] Check-in dispute {} resolved by admin {} with decision: {}", 
                disputeId, admin.getId(), resolution.getDecision());
    }
    
    /**
     * PROCEED: Document damage and continue with booking.
     * Guest is waived from liability for this pre-existing damage.
     */
    private void resolveCheckInProceed(DamageClaim dispute, Booking booking, 
                                       CheckInDisputeResolutionDTO resolution, User admin) {
        dispute.resolveCheckInProceed(admin, resolution.getNotes(), resolution.getDocumentedDamage());
        
        // Return booking to CHECK_IN_COMPLETE status
        booking.setStatus(BookingStatus.CHECK_IN_COMPLETE);
        booking.setGuestCheckInCompletedAt(Instant.now());
        
        // Notify both parties
        notifyCheckInDisputeResolved(booking, dispute, "PROCEED", 
            "Prijava je prihvaćena. Šteta je zabeležena i gost nije odgovoran za nju. Možete nastaviti sa preuzimanjem.");
    }
    
    /**
     * CANCEL: Cancel booking with full refund to guest.
     */
    private void resolveCheckInCancel(DamageClaim dispute, Booking booking, 
                                      CheckInDisputeResolutionDTO resolution, User admin) {
        dispute.resolveCheckInCancel(admin, resolution.getNotes(), resolution.getCancellationReason());
        
        // Cancel booking
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(java.time.LocalDateTime.now());
        
        // Process refund
        try {
            paymentService.processFullRefund(booking.getId(), "Check-in dispute: " + resolution.getCancellationReason());
        } catch (Exception e) {
            log.error("Failed to process refund for booking {} after check-in dispute cancellation", 
                    booking.getId(), e);
            // Continue - manual refund will be needed
        }
        
        // Notify both parties
        notifyCheckInDisputeResolved(booking, dispute, "CANCEL", 
            "Rezervacija je otkazana zbog prijavljene štete. Gost dobija potpuni povraćaj sredstava.");
    }
    
    /**
     * DECLINE: Reject the dispute (no undisclosed damage found).
     * Guest must accept condition or self-cancel.
     */
    private void resolveCheckInDecline(DamageClaim dispute, Booking booking, 
                                       CheckInDisputeResolutionDTO resolution, User admin) {
        dispute.declineCheckInDispute(admin, resolution.getNotes());
        
        // Return booking to HOST_COMPLETE - guest must re-submit acknowledgment
        booking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
        
        // Notify guest they need to re-acknowledge or cancel
        notifyCheckInDisputeDeclined(booking, dispute, resolution.getNotes());
    }
    
    /**
     * Notify both parties about check-in dispute resolution.
     */
    private void notifyCheckInDisputeResolved(Booking booking, DamageClaim dispute, 
                                               String decision, String message) {
        try {
            // Notify guest (renter)
            CreateNotificationRequestDTO guestNotification = CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getRenter().getId())
                    .type(NotificationType.DISPUTE_RESOLVED)
                    .message("Prijava stanja vozila je rešena: " + message)
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build();
            notificationService.createNotification(guestNotification);
            
            // Notify host (car owner)
            CreateNotificationRequestDTO hostNotification = CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getCar().getOwner().getId())
                    .type(NotificationType.DISPUTE_RESOLVED)
                    .message("Prijava gosta za rezervaciju #" + booking.getId() + " je rešena: " + decision)
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build();
            notificationService.createNotification(hostNotification);
            
        } catch (Exception e) {
            log.error("Failed to notify users about check-in dispute resolution", e);
        }
    }
    
    /**
     * Notify guest that their dispute was declined.
     */
    private void notifyCheckInDisputeDeclined(Booking booking, DamageClaim dispute, String adminNotes) {
        try {
            CreateNotificationRequestDTO notification = CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getRenter().getId())
                    .type(NotificationType.DISPUTE_RESOLVED)
                    .message("⚠️ Vaša prijava stanja vozila je odbijena. " +
                            "Admin je pregledao prijavu i nije pronašao neprijavljenu štetu. " +
                            "Molimo vas da prihvatite stanje vozila ili otkažete rezervaciju. " +
                            (adminNotes != null ? "Napomena: " + adminNotes : ""))
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build();
            notificationService.createNotification(notification);
            
        } catch (Exception e) {
            log.error("Failed to notify guest about declined check-in dispute", e);
        }
    }
    
    /**
     * Get all pending check-in disputes for admin review.
     */
    @Transactional(readOnly = true)
    public Page<AdminDisputeListDto> listPendingCheckInDisputes(Pageable pageable) {
        Specification<DamageClaim> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("disputeStage").as(String.class), DisputeStage.CHECK_IN.name()));
            predicates.add(cb.equal(root.get("status").as(String.class), DamageClaimStatus.CHECK_IN_DISPUTE_PENDING.name()));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        
        Page<DamageClaim> disputes = damageClaimRepo.findAll(spec, pageable);
        return disputes.map(this::toAdminDisputeListDto);
    }
    
    // ==================== VAL-010: CHECKOUT DAMAGE DISPUTE RESOLUTION ====================
    
    /**
     * List all pending checkout damage disputes for admin review.
     * These are disputes where guest contested host's damage claim at checkout.
     */
    @Transactional(readOnly = true)
    public Page<AdminDisputeListDto> listPendingCheckoutDisputes(Pageable pageable) {
        Specification<DamageClaim> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("disputeStage").as(String.class), DisputeStage.CHECKOUT.name()));
            predicates.add(cb.or(
                cb.equal(root.get("status").as(String.class), DamageClaimStatus.CHECKOUT_GUEST_DISPUTED.name()),
                cb.equal(root.get("status").as(String.class), DamageClaimStatus.CHECKOUT_TIMEOUT_ESCALATED.name())
            ));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        
        Page<DamageClaim> disputes = damageClaimRepo.findAll(spec, pageable);
        return disputes.map(this::toAdminDisputeListDto);
    }
    
    /**
     * Resolve a checkout damage dispute (VAL-010).
     * 
     * <p>After resolution:
     * <ul>
     *   <li>APPROVE: Capture deposit for damage payment</li>
     *   <li>REJECT: Release deposit back to guest</li>
     *   <li>PARTIAL: Capture partial amount, release remainder</li>
     * </ul>
     * 
     * <p>The booking is transitioned back to CHECKOUT_HOST_COMPLETE 
     * and the checkout saga is resumed.
     */
    public CheckoutDisputeResolutionResponseDTO resolveCheckoutDispute(
            Long damageClaimId, 
            CheckoutDisputeResolutionDTO request, 
            User admin) {
        
        DamageClaim claim = damageClaimRepo.findById(damageClaimId)
                .orElseThrow(() -> new ResourceNotFoundException("Damage claim not found: " + damageClaimId));
        
        // Validate this is a checkout dispute
        if (claim.getDisputeStage() != DisputeStage.CHECKOUT) {
            throw new IllegalStateException("This is not a checkout damage dispute");
        }
        
        // Validate dispute is in resolvable state
        if (!claim.getStatus().isCheckoutDispute() || claim.getStatus().isResolved()) {
            throw new IllegalStateException("Dispute cannot be resolved in current state: " + claim.getStatus());
        }
        
        Booking booking = claim.getBooking();
        java.math.BigDecimal originalClaimAmount = claim.getClaimedAmount();
        java.math.BigDecimal approvedAmount = java.math.BigDecimal.ZERO;
        java.math.BigDecimal depositCaptured = java.math.BigDecimal.ZERO;
        java.math.BigDecimal depositReleased = java.math.BigDecimal.ZERO;
        
        log.info("[VAL-010] Resolving checkout dispute {} for booking {} with decision {}",
                damageClaimId, booking.getId(), request.getDecision());
        
        switch (request.getDecision()) {
            case APPROVE -> {
                approvedAmount = request.getApprovedAmountRsd() != null 
                    ? request.getApprovedAmountRsd() 
                    : originalClaimAmount;
                claim.setApprovedAmount(approvedAmount);
                claim.setStatus(DamageClaimStatus.CHECKOUT_ADMIN_APPROVED);
                
                // Capture deposit for damage payment
                depositCaptured = processDepositCapture(booking, approvedAmount);
                java.math.BigDecimal depositAmount = booking.getSecurityDeposit() != null 
                    ? booking.getSecurityDeposit() : java.math.BigDecimal.ZERO;
                depositReleased = depositAmount.subtract(depositCaptured);
            }
            
            case REJECT -> {
                claim.setApprovedAmount(java.math.BigDecimal.ZERO);
                claim.setStatus(DamageClaimStatus.CHECKOUT_ADMIN_REJECTED);
                
                // Release full deposit
                depositReleased = processDepositRelease(booking);
            }
            
            case PARTIAL -> {
                if (request.getApprovedAmountRsd() == null || 
                        request.getApprovedAmountRsd().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Approved amount is required for PARTIAL decision");
                }
                approvedAmount = request.getApprovedAmountRsd();
                claim.setApprovedAmount(approvedAmount);
                claim.setStatus(DamageClaimStatus.CHECKOUT_ADMIN_APPROVED);
                
                // Capture partial deposit
                depositCaptured = processDepositCapture(booking, approvedAmount);
                java.math.BigDecimal depositAmt = booking.getSecurityDeposit() != null 
                    ? booking.getSecurityDeposit() : java.math.BigDecimal.ZERO;
                depositReleased = depositAmt.subtract(depositCaptured);
            }
        }
        
        // Update claim resolution details
        claim.setResolvedBy(admin);
        claim.setResolvedAt(Instant.now());
        claim.setResolutionNotes(request.getResolutionNotes());
        damageClaimRepo.save(claim);
        
        // Clear deposit hold and transition booking
        // CRITICAL: Only mark deposit resolved if payment gateway operations succeeded.
        // If processDepositCapture/Release threw an exception above, we never reach here.
        booking.setSecurityDepositReleased(true);
        booking.setSecurityDepositResolvedAt(Instant.now());
        booking.setSecurityDepositHoldReason(null);
        booking.setSecurityDepositHoldUntil(null);
        booking.setDamageClaimStatus(claim.getStatus().name());
        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);
        bookingRepository.save(booking);
        
        // Audit log
        auditService.logAction(admin, AdminAction.DISPUTE_RESOLVED, 
                ResourceType.BOOKING, booking.getId(), null,
                request.getDecision().name(),
                String.format("Checkout dispute %d resolved: %s (approved: %s RSD)",
                        damageClaimId, request.getDecision(), approvedAmount));
        
        // Notify parties if requested
        boolean notificationsSent = false;
        if (request.isNotifyParties()) {
            notificationsSent = notifyCheckoutDisputeResolved(booking, claim, request.getDecision().name(), approvedAmount);
        }
        
        // Resume checkout saga
        boolean sagaResumed = resumeCheckoutSaga(booking.getId());
        
        return CheckoutDisputeResolutionResponseDTO.builder()
                .bookingId(booking.getId())
                .damageClaimId(claim.getId())
                .decision(request.getDecision().name())
                .originalClaimAmountRsd(originalClaimAmount)
                .approvedAmountRsd(approvedAmount)
                .depositCapturedRsd(depositCaptured)
                .depositReleasedRsd(depositReleased)
                .resolutionNotes(request.getResolutionNotes())
                .resolvedByAdminId(admin.getId())
                .resolvedByAdminName(admin.getFirstName() + " " + admin.getLastName())
                .resolvedAt(claim.getResolvedAt())
                .sagaResumed(sagaResumed)
                .newBookingStatus(booking.getStatus().name())
                .notificationsSent(notificationsSent)
                .build();
    }
    
    /**
     * Process deposit capture for damage payment.
     */
    private java.math.BigDecimal processDepositCapture(Booking booking, java.math.BigDecimal amount) {
        // Cap at deposit amount
        java.math.BigDecimal depositAmount = booking.getSecurityDeposit() != null 
            ? booking.getSecurityDeposit() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal captureAmount = amount.min(depositAmount);
        // Use damage charge API with the claim and deposit authorization
        DamageClaim claim = booking.getCheckoutDamageClaim();
        if (claim != null) {
            String depositAuthId = booking.getDepositAuthorizationId();
            if (depositAuthId == null || depositAuthId.isBlank()) {
                log.error("[VAL-010] CRITICAL: No deposit authorization ID for booking {} - cannot capture. " +
                        "Manual intervention required.", booking.getId());
                throw new IllegalStateException(
                        "Deposit capture failed: No authorization ID for booking " + booking.getId() +
                        ". Manual deposit capture required.");
            }
            paymentService.chargeDamage(claim.getId(), depositAuthId);
        }
        log.info("[VAL-010] Captured {} RSD from deposit for booking {}", captureAmount, booking.getId());
        return captureAmount;
    }
    
    /**
     * Process deposit release back to guest.
     * Uses releaseDeposit (release authorization hold) NOT processFullRefund (which refunds the booking payment).
     */
    private java.math.BigDecimal processDepositRelease(Booking booking) {
        java.math.BigDecimal releaseAmount = booking.getSecurityDeposit() != null 
            ? booking.getSecurityDeposit() : java.math.BigDecimal.ZERO;
        String depositAuthId = booking.getDepositAuthorizationId();
        if (depositAuthId == null || depositAuthId.isBlank()) {
            log.error("[VAL-010] CRITICAL: No deposit authorization ID for booking {} - cannot release. " +
                    "Manual intervention required.", booking.getId());
            throw new IllegalStateException(
                    "Deposit release failed: No authorization ID for booking " + booking.getId() +
                    ". Manual deposit release required.");
        }
        paymentService.releaseDeposit(booking.getId(), depositAuthId);
        log.info("[VAL-010] Released {} RSD deposit for booking {}", releaseAmount, booking.getId());
        return releaseAmount;
    }
    
    /**
     * Resume checkout saga after dispute resolution.
     * P0 FIX: Actually invoke the saga orchestrator instead of just logging.
     * 
     * <p><b>Gap fix:</b> When the damage dispute path is taken
     * (CHECKOUT_DAMAGE_DISPUTE set before completeCheckout()), no saga exists.
     * In that case, prepare the booking and start a fresh saga instead of resuming.</p>
     */
    private boolean resumeCheckoutSaga(Long bookingId) {
        try {
            checkoutSagaOrchestrator.resumeSuspendedSaga(bookingId);
            log.info("[VAL-010] Checkout saga resumed for booking {}", bookingId);
            return true;
        } catch (ResourceNotFoundException e) {
            // No saga exists — dispute branched before completeCheckout() was called.
            // Prepare the booking and start a fresh saga.
            log.info("[VAL-010] No existing saga for booking {} — starting fresh saga after admin dispute resolution", bookingId);
            try {
                Booking booking = bookingRepository.findById(bookingId)
                        .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
                // Set checkout timestamps if completeCheckout() was never called
                if (booking.getCheckoutCompletedAt() == null) {
                    booking.setCheckoutCompletedAt(Instant.now());
                }
                if (booking.getTripEndedAt() == null) {
                    booking.setTripEndedAt(Instant.now());
                }
                bookingRepository.save(booking);
                
                checkoutSagaOrchestrator.startSaga(bookingId);
                log.info("[VAL-010] Fresh saga started for booking {} after admin dispute resolution", bookingId);
                return true;
            } catch (Exception startEx) {
                log.error("[VAL-010] Failed to start fresh saga for booking {}: {}", bookingId, startEx.getMessage(), startEx);
                return false;
            }
        } catch (Exception e) {
            log.error("[VAL-010] Failed to resume saga for booking {}: {}", bookingId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Notify both parties about checkout dispute resolution.
     */
    private boolean notifyCheckoutDisputeResolved(Booking booking, DamageClaim claim, 
                                                   String decision, java.math.BigDecimal approvedAmount) {
        try {
            // Notify guest
            String guestMessage;
            switch (decision) {
                case "APPROVE" -> guestMessage = String.format(
                    "Oštećenje vozila je potvrđeno. Iznos od %s RSD će biti oduzet od vašeg depozita.",
                    approvedAmount);
                case "REJECT" -> guestMessage = 
                    "Vaša žalba na prijavu oštećenja je prihvaćena. Depozit će vam biti vraćen u celosti.";
                case "PARTIAL" -> guestMessage = String.format(
                    "Oštećenje vozila je delimično potvrđeno. Iznos od %s RSD će biti oduzet od depozita, ostatak vam se vraća.",
                    approvedAmount);
                default -> guestMessage = "Spor oko oštećenja vozila je rešen.";
            }
            
            CreateNotificationRequestDTO guestNotification = CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getRenter().getId())
                    .type(NotificationType.DISPUTE_RESOLVED)
                    .message(guestMessage)
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build();
            notificationService.createNotification(guestNotification);
            
            // Notify host
            String hostMessage = switch (decision) {
                case "APPROVE" -> String.format(
                    "Vaša prijava oštećenja je odobrena. Iznos od %s RSD je odobren za nadoknadu.",
                    approvedAmount);
                case "REJECT" -> 
                    "Vaša prijava oštećenja je odbijena nakon admin pregleda.";
                case "PARTIAL" -> String.format(
                    "Vaša prijava oštećenja je delimično odobrena. Odobren iznos: %s RSD.",
                    approvedAmount);
                default -> "Spor oko oštećenja vozila je rešen.";
            };
            
            CreateNotificationRequestDTO hostNotification = CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getCar().getOwner().getId())
                    .type(NotificationType.DISPUTE_RESOLVED)
                    .message(hostMessage)
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build();
            notificationService.createNotification(hostNotification);
            
            return true;
        } catch (Exception e) {
            log.error("[VAL-010] Failed to notify users about checkout dispute resolution", e);
            return false;
        }
    }
    
    /**
     * Get the complete timeline for a checkout damage dispute.
     */
    @Transactional(readOnly = true)
    public CheckoutDisputeTimelineDTO getCheckoutDisputeTimeline(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
        
        DamageClaim claim = booking.getCheckoutDamageClaim();
        if (claim == null) {
            throw new ResourceNotFoundException("No checkout damage claim found for booking: " + bookingId);
        }
        
        List<CheckoutDisputeTimelineDTO.TimelineEvent> events = new ArrayList<>();
        
        // Add damage reported event
        events.add(CheckoutDisputeTimelineDTO.TimelineEvent.builder()
                .eventType("DAMAGE_REPORTED")
                .description("Domaćin je prijavio oštećenje vozila")
                .actor("HOST")
                .actorName(claim.getHost().getFirstName() + " " + claim.getHost().getLastName())
                .occurredAt(claim.getCreatedAt())
                .build());
        
        // Add guest response if any
        if (claim.getGuestRespondedAt() != null) {
            String responseType = claim.getStatus() == DamageClaimStatus.CHECKOUT_GUEST_ACCEPTED 
                ? "GUEST_ACCEPTED" : "GUEST_DISPUTED";
            String description = claim.getStatus() == DamageClaimStatus.CHECKOUT_GUEST_ACCEPTED
                ? "Gost je prihvatio prijavu oštećenja"
                : "Gost je osporio prijavu oštećenja";
            
            events.add(CheckoutDisputeTimelineDTO.TimelineEvent.builder()
                    .eventType(responseType)
                    .description(description)
                    .actor("GUEST")
                    .actorName(claim.getGuest().getFirstName() + " " + claim.getGuest().getLastName())
                    .occurredAt(claim.getGuestRespondedAt())
                    .metadata(claim.getDisputeReason())
                    .build());
        }
        
        // Add resolution if resolved
        if (claim.getResolvedAt() != null) {
            String resolverName = claim.getResolvedBy() != null 
                ? claim.getResolvedBy().getFirstName() + " " + claim.getResolvedBy().getLastName() 
                : "System";
            events.add(CheckoutDisputeTimelineDTO.TimelineEvent.builder()
                    .eventType("ADMIN_RESOLVED")
                    .description("Admin je rešio spor: " + claim.getStatus().name())
                    .actor("ADMIN")
                    .actorName(resolverName)
                    .occurredAt(claim.getResolvedAt())
                    .metadata(claim.getResolutionNotes())
                    .build());
        }
        
        String renterName = booking.getRenter().getFirstName() + " " + booking.getRenter().getLastName();
        String ownerName = booking.getCar().getOwner().getFirstName() + " " + booking.getCar().getOwner().getLastName();
        String resolvedByName = claim.getResolvedBy() != null 
            ? claim.getResolvedBy().getFirstName() + " " + claim.getResolvedBy().getLastName() 
            : null;
        
        return CheckoutDisputeTimelineDTO.builder()
                .bookingId(booking.getId())
                .damageClaimId(claim.getId())
                .currentStatus(claim.getStatus().name())
                .guestId(booking.getRenter().getId())
                .guestName(renterName)
                .hostId(booking.getCar().getOwner().getId())
                .hostName(ownerName)
                .carId(booking.getCar().getId())
                .carDescription(booking.getCar().getBrand() + " " + booking.getCar().getModel())
                .tripStart(Instant.from(booking.getStartDate()))
                .tripEnd(Instant.from(booking.getEndDate()))
                .damageDescription(claim.getDescription())
                .claimedAmountRsd(claim.getClaimedAmount())
                .damageReportedAt(claim.getCreatedAt())
                .damagePhotoUrls(claim.getEvidencePhotoIdsList())
                .securityDepositRsd(booking.getSecurityDeposit())
                .depositHoldReason(booking.getSecurityDepositHoldReason())
                .depositHoldUntil(booking.getSecurityDepositHoldUntil())
                .guestResponseType(claim.getGuestRespondedAt() != null 
                    ? (claim.getStatus() == DamageClaimStatus.CHECKOUT_GUEST_ACCEPTED ? "ACCEPTED" : "DISPUTED")
                    : null)
                .guestDisputeReason(claim.getDisputeReason())
                .guestRespondedAt(claim.getGuestRespondedAt())
                .events(events)
                .resolved(claim.getStatus().isResolved())
                .resolutionDecision(claim.getResolvedAt() != null ? claim.getStatus().name() : null)
                .approvedAmountRsd(claim.getApprovedAmount())
                .resolutionNotes(claim.getResolutionNotes())
                .resolvedByAdminId(claim.getResolvedBy() != null ? claim.getResolvedBy().getId() : null)
                .resolvedByAdminName(resolvedByName)
                .resolvedAt(claim.getResolvedAt())
                .build();
    }
}
