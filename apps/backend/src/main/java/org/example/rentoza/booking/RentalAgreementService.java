package org.example.rentoza.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.car.Car;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing rental agreements.
 *
 * <p>Agreements are generated once per booking and must be accepted by both
 * parties (owner and renter) before a trip can start. This provides enforceable
 * evidence for the marketplace-intermediary model under Serbian law.
 */
@Service
@Slf4j
public class RentalAgreementService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    private static final String CURRENT_AGREEMENT_VERSION = "1.0.0";
    private static final String CURRENT_TERMS_TEMPLATE_ID = "sr-intermediary-v1";

    private final RentalAgreementRepository agreementRepository;
    private final BookingRepository bookingRepository;
    private final NotificationService notificationService;
    private final ObjectMapper stableMapper;

    @Value("${app.checkin.no-show-minutes-after-trip-start:120}")
    private int acceptanceDeadlineMinutesAfterTripStart;

    public RentalAgreementService(RentalAgreementRepository agreementRepository,
                                  BookingRepository bookingRepository,
                                  NotificationService notificationService) {
        this.agreementRepository = agreementRepository;
        this.bookingRepository = bookingRepository;
        this.notificationService = notificationService;

        // Stable ObjectMapper for deterministic JSON serialization (sorted keys)
        this.stableMapper = new ObjectMapper();
        this.stableMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * Generate a rental agreement for a booking. Idempotent — returns existing
     * agreement if one already exists for this booking.
     */
    @Transactional
    public RentalAgreement generateAgreement(Booking booking) {
        return generateAgreement(booking, true);
    }

    private RentalAgreement generateAgreement(Booking booking, boolean notifyParties) {
        // Idempotency: return existing agreement if present
        Optional<RentalAgreement> existing = agreementRepository.findByBookingId(booking.getId());
        if (existing.isPresent()) {
            log.debug("Agreement already exists for booking {}, returning existing", booking.getId());
            return existing.get();
        }

        Car car = booking.getCar();

        Map<String, Object> vehicleSnapshot = buildVehicleSnapshot(car);
        Map<String, Object> termsSnapshot = buildTermsSnapshot(booking);

        String contentHash = computeContentHash(vehicleSnapshot, termsSnapshot, booking);

        RentalAgreement agreement = RentalAgreement.builder()
                .bookingId(booking.getId())
                .agreementVersion(CURRENT_AGREEMENT_VERSION)
                .agreementType("STANDARD_RENTAL")
                .contentHash(contentHash)
                .generatedAt(Instant.now())
                .ownerUserId(car.getOwner().getId())
                .renterUserId(booking.getRenter().getId())
                .vehicleSnapshotJson(vehicleSnapshot)
                .termsSnapshotJson(termsSnapshot)
                .status(RentalAgreementStatus.PENDING)
                .acceptanceDeadlineAt(resolveAcceptanceDeadline(booking))
                .requiredNextActor(RentalAgreementActor.BOTH)
                .termsTemplateId(CURRENT_TERMS_TEMPLATE_ID)
                .termsTemplateHash("PENDING_COUNSEL_REVIEW")
                .build();

        RentalAgreement saved = saveHandlingConcurrentInsert(agreement, booking.getId());
        log.info("Rental agreement generated: agreementId={}, bookingId={}, hash={}",
                saved.getId(), booking.getId(), contentHash);

        if (notifyParties) {
            sendPendingNotifications(saved, booking.getId());
        } else {
            log.info("Rental agreement generated without notifications: bookingId={}", booking.getId());
        }

        return saved;
    }

    private RentalAgreement saveHandlingConcurrentInsert(RentalAgreement agreement, Long bookingId) {
        try {
            return agreementRepository.save(agreement);
        } catch (DataIntegrityViolationException e) {
            return agreementRepository.findByBookingId(bookingId)
                    .map(existing -> {
                        log.info("Agreement insert raced for booking {}, returning existing row", bookingId);
                        return existing;
                    })
                    .orElseThrow(() -> e);
        }
    }

    private void sendPendingNotifications(RentalAgreement agreement, Long bookingId) {
        try {
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(agreement.getOwnerUserId())
                    .type(NotificationType.RENTAL_AGREEMENT_PENDING)
                    .message("Ugovor o iznajmljivanju za vašu rezervaciju je spreman. " +
                            "Molimo pregledajte i prihvatite ugovor pre početka vožnje.")
                    .relatedEntityId("booking-" + bookingId + "-agreement")
                    .build());

            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(agreement.getRenterUserId())
                    .type(NotificationType.RENTAL_AGREEMENT_PENDING)
                    .message("Ugovor o iznajmljivanju za vašu rezervaciju je spreman. " +
                            "Molimo pregledajte i prihvatite ugovor pre početka vožnje.")
                    .relatedEntityId("booking-" + bookingId + "-agreement")
                    .build());
        } catch (Exception e) {
            log.error("Failed to send agreement notification for booking {}: {}",
                    bookingId, e.getMessage());
        }
    }

    /**
     * Accept agreement as the car owner. Idempotent and retry-safe.
     */
    @Transactional
    public RentalAgreement acceptAsOwner(Long bookingId, Long userId, String ip, String userAgent) {
        RentalAgreement agreement = loadAgreementForMutation(bookingId);
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Booking not found for agreement: " + bookingId));

        ensureAcceptanceDeadlinePersisted(agreement, booking);

        if (!agreement.getOwnerUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "User " + userId + " is not the owner for this agreement");
        }

        // Idempotency: if already accepted by owner, return as-is
        if (agreement.getOwnerAcceptedAt() != null) {
            log.debug("Owner already accepted agreement for booking {}", bookingId);
            return agreement;
        }

        ensureAcceptanceStillOpen(agreement);

        agreement.setOwnerAcceptedAt(Instant.now());
        agreement.setOwnerIp(ip);
        agreement.setOwnerUserAgent(truncate(userAgent, 500));

        // Transition status
        if (agreement.getRenterAcceptedAt() != null) {
            agreement.setStatus(RentalAgreementStatus.FULLY_ACCEPTED);
            agreement.setRequiredNextActor(RentalAgreementActor.NONE);
            log.info("Agreement fully accepted: bookingId={}", bookingId);
        } else {
            agreement.setStatus(RentalAgreementStatus.OWNER_ACCEPTED);
            agreement.setRequiredNextActor(RentalAgreementActor.RENTER);
            log.info("Agreement accepted by owner: bookingId={}", bookingId);
        }

        return agreementRepository.saveAndFlush(agreement);
    }

    /**
     * Accept agreement as the renter. Idempotent and retry-safe.
     */
    @Transactional
    public RentalAgreement acceptAsRenter(Long bookingId, Long userId, String ip, String userAgent) {
        RentalAgreement agreement = loadAgreementForMutation(bookingId);
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Booking not found for agreement: " + bookingId));

        ensureAcceptanceDeadlinePersisted(agreement, booking);

        if (!agreement.getRenterUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "User " + userId + " is not the renter for this agreement");
        }

        // Idempotency: if already accepted by renter, return as-is
        if (agreement.getRenterAcceptedAt() != null) {
            log.debug("Renter already accepted agreement for booking {}", bookingId);
            return agreement;
        }

        ensureAcceptanceStillOpen(agreement);

        agreement.setRenterAcceptedAt(Instant.now());
        agreement.setRenterIp(ip);
        agreement.setRenterUserAgent(truncate(userAgent, 500));

        // Transition status
        if (agreement.getOwnerAcceptedAt() != null) {
            agreement.setStatus(RentalAgreementStatus.FULLY_ACCEPTED);
            agreement.setRequiredNextActor(RentalAgreementActor.NONE);
            log.info("Agreement fully accepted: bookingId={}", bookingId);
        } else {
            agreement.setStatus(RentalAgreementStatus.RENTER_ACCEPTED);
            agreement.setRequiredNextActor(RentalAgreementActor.OWNER);
            log.info("Agreement accepted by renter (awaiting owner): bookingId={}", bookingId);
        }

        return agreementRepository.saveAndFlush(agreement);
    }

    /**
     * Get the agreement for a booking.
     */
    @Transactional(readOnly = true)
    public Optional<RentalAgreement> getAgreementForBooking(Long bookingId) {
        return agreementRepository.findByBookingId(bookingId);
    }

    /**
     * Get or lazily generate the agreement for a booking.
     *
     * <p>Covers the gap where agreement generation failed silently during
     * booking approval (try-catch in BookingApprovalService) or the backfill
     * hasn't run yet. Only generates for non-terminal bookings that should
     * have an agreement.
     */
    @Transactional
    public Optional<RentalAgreement> getOrGenerateAgreement(Long bookingId) {
        Optional<RentalAgreement> existing = agreementRepository.findByBookingId(bookingId);
        if (existing.isPresent()) {
            return existing;
        }

        // Lazy generation: load booking with relations and generate if eligible
        Optional<Booking> bookingOpt = bookingRepository.findByIdWithRelations(bookingId);
        if (bookingOpt.isEmpty()) {
            return Optional.empty();
        }

        Booking booking = bookingOpt.get();
        if (!shouldHaveAgreement(booking)) {
            return Optional.empty();
        }

        log.info("Lazy-generating agreement for booking {} (status={})", bookingId, booking.getStatus());
        RentalAgreement agreement = generateAgreement(booking, false);
        return Optional.of(agreement);
    }

    /**
     * Check if the agreement for a booking is fully accepted by both parties.
     */
    @Transactional(readOnly = true)
    public boolean isFullyAccepted(Long bookingId) {
        return agreementRepository.findByBookingId(bookingId)
                .map(RentalAgreement::isFullyAccepted)
                .orElse(false);
    }

    private RentalAgreement loadAgreementForMutation(Long bookingId) {
        try {
            return agreementRepository.findByBookingIdForUpdate(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No rental agreement found for booking: " + bookingId));
        } catch (PessimisticLockingFailureException e) {
            throw new RentalAgreementConflictException(
                    "RENTAL_AGREEMENT_STATE_CHANGED",
                    "Status ugovora o iznajmljivanju je upravo promenjen. Osvežite stranicu i pokušajte ponovo."
            );
        }
    }

    public boolean shouldHaveAgreement(Booking booking) {
        if (booking == null || booking.getStatus() == null) {
            return false;
        }
        return booking.getStatus() != BookingStatus.PENDING_APPROVAL
                && booking.getStatus() != BookingStatus.DECLINED
                && !booking.getStatus().isTerminal();
    }

    // ── Snapshot builders ────────────────────────────────────────────────────

    private Map<String, Object> buildVehicleSnapshot(Car car) {
        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("carId", car.getId());
        snapshot.put("brand", car.getBrand());
        snapshot.put("model", car.getModel());
        snapshot.put("year", car.getYear());
        snapshot.put("licensePlate", car.getLicensePlate());
        snapshot.put("ownerId", car.getOwner().getId());
        snapshot.put("ownerName", car.getOwner().getFirstName() + " " + car.getOwner().getLastName());
        if (car.getRegistrationExpiryDate() != null) {
            snapshot.put("registrationExpiryDate", car.getRegistrationExpiryDate().toString());
        }
        if (car.getInsuranceExpiryDate() != null) {
            snapshot.put("insuranceExpiryDate", car.getInsuranceExpiryDate().toString());
        }
        return snapshot;
    }

    private Map<String, Object> buildTermsSnapshot(Booking booking) {
        Map<String, Object> terms = new TreeMap<>();
        terms.put("bookingId", booking.getId());
        terms.put("startTime", booking.getStartTime().toString());
        terms.put("endTime", booking.getEndTime().toString());
        terms.put("totalPrice", booking.getTotalPrice().toPlainString());
        terms.put("insuranceType", booking.getInsuranceType());
        terms.put("prepaidRefuel", booking.isPrepaidRefuel());
        if (booking.getSecurityDeposit() != null) {
            terms.put("securityDeposit", booking.getSecurityDeposit().toPlainString());
        }
        terms.put("renterId", booking.getRenter().getId());
        terms.put("renterName", booking.getRenter().getFirstName() + " " + booking.getRenter().getLastName());
        terms.put("agreementVersion", CURRENT_AGREEMENT_VERSION);
        terms.put("platformRole", "INTERMEDIARY");
        terms.put("contractType", "OWNER_RENTER_DIRECT");
        terms.put("termsTemplateId", CURRENT_TERMS_TEMPLATE_ID);
        return terms;
    }

    // ── Canonical hashing ────────────────────────────────────────────────────

    /**
     * Compute SHA-256 hash of the canonical agreement payload.
     * Uses sorted-key JSON serialization for deterministic output.
     */
    private String computeContentHash(Map<String, Object> vehicleSnapshot,
                                      Map<String, Object> termsSnapshot,
                                      Booking booking) {
        try {
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("bookingId", booking.getId());
            canonical.put("agreementVersion", CURRENT_AGREEMENT_VERSION);
            canonical.put("vehicle", vehicleSnapshot);
            canonical.put("terms", termsSnapshot);

            String json = stableMapper.writeValueAsString(canonical);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize agreement payload for hashing", e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private void ensureAcceptanceDeadlinePersisted(RentalAgreement agreement, Booking booking) {
        if (agreement.getAcceptanceDeadlineAt() == null) {
            agreement.setAcceptanceDeadlineAt(resolveAcceptanceDeadline(booking));
        }
        if (agreement.getRequiredNextActor() == null) {
            if (agreement.getOwnerAcceptedAt() != null && agreement.getRenterAcceptedAt() != null) {
                agreement.setRequiredNextActor(RentalAgreementActor.NONE);
            } else if (agreement.getOwnerAcceptedAt() == null && agreement.getRenterAcceptedAt() == null) {
                agreement.setRequiredNextActor(RentalAgreementActor.BOTH);
            } else {
                agreement.setRequiredNextActor(
                        agreement.getOwnerAcceptedAt() == null ? RentalAgreementActor.OWNER : RentalAgreementActor.RENTER
                );
            }
        }
    }

    private void ensureAcceptanceStillOpen(RentalAgreement agreement) {
        if (agreement.getStatus() == RentalAgreementStatus.EXPIRED) {
            throw new RentalAgreementConflictException(
                    "RENTAL_AGREEMENT_EXPIRED",
                    "Rok za prihvatanje ugovora o iznajmljivanju je istekao za ovu rezervaciju."
            );
        }

        LocalDateTime deadline = agreement.getAcceptanceDeadlineAt();
        if (deadline != null && !deadline.isAfter(LocalDateTime.now(SERBIA_ZONE))) {
            throw new RentalAgreementConflictException(
                    "RENTAL_AGREEMENT_ACCEPTANCE_CLOSED",
                    "Rok za prihvatanje ugovora o iznajmljivanju je istekao za ovu rezervaciju."
            );
        }
    }

    private LocalDateTime resolveAcceptanceDeadline(Booking booking) {
        LocalDateTime startTime = booking.getStartTime();
        if (startTime == null && booking.getStartDate() != null) {
            startTime = booking.getStartDate().atStartOfDay();
        }
        if (startTime == null) {
            return LocalDateTime.now(SERBIA_ZONE).plusMinutes(acceptanceDeadlineMinutesAfterTripStart);
        }
        return startTime.plusMinutes(acceptanceDeadlineMinutesAfterTripStart);
    }
}
