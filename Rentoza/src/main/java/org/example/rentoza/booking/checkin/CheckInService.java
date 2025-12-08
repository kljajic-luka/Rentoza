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
import org.example.rentoza.car.Car;
import org.example.rentoza.common.GeoPoint;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.security.LockboxEncryptionService;
import org.example.rentoza.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
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
    private final GeofenceService geofenceService;
    private final NotificationService notificationService;
    private final LockboxEncryptionService lockboxEncryptionService;
    
    // Metrics
    private final Counter hostCompletedCounter;
    private final Counter guestCompletedCounter;
    private final Counter handshakeCompletedCounter;
    private final Timer checkInDurationTimer;

    @Value("${app.checkin.noshow.grace-minutes:30}")
    private int noShowGraceMinutes;

    public CheckInService(
            BookingRepository bookingRepository,
            CheckInEventService eventService,
            CheckInPhotoRepository photoRepository,
            GeofenceService geofenceService,
            NotificationService notificationService,
            LockboxEncryptionService lockboxEncryptionService,
            MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.eventService = eventService;
        this.photoRepository = photoRepository;
        this.geofenceService = geofenceService;
        this.notificationService = notificationService;
        this.lockboxEncryptionService = lockboxEncryptionService;
        
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
    }

    // ========== STATUS RETRIEVAL ==========

    /**
     * Get current check-in status for a booking.
     */
    @Transactional(readOnly = true)
    public CheckInStatusDTO getCheckInStatus(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        validateAccess(booking, userId);
        
        return mapToStatusDTO(booking, userId);
    }

    // ========== HOST WORKFLOW ==========

    /**
     * Complete host check-in with odometer/fuel readings.
     * 
     * @param dto Submission data
     * @param userId Current user ID
     * @return Updated check-in status
     */
    @Transactional
    public CheckInStatusDTO completeHostCheckIn(HostCheckInSubmissionDTO dto, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(dto.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate host access
        if (!isHost(booking, userId)) {
            throw new AccessDeniedException("Samo vlasnik vozila može završiti prijem");
        }
        
        // Validate status
        if (booking.getStatus() != BookingStatus.CHECK_IN_OPEN) {
            throw new IllegalStateException("Prijem nije otvoren. Trenutni status: " + booking.getStatus());
        }
        
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
     * Guest acknowledges vehicle condition.
     */
    @Transactional
    public CheckInStatusDTO acknowledgeCondition(GuestConditionAcknowledgmentDTO dto, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(dto.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate guest access
        if (!isGuest(booking, userId)) {
            throw new AccessDeniedException("Samo gost može potvrditi stanje vozila");
        }
        
        // Validate status
        if (booking.getStatus() != BookingStatus.CHECK_IN_HOST_COMPLETE) {
            throw new IllegalStateException(
                "Domaćin još nije završio prijem. Trenutni status: " + booking.getStatus());
        }
        
        if (!dto.getConditionAccepted()) {
            throw new IllegalArgumentException("Morate potvrditi stanje vozila da biste nastavili");
        }
        
        // Update booking
        booking.setGuestCheckInCompletedAt(Instant.now());
        booking.setStatus(BookingStatus.CHECK_IN_COMPLETE);
        
        // Set guest location
        if (dto.getGuestLatitude() != null && dto.getGuestLongitude() != null) {
            booking.setGuestCheckInLatitude(BigDecimal.valueOf(dto.getGuestLatitude()));
            booking.setGuestCheckInLongitude(BigDecimal.valueOf(dto.getGuestLongitude()));
        }
        
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

    // ========== HANDSHAKE ==========

    /**
     * Confirm handshake (both parties must confirm to start trip).
     * Uses pessimistic locking to prevent race conditions.
     */
    @Transactional
    public CheckInStatusDTO confirmHandshake(HandshakeConfirmationDTO dto, Long userId) {
        // Acquire pessimistic lock
        Booking booking = bookingRepository.findById(dto.getBookingId())
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

    public void notifyCheckInWindowOpened(Booking booking) {
        User host = booking.getCar().getOwner();
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(host.getId())
                .type(NotificationType.CHECK_IN_WINDOW_OPENED)
                .message(String.format("Prijem vozila je otvoren za rezervaciju %s - %s. Otpremite fotografije vozila.", 
                    booking.getCar().getBrand(), booking.getCar().getModel()))
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
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

    /**
     * Custom exception for geofence violations.
     */
    public static class GeofenceViolationException extends RuntimeException {
        public GeofenceViolationException(String message) {
            super(message);
        }
    }
}
