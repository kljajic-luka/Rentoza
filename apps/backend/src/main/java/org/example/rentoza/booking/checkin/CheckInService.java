package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.dto.*;
import org.example.rentoza.booking.checkin.GeofenceService.GeofenceResult;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.dispute.DamageClaimStatus;
import org.example.rentoza.booking.dispute.DisputeStage;
import org.example.rentoza.booking.dispute.DisputeType;
import org.example.rentoza.car.Car;
import org.example.rentoza.common.GeoPoint;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.security.LockboxEncryptionService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.user.dto.BookingEligibilityDTO;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Core orchestrator for the check-in workflow.
 * 
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Host check-in completion (photos, odometer, fuel)</li>
 *   <li>Guest condition acknowledgment</li>
 *   <li>Handshake confirmation with geofence validation</li>
 *   <li>Trip start transition</li>
 *   <li>No-show processing</li>
 * </ul>
 * 
 * <h2>Concurrency</h2>
 * <p>Handshake uses pessimistic locking to prevent duplicate trip starts.
 * 
 * @see CheckInScheduler for automated state transitions
 * @see CheckInEventService for audit trail
 */
@Service
@Slf4j
public class CheckInService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    private static final int REQUIRED_HOST_PHOTO_TYPES = 8;

    private final BookingRepository bookingRepository;
    private final CheckInEventService eventService;
    private final CheckInPhotoRepository photoRepository;
    private final GuestCheckInPhotoRepository guestPhotoRepository;
    private final GeofenceService geofenceService;
    private final NotificationService notificationService;
    private final LockboxEncryptionService lockboxEncryptionService;
    private final RenterVerificationService renterVerificationService;
    private final FeatureFlags featureFlags;
    private final CheckInValidationService validationService;
    
    // VAL-004: Dependencies for check-in dispute flow
    private final DamageClaimRepository damageClaimRepository;
    private final UserRepository userRepository;
    
    private static final int REQUIRED_GUEST_PHOTO_TYPES = 8;
    
    // Metrics
    private final Counter hostCompletedCounter;
    private final Counter guestCompletedCounter;
    private final Counter handshakeCompletedCounter;
    private final Timer checkInDurationTimer;
    private final Counter earlyCheckInBlockedCounter;
    private final Counter licenseVerificationCounter;

    @Value("${app.checkin.noshow.grace-minutes:30}")
    private int noShowGraceMinutes;
    
    // ========== PHASE 4: SAFETY IMPROVEMENTS CONFIGURATION ==========
    
    // Phase 4A: Check-in Timing & Insurance Alignment
    @Value("${app.checkin.max-early-hours:1}")
    private int maxEarlyCheckInHours;
    
    @Value("${app.checkin.timing.validation-enabled:true}")
    private boolean checkInTimingValidationEnabled;
    
    // Phase 4B: License Verification
    @Value("${app.checkin.license-verification.required:true}")
    private boolean licenseVerificationRequired;
    
    @Value("${app.checkin.license-verification.enabled:true}")
    private boolean licenseVerificationEnabled;
    
    // Phase 4C: Hardened No-Show Logic
    @Value("${app.checkin.noshow.require-message-attempt:true}")
    private boolean requireMessageAttemptForNoShow;
    
    @Value("${app.checkin.noshow.short-trip-threshold-hours:24}")
    private int shortTripThresholdHours;
    
    @Value("${app.checkin.noshow.short-trip-grace-minutes:15}")
    private int shortTripGraceMinutes;
    
    @Value("${app.checkin.noshow.long-trip-grace-minutes:30}")
    private int longTripGraceMinutes;
    
    // Phase 4I: Begun Notifications
    @Value("${app.checkin.begun-notifications.enabled:true}")
    private boolean begunNotificationsEnabled;

    public CheckInService(
            BookingRepository bookingRepository,
            CheckInEventService eventService,
            CheckInPhotoRepository photoRepository,
            GuestCheckInPhotoRepository guestPhotoRepository,
            GeofenceService geofenceService,
            NotificationService notificationService,
            LockboxEncryptionService lockboxEncryptionService,
            RenterVerificationService renterVerificationService,
            FeatureFlags featureFlags,
            CheckInValidationService validationService,
            DamageClaimRepository damageClaimRepository,
            UserRepository userRepository,
            MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.eventService = eventService;
        this.photoRepository = photoRepository;
        this.guestPhotoRepository = guestPhotoRepository;
        this.geofenceService = geofenceService;
        this.notificationService = notificationService;
        this.lockboxEncryptionService = lockboxEncryptionService;
        this.renterVerificationService = renterVerificationService;
        this.featureFlags = featureFlags;
        this.validationService = validationService;
        this.damageClaimRepository = damageClaimRepository;
        this.userRepository = userRepository;
        
        this.hostCompletedCounter = Counter.builder("checkin.host.completed")
                .description("Host check-in completions")
                .register(meterRegistry);
        
        this.guestCompletedCounter = Counter.builder("checkin.guest.completed")
                .description("Guest check-in completions")
                .register(meterRegistry);
        
        this.handshakeCompletedCounter = Counter.builder("checkin.handshake.completed")
                .description("Successful trip starts")
                .register(meterRegistry);
        
        this.checkInDurationTimer = Timer.builder("checkin.duration")
                .description("Time from check-in open to trip start")
                .register(meterRegistry);
        
        this.earlyCheckInBlockedCounter = Counter.builder("checkin.early.blocked")
                .description("Early check-in attempts blocked for insurance compliance")
                .register(meterRegistry);
        
        this.licenseVerificationCounter = Counter.builder("checkin.license.verified")
                .description("In-person license verifications completed")
                .register(meterRegistry);
    }

    // ========== STATUS RETRIEVAL ==========

    /**
     * Get current check-in status for a booking.
     */
    @Transactional(readOnly = true)
    public CheckInStatusDTO getCheckInStatus(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Delegate to validation service
        validationService.validateAccess(booking, userId);
        
        return mapToStatusDTO(booking, userId);
    }

    // ========== HOST WORKFLOW ==========

    /**
     * Complete host check-in with odometer/fuel readings.
     * 
     * <p><b>Phase 4A Enhancement:</b> Now validates check-in timing to ensure
     * insurance coverage is active. Check-in cannot be completed more than
     * {@code maxEarlyCheckInHours} (default: 1 hour) before trip start.
     * 
     * @param dto Submission data
     * @param userId Current user ID
     * @return Updated check-in status
     * @throws IllegalStateException if check-in is attempted too early (Phase 4A)
     */
    @Transactional
    public CheckInStatusDTO completeHostCheckIn(HostCheckInSubmissionDTO dto, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(dto.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate host access (delegated to validation service)
        validationService.validateHostAccess(booking, userId);
        
        // Validate status
        if (booking.getStatus() != BookingStatus.CHECK_IN_OPEN) {
            throw new IllegalStateException("Prijem nije otvoren. Trenutni status: " + booking.getStatus());
        }
        
        // ========================================================================
        // PHASE 4A: VALIDATE CHECK-IN TIMING FOR INSURANCE COMPLIANCE
        // ========================================================================
        // Ensures check-in cannot be completed more than 1 hour before trip start.
        // This prevents insurance coverage gaps where the vehicle is handed over
        // before the policy's effective start time.
        validationService.validateCheckInTiming(booking, userId, CheckInActorRole.HOST);
        
        // Validate photos
        long validPhotoTypes = photoRepository.countRequiredHostPhotoTypes(booking.getId());
        if (validPhotoTypes < REQUIRED_HOST_PHOTO_TYPES) {
            throw new IllegalStateException(String.format(
                "Potrebno je minimum %d tipova fotografija. Pronađeno: %d", 
                REQUIRED_HOST_PHOTO_TYPES, validPhotoTypes));
        }
        
        // Update booking
        booking.setStartOdometer(dto.getOdometerReading());
        booking.setStartFuelLevel(dto.getFuelLevelPercent());
        booking.setHostCheckInCompletedAt(Instant.now());
        booking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
        
        // ========================================================================
        // PHASE 2: DERIVE CAR LOCATION FROM FIRST VALID PHOTO EXIF GPS
        // ========================================================================
        // Turo-style simplification: Car location is no longer submitted by host.
        // Instead, we derive it from the first uploaded photo with valid EXIF GPS.
        //
        // Benefits:
        // - Fixes orphaned carLatitude/carLongitude problem
        // - Eliminates GPS permission friction for host
        // - Photos are evidence (trust model)
        // - Simpler UX (matches industry standard)
        //
        // Fallback: If no photo has GPS, check-in proceeds anyway (CAR_LOCATION_MISSING event logged)
        
        Optional<CheckInPhoto> firstPhotoWithGps = photoRepository
                .findByBookingId(booking.getId())
                .stream()
                .filter(photo -> !photo.isDeleted())
                .filter(photo -> photo.getExifLatitude() != null && photo.getExifLongitude() != null)
                .filter(photo -> photo.getExifValidationStatus() == ExifValidationStatus.VALID ||
                               photo.getExifValidationStatus() == ExifValidationStatus.VALID_WITH_WARNINGS)
                .sorted((p1, p2) -> p1.getUploadedAt().compareTo(p2.getUploadedAt())) // First chronologically
                .findFirst();
        
        if (firstPhotoWithGps.isPresent()) {
            CheckInPhoto photo = firstPhotoWithGps.get();
            booking.setCarLatitude(photo.getExifLatitude());
            booking.setCarLongitude(photo.getExifLongitude());
            
            // Log derivation event for audit trail
            eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.CAR_LOCATION_DERIVED,
                userId,
                CheckInActorRole.HOST,
                Map.of(
                    "photoId", photo.getId(),
                    "photoType", photo.getPhotoType().name(),
                    "latitude", photo.getExifLatitude().doubleValue(),
                    "longitude", photo.getExifLongitude().doubleValue(),
                    "photoTimestamp", photo.getExifTimestamp() != null ? photo.getExifTimestamp().toString() : "UNKNOWN",
                    "derivationMethod", "FIRST_VALID_EXIF"
                )
            );
            
            log.info("[CheckIn-Phase2] Car location derived from photo ID {} (type: {}) for booking {}",
                    photo.getId(), photo.getPhotoType(), booking.getId());
        } else {
            // No photo with GPS found - log warning but allow check-in (trust model)
            long photoCount = photoRepository.findByBookingId(booking.getId()).stream()
                    .filter(p -> !p.isDeleted())
                    .count();
            
            eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.CAR_LOCATION_MISSING,
                userId,
                CheckInActorRole.HOST,
                Map.of(
                    "reason", "NO_GPS_IN_PHOTOS",
                    "photoCount", photoCount,
                    "photosChecked", photoCount,
                    "photosWithoutGps", photoCount
                )
            );
            
            log.warn("[CheckIn-Phase2] No photo with EXIF GPS found for booking {}. Check-in proceeds without car location (trust model).",
                    booking.getId());
        }
        
        // PHASE 2: Location variance validation REMOVED
        // Old logic calculated distance between car location and pickup location,
        // blocking check-in if >2km variance. This added complexity without clear benefit.
        // Now we trust photos by default and use audit trail for dispute resolution.
        // See CheckInEventType.CAR_LOCATION_DERIVED for complete audit data.
        
        // Set host location
        if (dto.getHostLatitude() != null && dto.getHostLongitude() != null) {
            booking.setHostCheckInLatitude(BigDecimal.valueOf(dto.getHostLatitude()));
            booking.setHostCheckInLongitude(BigDecimal.valueOf(dto.getHostLongitude()));
        }
        
        // Handle lockbox code with AES-256-GCM encryption
        if (dto.getLockboxCode() != null && !dto.getLockboxCode().isBlank()) {
            byte[] encryptedCode = lockboxEncryptionService.encrypt(dto.getLockboxCode());
            booking.setLockboxCodeEncrypted(encryptedCode);
            
            log.info("[CheckIn] Lockbox code encrypted and stored for booking {}", booking.getId());
            
            eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.HOST_LOCKBOX_SUBMITTED,
                userId,
                CheckInActorRole.HOST,
                Map.of("codeLength", dto.getLockboxCode().length())
            );
        }
        
        // Record events
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.HOST_ODOMETER_SUBMITTED,
            userId,
            CheckInActorRole.HOST,
            Map.of("reading", dto.getOdometerReading())
        );
        
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.HOST_FUEL_SUBMITTED,
            userId,
            CheckInActorRole.HOST,
            Map.of("levelPercent", dto.getFuelLevelPercent())
        );
        
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.HOST_SECTION_COMPLETE,
            userId,
            CheckInActorRole.HOST,
            Map.of(
                "photoCount", validPhotoTypes,
                "odometerSubmitted", true,
                "fuelSubmitted", true
            )
        );
        
        bookingRepository.save(booking);
        
        hostCompletedCounter.increment();
        log.info("[CheckIn] Host completed check-in for booking {}", booking.getId());
        
        // Notify guest
        notifyGuestCheckInReady(booking);
        
        return mapToStatusDTO(booking, userId);
    }

    // ========== GUEST WORKFLOW ==========

    /**
     * Guest acknowledges vehicle condition or raises a dispute.
     * 
     * <p><b>VAL-004 Enhancement:</b> If guest disputes pre-existing damage,
     * creates a DamageClaim and puts booking into CHECK_IN_DISPUTE status.
     */
    @Transactional
    public CheckInStatusDTO acknowledgeCondition(GuestConditionAcknowledgmentDTO dto, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(dto.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate guest access (delegated to validation service)
        validationService.validateGuestAccess(booking, userId);
        
        // Validate status - allow CHECK_IN_HOST_COMPLETE or CHECK_IN_DISPUTE (for re-submission after dispute declined)
        if (booking.getStatus() != BookingStatus.CHECK_IN_HOST_COMPLETE &&
            booking.getStatus() != BookingStatus.CHECK_IN_DISPUTE) {
            throw new IllegalStateException(
                "Domaćin još nije završio prijem. Trenutni status: " + booking.getStatus());
        }
        
        // Set guest location
        if (dto.getGuestLatitude() != null && dto.getGuestLongitude() != null) {
            booking.setGuestCheckInLatitude(BigDecimal.valueOf(dto.getGuestLatitude()));
            booking.setGuestCheckInLongitude(BigDecimal.valueOf(dto.getGuestLongitude()));
        }
        
        // VAL-004: Check if guest is disputing pre-existing damage
        if (Boolean.TRUE.equals(dto.getDisputePreExistingDamage())) {
            return handleCheckInDispute(booking, dto, userId);
        }
        
        // Normal flow: condition accepted
        if (!dto.getConditionAccepted()) {
            throw new IllegalArgumentException("Morate potvrditi stanje vozila ili prijaviti štetu da biste nastavili");
        }
        
        // Update booking
        booking.setGuestCheckInCompletedAt(Instant.now());
        booking.setStatus(BookingStatus.CHECK_IN_COMPLETE);
        
        // Record event
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.GUEST_CONDITION_ACKNOWLEDGED,
            userId,
            CheckInActorRole.GUEST,
            Map.of(
                "conditionAccepted", true,
                "hotspotsMarked", dto.getHotspots() != null ? dto.getHotspots().size() : 0,
                "comment", dto.getConditionComment() != null ? dto.getConditionComment() : ""
            )
        );
        
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.GUEST_SECTION_COMPLETE,
            userId,
            CheckInActorRole.GUEST,
            Map.of("conditionAcknowledged", true)
        );
        
        // Process hotspots if any
        if (dto.getHotspots() != null) {
            for (int i = 0; i < dto.getHotspots().size(); i++) {
                HotspotMarkingDTO hotspot = dto.getHotspots().get(i);
                eventService.recordEvent(
                    booking,
                    booking.getCheckInSessionId(),
                    CheckInEventType.GUEST_HOTSPOT_MARKED,
                    userId,
                    CheckInActorRole.GUEST,
                    Map.of(
                        "hotspotId", i + 1,
                        "photoId", hotspot.getPhotoId(),
                        "xPercent", hotspot.getXPercent(),
                        "yPercent", hotspot.getYPercent(),
                        "description", hotspot.getDescription() != null ? hotspot.getDescription() : ""
                    )
                );
            }
        }
        
        bookingRepository.save(booking);
        
        guestCompletedCounter.increment();
        log.info("[CheckIn] Guest acknowledged condition for booking {}", booking.getId());
        
        // Notify both parties that handshake is ready
        notifyHandshakeReady(booking);
        
        return mapToStatusDTO(booking, userId);
    }
    
    /**
     * VAL-004: Handle guest check-in dispute for undisclosed pre-existing damage.
     * Creates a DamageClaim and notifies admin for review.
     */
    private CheckInStatusDTO handleCheckInDispute(Booking booking, GuestConditionAcknowledgmentDTO dto, Long userId) {
        User guest = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Korisnik nije pronađen"));
        
        // Determine dispute type
        DisputeType disputeType = DisputeType.PRE_EXISTING_DAMAGE;
        if (dto.getDisputeType() != null) {
            try {
                disputeType = DisputeType.valueOf(dto.getDisputeType().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid dispute type '{}', defaulting to PRE_EXISTING_DAMAGE", dto.getDisputeType());
            }
        }
        
        // Create damage claim for check-in dispute
        DamageClaim dispute = DamageClaim.builder()
                .booking(booking)
                .host(booking.getCar().getOwner())
                .guest(booking.getRenter())
                .reportedBy(guest)
                .description(dto.getDamageDisputeDescription())
                .status(DamageClaimStatus.CHECK_IN_DISPUTE_PENDING)
                .disputeStage(DisputeStage.CHECK_IN)
                .disputeType(disputeType)
                .disputedPhotoIds(dto.getDisputedPhotoIds() != null ? new ArrayList<>(dto.getDisputedPhotoIds()) : new ArrayList<>())
                .claimedAmount(BigDecimal.ZERO) // No monetary claim yet
                .createdAt(Instant.now())
                .build();
        
        damageClaimRepository.save(dispute);
        
        // Update booking status
        booking.setStatus(BookingStatus.CHECK_IN_DISPUTE);
        bookingRepository.save(booking);
        
        // Record event
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.GUEST_CONDITION_ACKNOWLEDGED,
            userId,
            CheckInActorRole.GUEST,
            Map.of(
                "conditionAccepted", false,
                "disputeRaised", true,
                "disputeType", disputeType.name(),
                "disputeDescription", dto.getDamageDisputeDescription(),
                "disputedPhotoIds", dto.getDisputedPhotoIds() != null ? dto.getDisputedPhotoIds().toString() : "[]"
            )
        );
        
        log.info("[CheckIn] Guest raised check-in dispute for booking {}. Dispute ID: {}", 
                booking.getId(), dispute.getId());
        
        // Notify admin
        notifyAdminCheckInDispute(booking, dispute);
        
        // Notify host about dispute
        notifyHostCheckInDispute(booking, dispute);
        
        return mapToStatusDTO(booking, userId);
    }
    
    /**
     * VAL-004: Notify admin about check-in dispute requiring review.
     */
    private void notifyAdminCheckInDispute(Booking booking, DamageClaim dispute) {
        try {
            // Log instead of system notification - admin will see in dispute queue
            log.warn("[VAL-004] URGENT: Check-in dispute raised for booking {}. Dispute ID: {}. " +
                    "Guest: {} {}. Vehicle: {} {}. Description: {}",
                    booking.getId(),
                    dispute.getId(),
                    booking.getRenter().getFirstName(),
                    booking.getRenter().getLastName(),
                    booking.getCar().getBrand(),
                    booking.getCar().getModel(),
                    dispute.getDescription());
        } catch (Exception e) {
            log.error("Failed to log admin notification for check-in dispute for booking {}", booking.getId(), e);
        }
    }
    
    /**
     * VAL-004: Notify host that guest raised a dispute about vehicle condition.
     */
    private void notifyHostCheckInDispute(Booking booking, DamageClaim dispute) {
        try {
            CreateNotificationRequestDTO notification = CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getCar().getOwner().getId())
                    .type(NotificationType.HOTSPOT_MARKED) // Reusing existing type for damage-related notification
                    .message(String.format(
                        "⚠️ Gost %s je prijavio neprijavljenu štetu na vašem vozilu %s %s. " +
                        "Preuzimanje je pauzirano dok admin ne pregleda prijavu.",
                        booking.getRenter().getFirstName(),
                        booking.getCar().getBrand(),
                        booking.getCar().getModel()
                    ))
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build();
            
            notificationService.createNotification(notification);
        } catch (Exception e) {
            log.error("Failed to notify host about check-in dispute for booking {}", booking.getId(), e);
        }
    }

    // ========== HANDSHAKE ==========

    /**
     * Confirm handshake (both parties must confirm to start trip).
     * Uses pessimistic locking to prevent race conditions when both parties confirm simultaneously.
     * 
     * CRITICAL: The pessimistic lock (SELECT FOR UPDATE) ensures only one transaction can
     * modify the booking at a time, preventing duplicate IN_TRIP transitions.
     */
    @Transactional
    public CheckInStatusDTO confirmHandshake(HandshakeConfirmationDTO dto, Long userId) {
        // Acquire pessimistic lock to prevent race conditions during concurrent handshake confirmations
        Booking booking = bookingRepository.findByIdWithLock(dto.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Idempotency check
        if (booking.getStatus() == BookingStatus.IN_TRIP) {
            log.info("[CheckIn] Handshake already completed for booking {} - idempotent return", dto.getBookingId());
            return mapToStatusDTO(booking, userId);
        }
        
        // Validate status
        if (booking.getStatus() != BookingStatus.CHECK_IN_COMPLETE) {
            throw new IllegalStateException(
                "Prijem nije završen. Trenutni status: " + booking.getStatus());
        }
        
        if (!dto.getConfirmed()) {
            throw new IllegalArgumentException("Morate potvrditi predaju vozila");
        }
        
        boolean isHost = isHost(booking, userId);
        boolean isGuest = isGuest(booking, userId);
        
        if (!isHost && !isGuest) {
            throw new AccessDeniedException("Niste učesnik ove rezervacije");
        }
        
        // Process host confirmation
        if (isHost) {
            if (booking.getHandshakeCompletedAt() != null && isHostHandshakeConfirmed(booking)) {
                log.debug("[CheckIn] Host already confirmed handshake for booking {}", dto.getBookingId());
            } else {
                eventService.recordEvent(
                    booking,
                    booking.getCheckInSessionId(),
                    CheckInEventType.HANDSHAKE_HOST_CONFIRMED,
                    userId,
                    CheckInActorRole.HOST,
                    Map.of(
                        "confirmedAt", Instant.now().toString(),
                        "verifiedPhysicalId", dto.getHostVerifiedPhysicalId() != null 
                            ? dto.getHostVerifiedPhysicalId() : false
                    )
                );
            }
        }
        
        // Process guest confirmation
        if (isGuest) {
            // DUAL-PARTY PHOTOS: Verify guest has uploaded required photos before handshake
            if (featureFlags.isDualPartyPhotosRequiredForHandshake() &&
                featureFlags.isDualPartyPhotosEnabledForBooking(booking.getId())) {
                
                long guestPhotoCount = guestPhotoRepository.countRequiredGuestPhotoTypes(booking.getId());
                
                if (guestPhotoCount < REQUIRED_GUEST_PHOTO_TYPES) {
                    log.warn("[CheckIn] Handshake blocked - guest photos incomplete: bookingId={}, uploadedCount={}, requiredCount={}", 
                        booking.getId(), guestPhotoCount, REQUIRED_GUEST_PHOTO_TYPES);
                    
                    eventService.recordEvent(
                        booking,
                        booking.getCheckInSessionId(),
                        CheckInEventType.GUEST_PHOTO_VALIDATION_FAILED,
                        userId,
                        CheckInActorRole.GUEST,
                        Map.of(
                            "uploadedCount", guestPhotoCount,
                            "requiredCount", REQUIRED_GUEST_PHOTO_TYPES,
                            "reason", "INCOMPLETE_PHOTOS"
                        )
                    );
                    
                    throw new ValidationException(
                        "Morate otpremiti sve obavezne fotografije pre potvrde primopredaje. " +
                        "Otpremljeno: " + guestPhotoCount + "/" + REQUIRED_GUEST_PHOTO_TYPES);
                }
                
                log.info("[CheckIn] Guest photos validated for booking {}: {}/{} required types uploaded",
                    booking.getId(), guestPhotoCount, REQUIRED_GUEST_PHOTO_TYPES);
            }
            
            // STRICT CHECK-IN: Verify license validity before handshake
            if (featureFlags.isStrictCheckinEnabled()) {
                BookingEligibilityDTO eligibility = renterVerificationService.checkBookingEligibility(
                    booking.getRenter().getId(), 
                    booking.getEndTime().toLocalDate()
                );
                
                if (!eligibility.isEligible()) {
                    log.warn("[CheckIn] Handshake blocked due to license issue: bookingId={}, reason={}", 
                        booking.getId(), eligibility.getBlockReason());
                    throw new ValidationException("Check-in blocked: " + eligibility.getMessageSr());
                }
            }

            // Geofence validation for remote handoff
            if (booking.getLockboxCodeEncrypted() != null && dto.getLatitude() != null) {
                // Infer location density for dynamic radius adjustment
                // Urban areas (Belgrade high-rises) get larger radius due to GPS multipath
                GeofenceService.LocationDensity density = geofenceService.inferLocationDensity(
                    booking.getCarLatitude(), booking.getCarLongitude()
                );
                
                GeofenceResult geoResult = geofenceService.validateProximity(
                    booking.getCarLatitude(), booking.getCarLongitude(),
                    BigDecimal.valueOf(dto.getLatitude()), BigDecimal.valueOf(dto.getLongitude()),
                    density
                );
                
                booking.setGeofenceDistanceMeters(geoResult.getDistanceMeters());
                
                if (geoResult.shouldBlock()) {
                    eventService.recordEvent(
                        booking,
                        booking.getCheckInSessionId(),
                        CheckInEventType.GEOFENCE_CHECK_FAILED,
                        userId,
                        CheckInActorRole.GUEST,
                        Map.of(
                            "distanceMeters", geoResult.getDistanceMeters(),
                            "thresholdMeters", geoResult.getRequiredRadiusMeters(),
                            "locationDensity", density != null ? density.name() : "UNKNOWN",
                            "dynamicRadiusApplied", geoResult.isDynamicRadiusApplied()
                        )
                    );
                    throw new GeofenceViolationException(geoResult.getReason());
                } else {
                    eventService.recordEvent(
                        booking,
                        booking.getCheckInSessionId(),
                        CheckInEventType.GEOFENCE_CHECK_PASSED,
                        userId,
                        CheckInActorRole.GUEST,
                        Map.of(
                            "distanceMeters", geoResult.getDistanceMeters(),
                            "thresholdMeters", geoResult.getRequiredRadiusMeters(),
                            "locationDensity", density != null ? density.name() : "UNKNOWN",
                            "dynamicRadiusApplied", geoResult.isDynamicRadiusApplied()
                        )
                    );
                }
                
                booking.setGuestCheckInLatitude(BigDecimal.valueOf(dto.getLatitude()));
                booking.setGuestCheckInLongitude(BigDecimal.valueOf(dto.getLongitude()));
            }
            
            if (!isGuestHandshakeConfirmed(booking)) {
                eventService.recordEvent(
                    booking,
                    booking.getCheckInSessionId(),
                    CheckInEventType.HANDSHAKE_GUEST_CONFIRMED,
                    userId,
                    CheckInActorRole.GUEST,
                    Map.of(
                        "confirmedAt", Instant.now().toString(),
                        "latitude", dto.getLatitude() != null ? dto.getLatitude() : "N/A",
                        "longitude", dto.getLongitude() != null ? dto.getLongitude() : "N/A"
                    )
                );
            }
        }
        
        // Check if both parties have confirmed
        boolean hostConfirmed = isHostHandshakeConfirmed(booking);
        boolean guestConfirmed = isGuestHandshakeConfirmed(booking);
        
        if (hostConfirmed && guestConfirmed) {
            // ====================================================================
            // PHASE 4B: VALIDATE LICENSE VERIFICATION FOR IN-PERSON HANDSHAKES
            // ====================================================================
            // For in-person handshakes (no lockbox), require the host to confirm
            // they have visually verified the guest's driver's license.
            // This is critical for insurance compliance.
            if (booking.getLockboxCodeEncrypted() == null) {
                // In-person handshake - require license verification
                validateLicenseVerification(booking);
            }
            
            // Start the trip!
            booking.setStatus(BookingStatus.IN_TRIP);
            booking.setHandshakeCompletedAt(Instant.now());
            booking.setTripStartedAt(Instant.now());
            
            eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.TRIP_STARTED,
                userId,
                isHost ? CheckInActorRole.HOST : CheckInActorRole.GUEST,
                Map.of(
                    "handshakeMethod", booking.getLockboxCodeEncrypted() != null ? "REMOTE" : "IN_PERSON",
                    "geofenceStatus", booking.getGeofenceDistanceMeters() != null ? "PASSED" : "N/A"
                )
            );
            
            handshakeCompletedCounter.increment();
            
            // Record check-in duration
            if (booking.getCheckInOpenedAt() != null) {
                Duration duration = Duration.between(booking.getCheckInOpenedAt(), Instant.now());
                checkInDurationTimer.record(duration);
            }
            
            log.info("[CheckIn] Trip started for booking {} - handshake complete", booking.getId());
            
            // Notify both parties
            notifyTripStarted(booking);
        }
        
        bookingRepository.save(booking);
        
        return mapToStatusDTO(booking, userId);
    }

    // ========== NO-SHOW HANDLING ==========

    /**
     * Process a no-show scenario.
     */
    @Transactional
    public void processNoShow(Booking booking, String party) {
        if ("HOST".equals(party)) {
            booking.setStatus(BookingStatus.NO_SHOW_HOST);
            
            eventService.recordSystemEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.NO_SHOW_HOST_TRIGGERED,
                Map.of(
                    "deadlineAt", booking.getStartTime().toString(),
                    "missedBy", calculateMissedBy(booking)
                )
            );
            
            // Notify guest
            notifyNoShow(booking, "HOST");
            
        } else if ("GUEST".equals(party)) {
            booking.setStatus(BookingStatus.NO_SHOW_GUEST);
            
            eventService.recordSystemEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.NO_SHOW_GUEST_TRIGGERED,
                Map.of(
                    "hostCompletedAt", booking.getHostCheckInCompletedAt().toString(),
                    "missedBy", calculateMissedBy(booking)
                )
            );
            
            // Notify host
            notifyNoShow(booking, "GUEST");
        }
        
        bookingRepository.save(booking);
        log.warn("[CheckIn] No-show processed for booking {}, party: {}", booking.getId(), party);
    }

    // ========== SCHEDULER SUPPORT METHODS ==========

    /**
     * Find bookings eligible for check-in window opening.
     * Uses exact timestamps for precise T-24h detection.
     * 
     * PERFORMANCE OPTIMIZATION (Phase 1 Critical Fix):
     * Uses database-side filtering via JPQL query with JOIN FETCH,
     * eliminating O(n) memory allocation from findAll().stream().
     * 
     * Query uses composite index: idx_booking_checkin_window(status, check_in_session_id, start_time)
     */
    @Transactional(readOnly = true)
    public List<Booking> findBookingsForCheckInWindowOpening(LocalDateTime startFrom, LocalDateTime startTo) {
        return bookingRepository.findBookingsForCheckInWindowOpening(startFrom, startTo);
    }

    /**
     * Manually force-open check-in window for a booking.
     * 
     * <p>DEV/ADMIN ONLY: Bypasses scheduler timing to immediately transition
     * a booking from ACTIVE to CHECK_IN_OPEN. Used for testing or when
     * scheduler timing doesn't align with operational needs.
     * 
     * @param bookingId The booking to open
     * @param requestingUserId The admin/dev user forcing the open
     * @throws IllegalStateException if booking not in ACTIVE status
     * @throws IllegalArgumentException if booking not found
     */
    @Transactional
    public void forceOpenCheckInWindow(Long bookingId, Long requestingUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
        
        // Only allow force-open for ACTIVE bookings (or already CHECK_IN_OPEN is no-op)
        if (booking.getStatus() == BookingStatus.CHECK_IN_OPEN) {
            log.info("[CheckIn] Booking {} already in CHECK_IN_OPEN status, no action needed", bookingId);
            return;
        }
        
        if (booking.getStatus() != BookingStatus.ACTIVE) {
            throw new IllegalStateException(String.format(
                "Cannot force-open check-in for booking %d: current status is %s, expected ACTIVE",
                bookingId, booking.getStatus()));
        }
        
        // Generate session and transition
        String sessionId = java.util.UUID.randomUUID().toString();
        booking.setCheckInSessionId(sessionId);
        booking.setCheckInOpenedAt(java.time.Instant.now());
        booking.setStatus(BookingStatus.CHECK_IN_OPEN);
        
        // Record audit event
        eventService.recordSystemEvent(
            booking,
            sessionId,
            CheckInEventType.CHECK_IN_OPENED,
            java.util.Map.of(
                "triggeredBy", "MANUAL_FORCE_OPEN",
                "requestingUserId", requestingUserId.toString(),
                "bookingStartTime", booking.getStartTime().toString()
            )
        );
        
        // Send notification to host
        notifyCheckInWindowOpened(booking);
        
        log.warn("[CheckIn] MANUAL: Force-opened check-in window for booking {} by user {} (session: {})",
            bookingId, requestingUserId, sessionId);
    }

    /**
     * Find bookings needing reminder notifications.
     * 
     * PERFORMANCE OPTIMIZATION (Phase 1 Critical Fix):
     * Uses database-side filtering via JPQL query with JOIN FETCH,
     * eliminating O(n) memory allocation from findAll().stream().
     */
    @Transactional(readOnly = true)
    public List<Booking> findBookingsNeedingReminder(BookingStatus status, Instant openedBefore) {
        return bookingRepository.findBookingsNeedingReminder(status, openedBefore);
    }

    /**
     * Find potential host no-shows.
     * Uses exact startTime for precise no-show detection.
     * 
     * PERFORMANCE OPTIMIZATION (Phase 1 Critical Fix):
     * Uses database-side filtering via JPQL query with JOIN FETCH,
     * eliminating O(n) memory allocation from findAll().stream().
     */
    @Transactional(readOnly = true)
    public List<Booking> findPotentialHostNoShows(BookingStatus status, LocalDateTime threshold) {
        // Adjust threshold to include grace period in query
        LocalDateTime thresholdWithGrace = threshold.minusMinutes(noShowGraceMinutes);
        return bookingRepository.findPotentialHostNoShows(status, thresholdWithGrace);
    }

    /**
     * Find potential guest no-shows.
     * 
     * PERFORMANCE OPTIMIZATION (Phase 1 Critical Fix):
     * Uses database-side filtering via JPQL query with JOIN FETCH,
     * eliminating O(n) memory allocation from findAll().stream().
     */
    @Transactional(readOnly = true)
    public List<Booking> findPotentialGuestNoShows(BookingStatus status, LocalDateTime threshold) {
        // Calculate threshold: host must have completed more than grace period ago
        Instant hostCompletedBefore = threshold.atZone(SERBIA_ZONE).toInstant()
                .minus(noShowGraceMinutes, ChronoUnit.MINUTES);
        return bookingRepository.findPotentialGuestNoShows(status, hostCompletedBefore);
    }

    // ========== NOTIFICATION METHODS ==========

    /**
     * Notify both host and guest when check-in window opens.
     * Host needs to upload photos, guest needs to prepare for pickup.
     */
    public void notifyCheckInWindowOpened(Booking booking) {
        User host = booking.getCar().getOwner();
        User guest = booking.getRenter();
        String carInfo = booking.getCar().getBrand() + " " + booking.getCar().getModel();
        
        // Notify host - needs to upload vehicle photos
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(host.getId())
                .type(NotificationType.CHECK_IN_WINDOW_OPENED)
                .message(String.format("Prijem vozila %s je otvoren. Otpremite fotografije vozila pre predaje gostu.", carInfo))
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
        
        // Notify guest - pickup time approaching
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(guest.getId())
                .type(NotificationType.CHECK_IN_WINDOW_OPENED)
                .message(String.format("Prijem vozila %s je otvoren. Pripremite se za preuzimanje vozila.", carInfo))
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
        
        log.info("[CheckIn] Sent check-in window opened notifications for booking {} to host {} and guest {}", 
            booking.getId(), host.getId(), guest.getId());
    }

    public void notifyGuestCheckInReady(Booking booking) {
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.CHECK_IN_HOST_COMPLETE)
                .message("Domaćin je završio prijem vozila. Potvrdite stanje vozila da biste započeli putovanje.")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
    }

    public void sendCheckInReminder(Booking booking, String recipient) {
        Long recipientId = "HOST".equals(recipient) 
                ? booking.getCar().getOwner().getId() 
                : booking.getRenter().getId();
        
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(recipientId)
                .type(NotificationType.CHECK_IN_REMINDER)
                .message("Podsetnik: Prijem vozila još nije završen. Molimo završite pre početka putovanja.")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
    }

    private void notifyHandshakeReady(Booking booking) {
        // Notify host
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getCar().getOwner().getId())
                .type(NotificationType.HANDSHAKE_CONFIRMED)
                .message("Gost je potvrdio stanje vozila. Potvrdite predaju da započnete putovanje.")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
        
        // Notify guest
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.HANDSHAKE_CONFIRMED)
                .message("Stanje vozila je potvrđeno. Sačekajte potvrdu domaćina ili potvrdite preuzimanje.")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
    }

    private void notifyTripStarted(Booking booking) {
        // Notify host
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getCar().getOwner().getId())
                .type(NotificationType.TRIP_STARTED)
                .message("Putovanje je započelo! Vozilo je predato gostu.")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
        
        // Notify guest
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.TRIP_STARTED)
                .message("Putovanje je započelo! Srećan put!")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
    }

    private void notifyNoShow(Booking booking, String noShowParty) {
        if ("HOST".equals(noShowParty)) {
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getRenter().getId())
                    .type(NotificationType.NO_SHOW_HOST)
                    .message("Domaćin se nije pojavio. Kontaktirajte podršku za povrat sredstava.")
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build());
        } else {
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(booking.getCar().getOwner().getId())
                    .type(NotificationType.NO_SHOW_GUEST)
                    .message("Gost se nije pojavio u roku od 30 minuta.")
                    .relatedEntityId(String.valueOf(booking.getId()))
                    .build());
        }
    }

    // ========== HELPER METHODS ==========

    private boolean isHost(Booking booking, Long userId) {
        return booking.getCar().getOwner().getId().equals(userId);
    }

    private boolean isGuest(Booking booking, Long userId) {
        return booking.getRenter().getId().equals(userId);
    }

    private void validateAccess(Booking booking, Long userId) {
        if (!isHost(booking, userId) && !isGuest(booking, userId)) {
            throw new AccessDeniedException("Nemate pristup ovoj rezervaciji");
        }
    }

    private boolean isHostHandshakeConfirmed(Booking booking) {
        return eventService.hasEventOfType(booking.getId(), CheckInEventType.HANDSHAKE_HOST_CONFIRMED);
    }

    private boolean isGuestHandshakeConfirmed(Booking booking) {
        return eventService.hasEventOfType(booking.getId(), CheckInEventType.HANDSHAKE_GUEST_CONFIRMED);
    }

    private String calculateMissedBy(Booking booking) {
        Instant now = Instant.now();
        Instant startInstant = booking.getStartTime().atZone(SERBIA_ZONE).toInstant();
        
        Duration missed = Duration.between(startInstant.plus(noShowGraceMinutes, ChronoUnit.MINUTES), now);
        return missed.toMinutes() + " minuta";
    }

    private CheckInStatusDTO mapToStatusDTO(Booking booking, Long userId) {
        boolean isHost = isHost(booking, userId);
        boolean isGuest = isGuest(booking, userId);
        
        // DIAGNOSTIC: Log role calculation inputs and outputs
        log.info("[CheckIn] DIAGNOSTIC: mapToStatusDTO - bookingId={}, userId={}, ownerId={}, renterId={}, isHost={}, isGuest={}",
            booking.getId(),
            userId,
            booking.getCar().getOwner().getId(),
            booking.getRenter().getId(),
            isHost,
            isGuest);
        
        List<CheckInPhotoDTO> photos = null;
        // Only show photos to guest after host completes, or always to host
        if (isHost || booking.getStatus().ordinal() >= BookingStatus.CHECK_IN_HOST_COMPLETE.ordinal()) {
            photos = photoRepository.findByBookingId(booking.getId()).stream()
                    .filter(p -> !p.isDeleted())
                    .map(this::mapToPhotoDTO)
                    .collect(Collectors.toList());
        }
        
        LocalDateTime noShowDeadline = null;
        Long minutesUntilNoShow = null;
        
        if (booking.getStatus() == BookingStatus.CHECK_IN_OPEN || 
            booking.getStatus() == BookingStatus.CHECK_IN_HOST_COMPLETE) {
            noShowDeadline = booking.getStartTime().plusMinutes(noShowGraceMinutes);
            minutesUntilNoShow = ChronoUnit.MINUTES.between(LocalDateTime.now(SERBIA_ZONE), noShowDeadline);
            if (minutesUntilNoShow < 0) minutesUntilNoShow = 0L;
        }
        
        // Calculate pickup location with fallback to car home location
        GeoPoint pickupLocation = getPickupLocationWithFallback(booking, booking.getCar());
        boolean isEstimated = booking.getPickupLocation() == null && pickupLocation != null;
        String estimatedSource = isEstimated ? "CAR_HOME_LOCATION" : null;
        Integer varianceMeters = booking.getPickupLocationVarianceMeters();
        String varianceStatus = calculateVarianceStatus(varianceMeters);
        
        return CheckInStatusDTO.builder()
                .bookingId(booking.getId())
                .checkInSessionId(booking.getCheckInSessionId())
                .status(booking.getStatus())
                .hostCheckInComplete(booking.getHostCheckInCompletedAt() != null)
                .guestCheckInComplete(booking.getGuestCheckInCompletedAt() != null)
                .handshakeReady(booking.getStatus() == BookingStatus.CHECK_IN_COMPLETE)
                .checkInOpenedAt(toLocalDateTime(booking.getCheckInOpenedAt()))
                .hostCompletedAt(toLocalDateTime(booking.getHostCheckInCompletedAt()))
                .guestCompletedAt(toLocalDateTime(booking.getGuestCheckInCompletedAt()))
                .handshakeCompletedAt(toLocalDateTime(booking.getHandshakeCompletedAt()))
                .vehiclePhotos(photos)
                .odometerReading(booking.getStartOdometer())
                .fuelLevelPercent(booking.getStartFuelLevel())
                .lockboxAvailable(booking.getLockboxCodeEncrypted() != null)
                .geofenceValid(booking.getGeofenceDistanceMeters() != null && 
                              booking.getGeofenceDistanceMeters() <= geofenceService.getDefaultRadiusMeters())
                .geofenceDistanceMeters(booking.getGeofenceDistanceMeters())
                .tripStartScheduled(booking.getStartTime())
                .noShowDeadline(noShowDeadline)
                .minutesUntilNoShow(minutesUntilNoShow)
                .isHost(isHost)
                .isGuest(isGuest)
                // Pickup location fields
                .pickupLatitude(pickupLocation != null ? pickupLocation.getLatitude().doubleValue() : null)
                .pickupLongitude(pickupLocation != null ? pickupLocation.getLongitude().doubleValue() : null)
                .pickupAddress(pickupLocation != null ? pickupLocation.getAddress() : null)
                .pickupCity(pickupLocation != null ? pickupLocation.getCity() : null)
                .pickupZipCode(pickupLocation != null ? pickupLocation.getZipCode() : null)
                .pickupLocationVarianceMeters(varianceMeters)
                .varianceStatus(varianceStatus)
                .isEstimatedLocation(isEstimated)
                .estimatedLocationSource(estimatedSource)
                // Phase 4B: License Verification - required for in-person handoffs (no lockbox)
                .licenseVerificationRequired(licenseVerificationEnabled && licenseVerificationRequired 
                        && booking.getLockboxCodeEncrypted() == null)
                .licenseVerifiedInPerson(booking.getLicenseVerifiedInPersonAt() != null)
                .licenseVerifiedInPersonAt(booking.getLicenseVerifiedInPersonAt() != null 
                        ? booking.getLicenseVerifiedInPersonAt().toString() : null)
                .car(CheckInStatusDTO.CarSummaryDTO.builder()
                        .id(booking.getCar().getId())
                        .brand(booking.getCar().getBrand())
                        .model(booking.getCar().getModel())
                        .year(booking.getCar().getYear())
                        .imageUrl(booking.getCar().getImageUrl())
                        .build())
                .build();
    }

    private CheckInPhotoDTO mapToPhotoDTO(CheckInPhoto photo) {
        return CheckInPhotoDTO.builder()
                .photoId(photo.getId())
                .photoType(photo.getPhotoType())
                .url(photo.getStorageKey())
                .uploadedAt(toLocalDateTime(photo.getUploadedAt()))
                .exifValidationStatus(photo.getExifValidationStatus())
                .exifValidationMessage(photo.getExifValidationMessage())
                .width(photo.getImageWidth())
                .height(photo.getImageHeight())
                .mimeType(photo.getMimeType())
                .exifTimestamp(toLocalDateTime(photo.getExifTimestamp()))
                .exifLatitude(photo.getExifLatitude() != null ? photo.getExifLatitude().doubleValue() : null)
                .exifLongitude(photo.getExifLongitude() != null ? photo.getExifLongitude().doubleValue() : null)
                .deviceModel(photo.getExifDeviceModel())
                .build();
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return LocalDateTime.ofInstant(instant, SERBIA_ZONE);
    }

    /**
     * Get pickup location with fallback to car's home location.
     * 
     * @param booking The booking (may have pickupLocation null)
     * @param car The car (should have locationGeoPoint)
     * @return GeoPoint for pickup, or null if both are unavailable
     */
    private GeoPoint getPickupLocationWithFallback(Booking booking, Car car) {
        if (booking.getPickupLocation() != null) {
            return booking.getPickupLocation();
        }
        if (car != null && car.getLocationGeoPoint() != null) {
            return car.getLocationGeoPoint();
        }
        return null;
    }

    /**
     * Calculate variance status from meters for UI badging.
     * 
     * @param varianceMeters Distance variance in meters (null = no variance)
     * @return "NONE", "WARNING", or "BLOCKING"
     */
    private String calculateVarianceStatus(Integer varianceMeters) {
        if (varianceMeters == null || varianceMeters < 100) {
            return "NONE";
        } else if (varianceMeters < 1000) {
            return "WARNING";
        } else {
            return "BLOCKING";
        }
    }

    // ========== PHASE 4: SAFETY VALIDATION METHODS ==========

    /**
     * Phase 4A: Validate check-in timing for insurance compliance.
     * 
     * <p>Check-in cannot be completed more than {@code maxEarlyCheckInHours} before trip start.
     * This ensures insurance coverage is active when the vehicle is handed over.
     * 
     * <p>Insurance policies typically begin at the scheduled trip start time. Completing
     * check-in too early creates a coverage gap where the vehicle is with the guest
     * but insurance is not yet active.
     * 
     * @param booking The booking to validate
     * @param userId The user attempting to complete check-in
     * @param actorRole HOST or GUEST
     * @throws IllegalStateException if check-in is attempted too early
     */
    private void validateCheckInTiming(Booking booking, Long userId, CheckInActorRole actorRole) {
        if (!checkInTimingValidationEnabled) {
            log.debug("[CheckIn-Phase4A] Timing validation disabled by configuration");
            return;
        }
        
        LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
        LocalDateTime tripStart = booking.getStartTime();
        LocalDateTime earliestAllowedCheckIn = tripStart.minusHours(maxEarlyCheckInHours);
        
        long minutesUntilTrip = ChronoUnit.MINUTES.between(now, tripStart);
        long maxEarlyMinutes = maxEarlyCheckInHours * 60L;
        
        if (now.isBefore(earliestAllowedCheckIn)) {
            // Log the blocked attempt
            eventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.EARLY_CHECK_IN_BLOCKED,
                userId,
                actorRole,
                Map.of(
                    "tripStartTime", tripStart.toString(),
                    "attemptTime", now.toString(),
                    "minutesUntilTrip", minutesUntilTrip,
                    "maxEarlyMinutes", maxEarlyMinutes,
                    "earliestAllowedTime", earliestAllowedCheckIn.toString()
                )
            );
            
            earlyCheckInBlockedCounter.increment();
            
            log.warn("[CheckIn-Phase4A] Early check-in blocked for booking {}. " +
                    "Attempt at {}, trip starts at {}, earliest allowed at {}",
                    booking.getId(), now, tripStart, earliestAllowedCheckIn);
            
            // Format user-friendly message
            String earliestTimeFormatted = earliestAllowedCheckIn.format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            
            throw new IllegalStateException(String.format(
                "Prijem nije dozvoljen više od %d sat(a) pre početka putovanja. " +
                "Molimo pokušajte ponovo u %s ili kasnije. " +
                "Ovo osigurava da je vaše osiguranje aktivno tokom predaje vozila.",
                maxEarlyCheckInHours,
                earliestTimeFormatted
            ));
        }
        
        // Log successful timing validation
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.CHECK_IN_TIMING_VALIDATED,
            userId,
            actorRole,
            Map.of(
                "tripStartTime", tripStart.toString(),
                "attemptTime", now.toString(),
                "minutesUntilTrip", minutesUntilTrip,
                "maxEarlyMinutes", maxEarlyMinutes
            )
        );
        
        log.debug("[CheckIn-Phase4A] Timing validation passed for booking {}. " +
                "Check-in at {}, trip starts at {} ({}min away)",
                booking.getId(), now, tripStart, minutesUntilTrip);
    }

    /**
     * Phase 4B: Validate license verification before handshake.
     * 
     * <p>When license verification is required, the host must confirm they have
     * visually verified the guest's driver's license in-person before the handshake
     * can proceed.
     * 
     * @param booking The booking to validate
     * @throws IllegalStateException if license verification is required but not completed
     */
    private void validateLicenseVerification(Booking booking) {
        if (!licenseVerificationEnabled || !licenseVerificationRequired) {
            log.debug("[CheckIn-Phase4B] License verification disabled or not required");
            return;
        }
        
        if (booking.getLicenseVerifiedInPersonAt() == null) {
            throw new IllegalStateException(
                "Morate potvrditi da ste lično proverili vozačku dozvolu gosta " +
                "pre završetka predaje vozila. Ovo je neophodno za validnost osiguranja."
            );
        }
        
        log.debug("[CheckIn-Phase4B] License verification confirmed for booking {} at {}",
                booking.getId(), booking.getLicenseVerifiedInPersonAt());
    }

    /**
     * Phase 4B: Host confirms in-person license verification.
     * 
     * <p>This method is called when the host confirms they have visually verified
     * the guest's driver's license. The verification is recorded with a timestamp
     * and creates an audit event.
     * 
     * @param bookingId The booking ID
     * @param userId The host user ID
     * @return Updated check-in status DTO
     * @throws AccessDeniedException if user is not the host
     * @throws ResourceNotFoundException if booking not found
     */
    @Transactional
    public CheckInStatusDTO confirmLicenseVerifiedInPerson(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        if (!isHost(booking, userId)) {
            throw new AccessDeniedException("Samo vlasnik vozila može potvrditi proveru vozačke dozvole");
        }
        
        // Validate booking is in appropriate state
        if (booking.getStatus() != BookingStatus.CHECK_IN_HOST_COMPLETE &&
            booking.getStatus() != BookingStatus.CHECK_IN_COMPLETE) {
            throw new IllegalStateException(
                "Provera vozačke dozvole je moguća samo nakon što domaćin završi prijem");
        }
        
        // Check if already verified (idempotent operation)
        if (booking.getLicenseVerifiedInPersonAt() != null) {
            log.info("[CheckIn-Phase4B] License already verified for booking {} at {}",
                    bookingId, booking.getLicenseVerifiedInPersonAt());
            return getCheckInStatus(bookingId, userId);
        }
        
        // Record verification
        Instant verifiedAt = Instant.now();
        booking.setLicenseVerifiedInPersonAt(verifiedAt);
        booking.setLicenseVerifiedByUserId(userId);
        
        // Get guest name for audit
        String guestName = booking.getRenter() != null 
            ? booking.getRenter().getFirstName()
            : "Unknown";
        
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            CheckInEventType.LICENSE_VERIFIED_IN_PERSON,
            userId,
            CheckInActorRole.HOST,
            Map.of(
                "guestUserId", booking.getRenter() != null ? booking.getRenter().getId() : "N/A",
                "guestName", guestName,
                "verifiedAt", verifiedAt.toString(),
                "hostUserId", userId
            )
        );
        
        bookingRepository.save(booking);
        licenseVerificationCounter.increment();
        
        log.info("[CheckIn-Phase4B] License verified in-person for booking {} by host {}. Guest: {}",
                bookingId, userId, guestName);
        
        return getCheckInStatus(bookingId, userId);
    }

    /**
     * Phase 4C: Get the appropriate no-show grace minutes based on trip length.
     * 
     * <p>Short trips (≤ 24 hours) have a tighter grace period since the impact
     * of delays is proportionally greater. Long trips get more lenient grace periods.
     * 
     * @param booking The booking to calculate grace for
     * @return Grace period in minutes
     */
    public int getNoShowGraceMinutesForTrip(Booking booking) {
        Duration tripDuration = Duration.between(
            booking.getStartTime(), 
            booking.getEndTime()
        );
        
        if (tripDuration.toHours() <= shortTripThresholdHours) {
            return shortTripGraceMinutes;
        }
        return longTripGraceMinutes;
    }

    /**
     * Phase 4I: Notify that check-in has begun.
     * 
     * <p>Sends a notification to the other party when check-in process begins
     * (first photo upload or interaction). Prevents duplicate notifications
     * via event deduplication.
     * 
     * @param bookingId The booking ID
     * @param userId The user starting check-in
     * @param party "HOST" or "GUEST"
     */
    @Transactional
    public void notifyCheckInBegun(Long bookingId, Long userId, String party) {
        if (!begunNotificationsEnabled) {
            return;
        }
        
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Determine event type based on party
        CheckInEventType eventType = "HOST".equals(party) 
            ? CheckInEventType.CHECK_IN_HOST_BEGUN 
            : CheckInEventType.CHECK_IN_GUEST_BEGUN;
        
        // Check if already notified (idempotency)
        if (eventService.hasEventOfType(bookingId, eventType)) {
            log.debug("[CheckIn-Phase4I] Already notified {} begun for booking {}", party, bookingId);
            return;
        }
        
        // Record event
        eventService.recordEvent(
            booking,
            booking.getCheckInSessionId(),
            eventType,
            userId,
            "HOST".equals(party) ? CheckInActorRole.HOST : CheckInActorRole.GUEST,
            Map.of(
                "begunAt", Instant.now().toString(),
                party.toLowerCase() + "UserId", userId
            )
        );
        
        // Send notification to other party
        NotificationType notifType = "HOST".equals(party)
            ? NotificationType.CHECK_IN_HOST_BEGUN
            : NotificationType.CHECK_IN_GUEST_BEGUN;
        
        Long recipientId = "HOST".equals(party) 
            ? booking.getRenter().getId() 
            : booking.getCar().getOwner().getId();
        
        String message = "HOST".equals(party)
            ? "Domaćin je počeo da dokumentuje stanje vozila. Pripremi se za preuzimanje."
            : "Gost je započeo proces prijema vozila.";
        
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
            .recipientId(recipientId)
            .type(notifType)
            .message(message)
            .relatedEntityId(String.valueOf(booking.getId()))
            .build());
        
        log.info("[CheckIn-Phase4I] {} begun notification sent for booking {} to user {}",
                party, bookingId, recipientId);
    }

    /**
     * Custom exception for geofence violations.
     */
    public static class GeofenceViolationException extends RuntimeException {
        public GeofenceViolationException(String message) {
            super(message);
        }
    }
}
