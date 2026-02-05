package org.example.rentoza.booking.checkin.verification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.checkin.*;
import org.example.rentoza.booking.checkin.verification.dto.IdVerificationStatusDTO;
import org.example.rentoza.booking.checkin.verification.dto.IdVerificationSubmitDTO;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Service for guest identity verification during check-in.
 * 
 * <h2>Verification Flow</h2>
 * <ol>
 *   <li>Guest initiates verification</li>
 *   <li>Guest captures selfie for liveness check</li>
 *   <li>Guest captures ID document (front/back)</li>
 *   <li>System extracts document data via OCR</li>
 *   <li>System matches selfie to document photo</li>
 *   <li>System compares extracted name to profile (Serbian-aware)</li>
 *   <li>Verification passes or fails</li>
 * </ol>
 */
@Service
@Slf4j
public class IdVerificationService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    private final IdVerificationRepository verificationRepository;
    private final BookingRepository bookingRepository;
    private final IdVerificationProvider verificationProvider;
    private final SerbianNameNormalizer nameNormalizer;
    private final CheckInEventService eventService;
    private final CheckInPhotoService photoService;

    // Metrics
    private final Counter verificationStartedCounter;
    private final Counter verificationPassedCounter;
    private final Counter verificationFailedCounter;
    private final Counter manualReviewCounter;

    @Value("${app.id-verification.liveness-threshold:0.85}")
    private double livenessThreshold;

    @Value("${app.id-verification.name-match-threshold:0.80}")
    private double nameMatchThreshold;

    @Value("${app.id-verification.max-attempts:3}")
    private int maxLivenessAttempts;

    @Value("${app.id-verification.skip-if-previously-verified:true}")
    private boolean skipIfPreviouslyVerified;

    public IdVerificationService(
            IdVerificationRepository verificationRepository,
            BookingRepository bookingRepository,
            IdVerificationProvider verificationProvider,
            SerbianNameNormalizer nameNormalizer,
            CheckInEventService eventService,
            CheckInPhotoService photoService,
            MeterRegistry meterRegistry) {
        this.verificationRepository = verificationRepository;
        this.bookingRepository = bookingRepository;
        this.verificationProvider = verificationProvider;
        this.nameNormalizer = nameNormalizer;
        this.eventService = eventService;
        this.photoService = photoService;

        this.verificationStartedCounter = Counter.builder("id_verification.started")
                .description("ID verifications started")
                .register(meterRegistry);

        this.verificationPassedCounter = Counter.builder("id_verification.passed")
                .description("ID verifications passed")
                .register(meterRegistry);

        this.verificationFailedCounter = Counter.builder("id_verification.failed")
                .description("ID verifications failed")
                .register(meterRegistry);

        this.manualReviewCounter = Counter.builder("id_verification.manual_review")
                .description("ID verifications sent to manual review")
                .register(meterRegistry);
    }

    // ========== STATUS RETRIEVAL ==========

    /**
     * Get current verification status for a booking.
     */
    @Transactional(readOnly = true)
    public IdVerificationStatusDTO getVerificationStatus(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));

        // Validate user is the guest
        if (!booking.getRenter().getId().equals(userId)) {
            throw new AccessDeniedException("Nemate pristup ovoj verifikaciji");
        }

        return verificationRepository.findByBookingId(bookingId)
                .map(this::mapToStatusDTO)
                .orElse(createInitialStatusDTO(booking));
    }

    // ========== INITIATE VERIFICATION ==========

    /**
     * Initialize ID verification for a booking.
     */
    @Transactional
    public IdVerificationStatusDTO initiateVerification(IdVerificationSubmitDTO dto, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(dto.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));

        // Validate user is the guest
        if (!booking.getRenter().getId().equals(userId)) {
            throw new AccessDeniedException("Samo gost može inicirati verifikaciju");
        }

        // Validate booking status allows check-in
        if (!booking.isCheckInWindowOpen()) {
            throw new IllegalStateException("Check-in prozor nije otvoren");
        }

        // Check if guest was previously verified
        if (skipIfPreviouslyVerified && verificationRepository.hasGuestBeenVerified(userId)) {
            log.info("[IdVerification] Guest {} was previously verified, creating auto-pass record", userId);
            return createAutoPassVerification(booking, userId);
        }

        // Check for existing verification
        CheckInIdVerification verification = verificationRepository.findByBookingId(booking.getId())
                .orElseGet(() -> createNewVerification(booking, dto));

        verificationStartedCounter.increment();

        return mapToStatusDTO(verification);
    }

    // ========== LIVENESS CHECK ==========

    /**
     * Submit selfie for liveness check.
     */
    @Transactional
    public IdVerificationStatusDTO submitLivenessCheck(Long bookingId, MultipartFile selfie, Long userId) throws IOException {
        CheckInIdVerification verification = getVerificationForUser(bookingId, userId);

        // Check max attempts
        if (verification.getLivenessAttempts() >= maxLivenessAttempts) {
            verification.requestManualReview("Premašen maksimalan broj pokušaja");
            verificationRepository.save(verification);
            manualReviewCounter.increment();
            return mapToStatusDTO(verification);
        }

        // Call provider
        IdVerificationProvider.LivenessResult result = verificationProvider.checkLiveness(
                selfie.getBytes(),
                selfie.getContentType()
        );

        // Record attempt
        BigDecimal score = result.getScore();
        boolean passed = result.isPassed() && score.doubleValue() >= livenessThreshold;
        verification.recordLivenessAttempt(score, passed);

        // Store selfie photo
        String storageKey = photoService.storeIdPhoto(
                verification.getBooking().getId(),
                verification.getCheckInSessionId(),
                selfie,
                "selfie"
        );
        verification.setSelfieStorageKey(storageKey);
        verification.setLivenessProvider(verificationProvider.getProviderName());

        if (passed) {
            log.info("[IdVerification] Liveness check passed for booking {} with score {}", bookingId, score);

            eventService.recordEvent(
                    verification.getBooking(),
                    verification.getCheckInSessionId(),
                    CheckInEventType.GUEST_ID_VERIFIED,
                    userId,
                    CheckInActorRole.GUEST,
                    java.util.Map.of("step", "LIVENESS", "score", score)
            );
        } else {
            log.info("[IdVerification] Liveness check failed for booking {} with score {}", bookingId, score);

            if (verification.getLivenessAttempts() >= maxLivenessAttempts) {
                verification.requestManualReview("Neuspela provera živosti nakon " + maxLivenessAttempts + " pokušaja");
                manualReviewCounter.increment();
            }
        }

        verificationRepository.save(verification);
        return mapToStatusDTO(verification);
    }

    // ========== DOCUMENT SUBMISSION ==========

    /**
     * Submit ID document for verification.
     */
    @Transactional
    public IdVerificationStatusDTO submitDocument(
            Long bookingId,
            MultipartFile frontImage,
            MultipartFile backImage,
            Long userId) throws IOException {

        CheckInIdVerification verification = getVerificationForUser(bookingId, userId);

        // Verify liveness was passed first
        if (!verification.getLivenessPassed()) {
            throw new IllegalStateException("Prvo morate proći proveru živosti");
        }

        // Call provider for OCR extraction
        IdVerificationProvider.DocumentExtraction extraction = verificationProvider.extractDocumentData(
                frontImage.getBytes(),
                backImage != null ? backImage.getBytes() : null,
                frontImage.getContentType()
        );

        if (!extraction.isSuccess()) {
            verification.markFailed(
                    IdVerificationStatus.FAILED_DOCUMENT_UNREADABLE,
                    extraction.getErrorMessage() != null ? extraction.getErrorMessage() : "Nije moguće pročitati dokument"
            );
            verificationRepository.save(verification);
            verificationFailedCounter.increment();
            return mapToStatusDTO(verification);
        }

        // Store document photos
        String frontKey = photoService.storeIdPhoto(
                verification.getBooking().getId(),
                verification.getCheckInSessionId(),
                frontImage,
                "id_front"
        );
        verification.setIdPhotoFrontStorageKey(frontKey);

        if (backImage != null) {
            String backKey = photoService.storeIdPhoto(
                    verification.getBooking().getId(),
                    verification.getCheckInSessionId(),
                    backImage,
                    "id_back"
            );
            verification.setIdPhotoBackStorageKey(backKey);
        }

        // Update document fields
        verification.setDocumentType(extraction.getDocumentType());
        verification.setDocumentCountry(extraction.getCountryCode());
        verification.setExtractedFirstName(extraction.getFirstName());
        verification.setExtractedLastName(extraction.getLastName());
        verification.setDocumentExpiry(extraction.getExpiryDate());

        // Check document expiry
        if (extraction.getExpiryDate() != null) {
            boolean expiryValid = extraction.getExpiryDate().isAfter(verification.getBooking().getEndDate());
            verification.setDocumentExpiryValid(expiryValid);

            if (!expiryValid) {
                verification.markFailed(
                        IdVerificationStatus.FAILED_DOCUMENT_EXPIRED,
                        "Dokument ističe pre kraja rezervacije"
                );
                verificationRepository.save(verification);
                verificationFailedCounter.increment();
                return mapToStatusDTO(verification);
            }
        }

        // Name matching
        User guest = verification.getGuest();
        String profileName = nameNormalizer.normalizeFullName(guest.getFirstName(), guest.getLastName());
        String extractedName = nameNormalizer.normalizeFullName(extraction.getFirstName(), extraction.getLastName());
        double matchScore = nameNormalizer.jaroWinklerSimilarity(profileName, extractedName);

        verification.recordNameMatch(extractedName, profileName, BigDecimal.valueOf(matchScore));

        if (matchScore < nameMatchThreshold) {
            log.warn("[IdVerification] Name mismatch for booking {}: profile='{}', extracted='{}', score={}",
                    bookingId, profileName, extractedName, matchScore);

            verification.markFailed(
                    IdVerificationStatus.FAILED_NAME_MISMATCH,
                    String.format("Ime na dokumentu ne odgovara profilu (%.0f%% podudarnost)", matchScore * 100)
            );
            verificationRepository.save(verification);
            verificationFailedCounter.increment();

            eventService.recordEvent(
                    verification.getBooking(),
                    verification.getCheckInSessionId(),
                    CheckInEventType.GUEST_ID_FAILED,
                    userId,
                    CheckInActorRole.GUEST,
                    java.util.Map.of("reason", "NAME_MISMATCH", "score", matchScore)
            );

            return mapToStatusDTO(verification);
        }

        // All checks passed
        verification.markPassed();
        verificationRepository.save(verification);
        verificationPassedCounter.increment();

        log.info("[IdVerification] Verification passed for booking {}", bookingId);

        eventService.recordEvent(
                verification.getBooking(),
                verification.getCheckInSessionId(),
                CheckInEventType.GUEST_ID_VERIFIED,
                userId,
                CheckInActorRole.GUEST,
                java.util.Map.of(
                        "documentType", extraction.getDocumentType(),
                        "nameMatchScore", matchScore,
                        "livenessScore", verification.getLivenessScore()
                )
        );

        return mapToStatusDTO(verification);
    }

    // ========== HELPER METHODS ==========

    private CheckInIdVerification getVerificationForUser(Long bookingId, Long userId) {
        CheckInIdVerification verification = verificationRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Verifikacija nije pronađena"));

        if (!verification.getGuest().getId().equals(userId)) {
            throw new AccessDeniedException("Nemate pristup ovoj verifikaciji");
        }

        return verification;
    }

    private CheckInIdVerification createNewVerification(Booking booking, IdVerificationSubmitDTO dto) {
        CheckInIdVerification verification = CheckInIdVerification.builder()
                .booking(booking)
                .checkInSessionId(booking.getCheckInSessionId())
                .guest(booking.getRenter())
                .documentType(dto.getDocumentType())
                .documentCountry(dto.getDocumentCountry() != null ? dto.getDocumentCountry() : "SRB")
                .verificationStatus(IdVerificationStatus.PENDING)
                .livenessPassed(false)
                .livenessAttempts(0)
                .build();

        return verificationRepository.save(verification);
    }

    private IdVerificationStatusDTO createAutoPassVerification(Booking booking, Long userId) {
        CheckInIdVerification verification = CheckInIdVerification.builder()
                .booking(booking)
                .checkInSessionId(booking.getCheckInSessionId())
                .guest(booking.getRenter())
                .verificationStatus(IdVerificationStatus.PASSED)
                .verificationMessage("Gost prethodno verifikovan")
                .livenessPassed(true)
                .livenessAttempts(0)
                .nameMatchPassed(true)
                .documentExpiryValid(true)
                .verifiedAt(Instant.now())
                .build();

        verification = verificationRepository.save(verification);

        eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.GUEST_ID_VERIFIED,
                userId,
                CheckInActorRole.GUEST,
                java.util.Map.of("autoPass", true, "reason", "PREVIOUSLY_VERIFIED")
        );

        verificationPassedCounter.increment();
        return mapToStatusDTO(verification);
    }

    private IdVerificationStatusDTO createInitialStatusDTO(Booking booking) {
        return IdVerificationStatusDTO.builder()
                .bookingId(booking.getId())
                .status(IdVerificationStatus.PENDING)
                .statusMessage("Verifikacija nije započeta")
                .canProceed(false)
                .needsManualReview(false)
                .livenessAttempts(0)
                .maxLivenessAttempts(maxLivenessAttempts)
                .nextStep("INITIATE")
                .requiredActions(new String[]{"Pokrenite verifikaciju", "Fotografišite selfie", "Fotografišite dokument"})
                .build();
    }

    private IdVerificationStatusDTO mapToStatusDTO(CheckInIdVerification v) {
        String nextStep = determineNextStep(v);
        String[] requiredActions = determineRequiredActions(v);

        return IdVerificationStatusDTO.builder()
                .verificationId(v.getId())
                .bookingId(v.getBooking().getId())
                .status(v.getVerificationStatus())
                .statusMessage(v.getVerificationMessage())
                .canProceed(v.canProceed())
                .needsManualReview(v.needsManualReview())
                .livenessPassed(v.getLivenessPassed())
                .livenessScore(v.getLivenessScore())
                .livenessAttempts(v.getLivenessAttempts())
                .maxLivenessAttempts(maxLivenessAttempts)
                .documentType(v.getDocumentType())
                .documentCountry(v.getDocumentCountry())
                .documentExpiryValid(v.getDocumentExpiryValid())
                .nameMatchPassed(v.getNameMatchPassed())
                .nameMatchScore(v.getNameMatchScore())
                .createdAt(toLocalDateTime(v.getCreatedAt()))
                .verifiedAt(toLocalDateTime(v.getVerifiedAt()))
                .nextStep(nextStep)
                .requiredActions(requiredActions)
                .build();
    }

    private String determineNextStep(CheckInIdVerification v) {
        if (v.getVerificationStatus().isPassed()) {
            return "COMPLETE";
        }
        if (v.getVerificationStatus().isFailed() || v.getVerificationStatus().requiresReview()) {
            return "BLOCKED";
        }
        if (!v.getLivenessPassed()) {
            return "LIVENESS";
        }
        return "DOCUMENT";
    }

    private String[] determineRequiredActions(CheckInIdVerification v) {
        if (v.getVerificationStatus().isPassed()) {
            return new String[]{"Verifikacija završena"};
        }
        if (v.getVerificationStatus().requiresReview()) {
            return new String[]{"Čeka se pregled administratora"};
        }
        if (v.getVerificationStatus().isFailed()) {
            return new String[]{v.getVerificationMessage()};
        }
        if (!v.getLivenessPassed()) {
            return new String[]{"Fotografišite selfie za proveru živosti"};
        }
        return new String[]{"Fotografišite prednju stranu dokumenta", "Fotografišite zadnju stranu dokumenta"};
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, SERBIA_ZONE);
    }
}

