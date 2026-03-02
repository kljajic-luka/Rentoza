package org.example.rentoza.booking.dispute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.dispute.dto.DamageClaimDTO;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

/**
 * Service for managing damage claims and dispute resolution.
 */
@Service
@Slf4j
public class DamageClaimService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    private final DamageClaimRepository claimRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    // Metrics
    private final Counter claimCreatedCounter;
    private final Counter claimDisputedCounter;
    private final Counter claimResolvedCounter;

    @Value("${app.damage-claim.response-hours:72}")
    private int responseHours;

    public DamageClaimService(
            DamageClaimRepository claimRepository,
            BookingRepository bookingRepository,
            UserRepository userRepository,
            NotificationService notificationService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.claimRepository = claimRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;

        this.claimCreatedCounter = Counter.builder("damage_claim.created")
                .description("Damage claims created")
                .register(meterRegistry);

        this.claimDisputedCounter = Counter.builder("damage_claim.disputed")
                .description("Damage claims disputed")
                .register(meterRegistry);

        this.claimResolvedCounter = Counter.builder("damage_claim.resolved")
                .description("Damage claims resolved")
                .register(meterRegistry);
    }

    // ========== CREATE CLAIM ==========

    /**
     * Maximum time window (in hours) after checkout within which damage claims can be filed.
     * H4 FIX: Platform spec requires claims within 48 hours of checkout.
     */
    private static final int DAMAGE_CLAIM_WINDOW_HOURS = 48;

    /**
     * Create a new damage claim.
     * Called from checkout when host reports damage.
     * 
     * H4 FIX: Enforces 48-hour window after checkout for filing claims.
     */
    @Transactional
    public DamageClaimDTO createClaim(
            Long bookingId,
            String description,
            BigDecimal claimedAmount,
            List<Long> evidencePhotoIds,
            Long hostUserId) {
        
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate host
        if (!booking.getCar().getOwner().getId().equals(hostUserId)) {
            throw new AccessDeniedException("Samo vlasnik može prijaviti štetu");
        }
        
        // ========================================================================
        // H4 FIX: Enforce 48-hour damage claim window after checkout
        // ========================================================================
        // Platform spec: "Damage claims must be filed within 48 hours of checkout"
        // This prevents hosts from filing claims weeks after the fact (unfair to renters).
        Instant checkoutTime = booking.getCheckoutCompletedAt();
        if (checkoutTime != null) {
            Duration timeSinceCheckout = Duration.between(checkoutTime, Instant.now());
            if (timeSinceCheckout.toHours() > DAMAGE_CLAIM_WINDOW_HOURS) {
                log.warn("[DamageClaim] WINDOW EXPIRED: bookingId={}, checkoutAt={}, hoursSince={}", 
                        bookingId, checkoutTime, timeSinceCheckout.toHours());
                throw new IllegalStateException(String.format(
                        "Rok za prijavu štete je istekao. " +
                        "Prijava štete mora biti podneta u roku od %d sata od završetka najma. " +
                        "Završetak najma: %s (%d sata pre).",
                        DAMAGE_CLAIM_WINDOW_HOURS,
                        checkoutTime.atZone(SERBIA_ZONE).toLocalDateTime(),
                        timeSinceCheckout.toHours()
                ));
            }
        }
        // Note: If checkout is not completed yet, claim is filed during checkout flow (allowed)
        
        // Check if active claim already exists for this booking + stage + initiator
        if (claimRepository.hasActiveClaim(bookingId, DisputeStage.CHECKOUT, ClaimInitiator.OWNER)) {
            throw new IllegalStateException("Za ovu rezervaciju već postoji aktivna prijava štete od strane vlasnika");
        }

        // H-4 FIX: Validate claimed amount does not exceed security deposit
        BigDecimal depositAmount = booking.getSecurityDeposit();
        if (depositAmount != null && claimedAmount.compareTo(depositAmount) > 0) {
            throw new IllegalArgumentException(String.format(
                    "Iznos prijave (%.0f RSD) ne može biti veći od depozita (%.0f RSD)",
                    claimedAmount.doubleValue(), depositAmount.doubleValue()));
        }

        // C-6 FIX: Flag high-value claims for mandatory admin review
        // Matches threshold used in CheckOutService.createCheckoutDamageClaim()
        boolean adminReviewRequired = claimedAmount != null
                && claimedAmount.compareTo(new BigDecimal("50000")) > 0;

        DamageClaim claim = DamageClaim.builder()
                .booking(booking)
                .host(booking.getCar().getOwner())
                .guest(booking.getRenter())
                .description(description)
                .claimedAmount(claimedAmount)
                .evidencePhotoIds(serializePhotoIds(evidencePhotoIds))
                .status(DamageClaimStatus.PENDING)
                .responseDeadline(Instant.now().plus(Duration.ofHours(responseHours)))
                .initiator(ClaimInitiator.OWNER)
                .disputeStage(DisputeStage.CHECKOUT)
                .reportedBy(booking.getCar().getOwner())
                .adminReviewRequired(adminReviewRequired)
                .build();

        claim = claimRepository.save(claim);

        claimCreatedCounter.increment();
        log.info("[DamageClaim] Claim created for booking {}: {} RSD", bookingId, claimedAmount);

        // Notify guest
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.CHECKOUT_DAMAGE_REPORTED)
                .message(String.format("Domaćin je prijavio štetu od %.0f RSD. Imate %d sati da odgovorite.",
                        claimedAmount.doubleValue(), responseHours))
                .relatedEntityId(String.valueOf(bookingId))
                .build());

        return mapToDTO(claim);
    }
    
    // ========== GUEST CLAIM (Phase 4) ==========
    
    /**
     * Guest files a counter-claim or independent damage claim.
     * 
     * <p>Use cases:
     * <ul>
     *   <li>Counter-claim: Host filed damage claim, guest disagrees and provides counter-evidence</li>
     *   <li>Independent: Guest reports issue not raised by host (vehicle problems, service issues)</li>
     * </ul>
     * 
     * @param bookingId Booking associated with the claim
     * @param description Detailed description of the issue
     * @param claimedAmount Requested compensation amount
     * @param disputeType Category of the dispute
     * @param evidencePhotoIds Photo evidence IDs
     * @param guestUserId Current guest user ID
     * @return Created claim DTO
     * @throws AccessDeniedException if user is not the renter on this booking
     * @since Phase 4 - Guest Dispute Capability
     */
    @Transactional
    public DamageClaimDTO createGuestClaim(
            Long bookingId,
            String description,
            BigDecimal claimedAmount,
            DisputeType disputeType,
            List<Long> evidencePhotoIds,
            Long guestUserId) {
        
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate guest is the renter
        if (!booking.getRenter().getId().equals(guestUserId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Samo zakupac može podneti reklamaciju kao gost");
        }
        
        User guest = userRepository.findById(guestUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Korisnik nije pronađen"));
        
        // Note: Guests CAN file claims even if host already filed one (counter-claim scenario)
        // But prevent duplicate active guest claims for same booking + stage
        if (claimRepository.hasActiveClaim(bookingId, DisputeStage.CHECKOUT, ClaimInitiator.USER)) {
            throw new IllegalStateException("Već postoji aktivna reklamacija gosta za ovu rezervaciju");
        }
        
        DamageClaim claim = DamageClaim.builder()
                .booking(booking)
                .host(booking.getCar().getOwner())
                .guest(booking.getRenter())
                .description(description)
                .claimedAmount(claimedAmount)
                .disputeType(disputeType)
                .evidencePhotoIds(serializePhotoIds(evidencePhotoIds))
                .status(DamageClaimStatus.PENDING)
                .responseDeadline(Instant.now().plus(Duration.ofHours(responseHours)))
                .initiator(ClaimInitiator.USER)
                .reportedBy(guest)
                .disputeStage(DisputeStage.CHECKOUT) // Guest claims are post-checkout disputes
                .build();
        
        claim = claimRepository.save(claim);
        
        claimCreatedCounter.increment();
        log.info("[DamageClaim] Guest claim created for booking {}: {} RSD (type: {})", 
                bookingId, claimedAmount, disputeType);
        
        // Notify host about guest claim
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getCar().getOwner().getId())
                .type(NotificationType.CHECKOUT_DAMAGE_REPORTED) // Reuse existing type
                .message(String.format("Gost je podneo reklamaciju od %.0f RSD. Pregled je potreban.",
                        claimedAmount.doubleValue()))
                .relatedEntityId(String.valueOf(bookingId))
                .build());
        
        // Notify admin for immediate review
        // Note: In production, consider sending to admin notification channel
        log.info("[DamageClaim] Guest claim {} awaits admin review", claim.getId());
        
        return mapToDTO(claim);
    }

    // ========== GUEST RESPONSE ==========

    /**
     * Guest accepts the damage claim.
     * P1 FIX: Routes CHECKOUT_PENDING claims through checkout-specific transitions.
     */
    @Transactional
    public DamageClaimDTO acceptClaim(Long claimId, String response, Long guestUserId) {
        DamageClaim claim = getClaimForGuest(claimId, guestUserId);

        if (!claim.canGuestRespond()) {
            throw new IllegalStateException("Rok za odgovor je istekao ili prijava nije na čekanju");
        }

        // [P1] Block guest self-acceptance on high-value claims requiring admin review
        if (Boolean.TRUE.equals(claim.getAdminReviewRequired())) {
            throw new IllegalStateException(
                    "Prijava štete preko 50.000 RSD zahteva pregled administratora. " +
                    "Gost ne može direktno prihvatiti ovu prijavu.");
        }

        // Route through checkout-specific or legacy transition
        if (claim.getStatus() == DamageClaimStatus.CHECKOUT_PENDING) {
            claim.acceptByGuestCheckout(response);
        } else {
            claim.acceptByGuest(response);
        }
        claim = claimRepository.save(claim);

        claimResolvedCounter.increment();
        log.info("[DamageClaim] Claim {} accepted by guest", claimId);

        // Notify host
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(claim.getHost().getId())
                .type(NotificationType.CHECKOUT_COMPLETE)
                .message("Gost je prihvatio prijavu štete")
                .relatedEntityId(String.valueOf(claim.getBooking().getId()))
                .build());

        return mapToDTO(claim);
    }

    /**
     * Guest disputes the damage claim.
     * P1 FIX: Routes CHECKOUT_PENDING claims through checkout-specific transitions.
     */
    @Transactional
    public DamageClaimDTO disputeClaim(Long claimId, String response, Long guestUserId) {
        DamageClaim claim = getClaimForGuest(claimId, guestUserId);

        if (!claim.canGuestRespond()) {
            throw new IllegalStateException("Rok za odgovor je istekao ili prijava nije na čekanju");
        }

        // Route through checkout-specific or legacy transition
        if (claim.getStatus() == DamageClaimStatus.CHECKOUT_PENDING) {
            claim.disputeByGuestCheckout(response);
        } else {
            claim.disputeByGuest(response);
        }
        claim = claimRepository.save(claim);

        claimDisputedCounter.increment();
        log.info("[DamageClaim] Claim {} disputed by guest", claimId);

        // Notify host
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(claim.getHost().getId())
                .type(NotificationType.CHECKOUT_DAMAGE_REPORTED)
                .message("Gost osporava prijavu štete. Predmet je prosleđen na pregled.")
                .relatedEntityId(String.valueOf(claim.getBooking().getId()))
                .build());

        return mapToDTO(claim);
    }

    // ========== ADMIN REVIEW ==========

    /**
     * Admin approves the damage claim.
     */
    @Transactional
    public DamageClaimDTO adminApprove(Long claimId, BigDecimal approvedAmount, String notes, Long adminUserId) {
        DamageClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Prijava štete nije pronađena"));

        if (claim.getStatus() != DamageClaimStatus.DISPUTED) {
            throw new IllegalStateException("Samo osporene prijave mogu biti pregledane");
        }

        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin korisnik nije pronađen"));

        // H-7 FIX: Validate admin role (defense-in-depth — controller @PreAuthorize is first layer)
        if (admin.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Samo administratori mogu odobriti prijave štete");
        }

        claim.approveByAdmin(admin, approvedAmount, notes);
        claim = claimRepository.save(claim);

        claimResolvedCounter.increment();
        log.info("[DamageClaim] Claim {} approved by admin for {} RSD", claimId, approvedAmount);

        // Notify both parties
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(claim.getHost().getId())
                .type(NotificationType.CHECKOUT_COMPLETE)
                .message(String.format("Vaša prijava štete je odobrena: %.0f RSD", approvedAmount.doubleValue()))
                .relatedEntityId(String.valueOf(claim.getBooking().getId()))
                .build());

        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(claim.getGuest().getId())
                .type(NotificationType.CHECKOUT_DAMAGE_REPORTED)
                .message(String.format("Administrator je odobrio prijavu štete: %.0f RSD", approvedAmount.doubleValue()))
                .relatedEntityId(String.valueOf(claim.getBooking().getId()))
                .build());

        return mapToDTO(claim);
    }

    /**
     * Admin rejects the damage claim.
     */
    @Transactional
    public DamageClaimDTO adminReject(Long claimId, String notes, Long adminUserId) {
        DamageClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Prijava štete nije pronađena"));

        if (claim.getStatus() != DamageClaimStatus.DISPUTED) {
            throw new IllegalStateException("Samo osporene prijave mogu biti pregledane");
        }

        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin korisnik nije pronađen"));

        // H-7 FIX: Validate admin role (defense-in-depth)
        if (admin.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Samo administratori mogu odbiti prijave štete");
        }

        claim.rejectByAdmin(admin, notes);
        claim = claimRepository.save(claim);

        claimResolvedCounter.increment();
        log.info("[DamageClaim] Claim {} rejected by admin", claimId);

        // Notify both parties
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(claim.getHost().getId())
                .type(NotificationType.CHECKOUT_COMPLETE)
                .message("Vaša prijava štete je odbijena nakon pregleda")
                .relatedEntityId(String.valueOf(claim.getBooking().getId()))
                .build());

        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(claim.getGuest().getId())
                .type(NotificationType.CHECKOUT_COMPLETE)
                .message("Administrator je odbio prijavu štete")
                .relatedEntityId(String.valueOf(claim.getBooking().getId()))
                .build());

        return mapToDTO(claim);
    }

    // ========== AUTO-APPROVAL SCHEDULER ==========

    /**
     * Auto-approve expired pending claims.
     * Called by scheduler every hour.
     */
    @Transactional
    public int autoApproveExpiredClaims() {
        List<DamageClaim> expired = claimRepository.findExpiredPendingClaims(Instant.now());

        for (DamageClaim claim : expired) {
            claim.setStatus(DamageClaimStatus.AUTO_APPROVED);

            // C-5 FIX: Cap approved amount at security deposit (never approve more than guest paid)
            BigDecimal depositAmount = claim.getBooking().getSecurityDeposit();
            BigDecimal cappedAmount = claim.getClaimedAmount();
            if (depositAmount != null && cappedAmount.compareTo(depositAmount) > 0) {
                cappedAmount = depositAmount;
                log.warn("[DamageClaim] Auto-approved claim {} capped at deposit: claimed={} RSD, deposit={} RSD",
                        claim.getId(), claim.getClaimedAmount(), depositAmount);
            }
            claim.setApprovedAmount(cappedAmount);
            claimRepository.save(claim);

            log.info("[DamageClaim] Claim {} auto-approved due to guest non-response", claim.getId());

            // Notify guest
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(claim.getGuest().getId())
                    .type(NotificationType.CHECKOUT_DAMAGE_REPORTED)
                    .message("Prijava štete je automatski odobrena jer niste odgovorili na vreme")
                    .relatedEntityId(String.valueOf(claim.getBooking().getId()))
                    .build());
        }

        return expired.size();
    }

    // ========== RETRIEVAL ==========

    @Transactional(readOnly = true)
    public DamageClaimDTO getClaimByBooking(Long bookingId, Long userId) {
        // Use multi-claim-aware query, return most recent claim
        List<DamageClaim> claims = claimRepository.findAllByBookingId(bookingId);
        if (claims.isEmpty()) {
            throw new ResourceNotFoundException("Prijava štete nije pronađena");
        }
        DamageClaim claim = claims.get(0); // newest first

        // Validate access
        if (!claim.getHost().getId().equals(userId) && !claim.getGuest().getId().equals(userId)) {
            throw new AccessDeniedException("Nemate pristup ovoj prijavi");
        }

        return mapToDTO(claim);
    }

    /**
     * Get all claims for a booking (multi-claim support).
     * Returns all claims (host-initiated and guest-initiated) for the given booking.
     * 
     * @since V61 - Multi-claim support
     */
    @Transactional(readOnly = true)
    public List<DamageClaimDTO> getClaimsByBooking(Long bookingId, Long userId) {
        List<DamageClaim> claims = claimRepository.findAllByBookingId(bookingId);
        if (claims.isEmpty()) {
            throw new ResourceNotFoundException("Prijave štete nisu pronađene za ovu rezervaciju");
        }
        // Validate access (check against first claim's parties)
        DamageClaim first = claims.get(0);
        if (!first.getHost().getId().equals(userId) && !first.getGuest().getId().equals(userId)) {
            throw new AccessDeniedException("Nemate pristup ovim prijavama");
        }
        return claims.stream().map(this::mapToDTO).toList();
    }

    @Transactional(readOnly = true)
    public List<DamageClaimDTO> getClaimsForUser(Long userId) {
        List<DamageClaim> hostClaims = claimRepository.findByHostId(userId);
        List<DamageClaim> guestClaims = claimRepository.findByGuestId(userId);

        // Combine and sort by date
        hostClaims.addAll(guestClaims);
        hostClaims.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        return hostClaims.stream().map(this::mapToDTO).toList();
    }

    /**
     * Get claims awaiting admin review (admin-only endpoint).
     * Includes admin notes and review details.
     */
    @Transactional(readOnly = true)
    public List<DamageClaimDTO> getClaimsAwaitingReview() {
        return claimRepository.findAwaitingAdminReview().stream()
                .map(claim -> mapToDTO(claim, true)) // Include admin fields
                .toList();
    }

    // ========== HELPERS ==========

    private DamageClaim getClaimForGuest(Long claimId, Long guestUserId) {
        DamageClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Prijava štete nije pronađena"));

        if (!claim.getGuest().getId().equals(guestUserId)) {
            throw new AccessDeniedException("Nemate pristup ovoj prijavi");
        }

        return claim;
    }

    private String serializePhotoIds(List<Long> photoIds) {
        if (photoIds == null || photoIds.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(photoIds);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<Long> deserializePhotoIds(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    private DamageClaimDTO mapToDTO(DamageClaim claim) {
        return mapToDTO(claim, false);
    }
    
    /**
     * Map DamageClaim entity to DTO.
     * 
     * @param claim Entity to map
     * @param includeAdminFields If true, includes admin-only fields (adminNotes, reviewedBy).
     *                          Should only be true for admin endpoints.
     * @return DTO with appropriate field visibility
     * @since Phase 4 - Privacy Leak Fix
     */
    private DamageClaimDTO mapToDTO(DamageClaim claim, boolean includeAdminFields) {
        String statusDisplay = switch (claim.getStatus()) {
            case PENDING -> "Na čekanju";
            case ACCEPTED_BY_GUEST -> "Prihvaćeno";
            case DISPUTED -> "Osporeno - čeka pregled";
            case ESCALATED -> "Eskaliran - čeka pregled viših nadležnih";
            case AUTO_APPROVED -> "Automatski odobreno";
            case ADMIN_APPROVED -> "Odobreno";
            case ADMIN_REJECTED -> "Odbijeno";
            case PAID -> "Plaćeno";
            case CANCELLED -> "Otkazano";
            case REQUIRES_MANUAL_REVIEW -> "Zahteva ručni pregled";
            case ARCHIVED -> "Arhivirano";
            // VAL-004: Check-in dispute statuses
            case CHECK_IN_DISPUTE_PENDING -> "Prijava prilikom preuzimanja - na čekanju";
            case CHECK_IN_RESOLVED_PROCEED -> "Preuzimanje nastavljeno sa zabeleškom";
            case CHECK_IN_RESOLVED_CANCEL -> "Rezervacija otkazana zbog prijave";
            case CHECK_IN_GUEST_WITHDREW -> "Gost povukao prijavu";
            // VAL-010: Checkout damage dispute statuses
            case CHECKOUT_PENDING -> "Šteta na povratku - čeka odgovor gosta";
            case CHECKOUT_GUEST_ACCEPTED -> "Gost prihvatio štetu na povratku";
            case CHECKOUT_GUEST_DISPUTED -> "Gost osporio štetu - čeka pregled admina";
            case CHECKOUT_ADMIN_APPROVED -> "Admin odobrio štetu na povratku";
            case CHECKOUT_ADMIN_REJECTED -> "Admin odbio štetu na povratku";
            case CHECKOUT_TIMEOUT_ESCALATED -> "Istek vremena - eskaliran adminu";
        };

        return DamageClaimDTO.builder()
                .id(claim.getId())
                .bookingId(claim.getBooking().getId())
                .hostId(claim.getHost().getId())
                .hostName(claim.getHost().getFirstName() + " " + claim.getHost().getLastName())
                .guestId(claim.getGuest().getId())
                .guestName(claim.getGuest().getFirstName() + " " + claim.getGuest().getLastName())
                .description(claim.getDescription())
                .claimedAmount(claim.getClaimedAmount())
                .approvedAmount(claim.getApprovedAmount())
                .checkinPhotoIds(deserializePhotoIds(claim.getCheckinPhotoIds()))
                .checkoutPhotoIds(deserializePhotoIds(claim.getCheckoutPhotoIds()))
                .evidencePhotoIds(deserializePhotoIds(claim.getEvidencePhotoIds()))
                .status(claim.getStatus())
                .statusDisplay(statusDisplay)
                .canGuestRespond(claim.canGuestRespond())
                .needsAdminReview(claim.needsAdminReview())
                .responseDeadline(toLocalDateTime(claim.getResponseDeadline()))
                .guestResponse(claim.getGuestResponse())
                .guestRespondedAt(toLocalDateTime(claim.getGuestRespondedAt()))
                // Phase 4 Privacy Fix: Only expose admin notes to admin endpoints
                .adminNotes(includeAdminFields ? claim.getAdminNotes() : null)
                .reviewedAt(includeAdminFields ? toLocalDateTime(claim.getReviewedAt()) : null)
                .paymentReference(claim.getPaymentReference())
                .paidAt(toLocalDateTime(claim.getPaidAt()))
                .createdAt(toLocalDateTime(claim.getCreatedAt()))
                .updatedAt(toLocalDateTime(claim.getUpdatedAt()))
                .vehicleName(claim.getBooking().getCar().getBrand() + " " + claim.getBooking().getCar().getModel())
                .vehicleImageUrl(claim.getBooking().getCar().getImageUrl())
                .build();
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, SERBIA_ZONE);
    }
}

