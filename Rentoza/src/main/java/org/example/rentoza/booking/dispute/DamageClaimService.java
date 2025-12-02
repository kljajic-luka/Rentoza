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
     * Create a new damage claim.
     * Called from checkout when host reports damage.
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
        
        // Check if claim already exists
        if (claimRepository.existsByBookingId(bookingId)) {
            throw new IllegalStateException("Za ovu rezervaciju već postoji prijava štete");
        }

        DamageClaim claim = DamageClaim.builder()
                .booking(booking)
                .host(booking.getCar().getOwner())
                .guest(booking.getRenter())
                .description(description)
                .claimedAmount(claimedAmount)
                .evidencePhotoIds(serializePhotoIds(evidencePhotoIds))
                .status(DamageClaimStatus.PENDING)
                .responseDeadline(Instant.now().plus(Duration.ofHours(responseHours)))
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

    // ========== GUEST RESPONSE ==========

    /**
     * Guest accepts the damage claim.
     */
    @Transactional
    public DamageClaimDTO acceptClaim(Long claimId, String response, Long guestUserId) {
        DamageClaim claim = getClaimForGuest(claimId, guestUserId);

        if (!claim.canGuestRespond()) {
            throw new IllegalStateException("Rok za odgovor je istekao ili prijava nije na čekanju");
        }

        claim.acceptByGuest(response);
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
     */
    @Transactional
    public DamageClaimDTO disputeClaim(Long claimId, String response, Long guestUserId) {
        DamageClaim claim = getClaimForGuest(claimId, guestUserId);

        if (!claim.canGuestRespond()) {
            throw new IllegalStateException("Rok za odgovor je istekao ili prijava nije na čekanju");
        }

        claim.disputeByGuest(response);
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
            claim.setApprovedAmount(claim.getClaimedAmount());
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
        DamageClaim claim = claimRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Prijava štete nije pronađena"));

        // Validate access
        if (!claim.getHost().getId().equals(userId) && !claim.getGuest().getId().equals(userId)) {
            throw new AccessDeniedException("Nemate pristup ovoj prijavi");
        }

        return mapToDTO(claim);
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

    @Transactional(readOnly = true)
    public List<DamageClaimDTO> getClaimsAwaitingReview() {
        return claimRepository.findAwaitingAdminReview().stream()
                .map(this::mapToDTO)
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
        String statusDisplay = switch (claim.getStatus()) {
            case PENDING -> "Na čekanju";
            case ACCEPTED_BY_GUEST -> "Prihvaćeno";
            case DISPUTED -> "Osporeno - čeka pregled";
            case AUTO_APPROVED -> "Automatski odobreno";
            case ADMIN_APPROVED -> "Odobreno";
            case ADMIN_REJECTED -> "Odbijeno";
            case PAID -> "Plaćeno";
            case CANCELLED -> "Otkazano";
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
                .adminNotes(claim.getAdminNotes())
                .reviewedAt(toLocalDateTime(claim.getReviewedAt()))
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

