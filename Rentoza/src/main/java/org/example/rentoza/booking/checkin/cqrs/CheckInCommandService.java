package org.example.rentoza.booking.checkin.cqrs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.CheckInActorRole;
import org.example.rentoza.booking.checkin.CheckInEventService;
import org.example.rentoza.booking.checkin.CheckInEventType;
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.checkin.GeofenceService;
import org.example.rentoza.booking.checkin.GeofenceService.GeofenceResult;
import org.example.rentoza.booking.checkin.dto.GuestConditionAcknowledgmentDTO;
import org.example.rentoza.booking.checkin.dto.HandshakeConfirmationDTO;
import org.example.rentoza.booking.checkin.dto.HostCheckInSubmissionDTO;
import org.example.rentoza.booking.checkin.dto.HotspotMarkingDTO;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.security.LockboxEncryptionService;
import org.example.rentoza.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * CQRS Command Service for Check-In Write Operations.
 * 
 * <h2>CQRS Pattern Implementation</h2>
 * <p>This service handles all write operations (commands) for the check-in workflow,
 * separating them from read operations (queries) for improved scalability and maintainability.
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
 * <h2>Event Publishing</h2>
 * <p>All state changes publish domain events via {@link ApplicationEventPublisher}
 * for read model synchronization and audit trail.
 * 
 * <h2>Concurrency</h2>
 * <p>Handshake uses pessimistic locking to prevent duplicate trip starts.
 * 
 * @see CheckInQueryService for read operations
 * @see CheckInStatusViewSyncListener for read model synchronization
 */
@Service
@Slf4j
public class CheckInCommandService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    private static final int REQUIRED_HOST_PHOTO_TYPES = 8;

    private final BookingRepository bookingRepository;
    private final CheckInEventService eventService;
    private final CheckInPhotoRepository photoRepository;
    private final GeofenceService geofenceService;
    private final NotificationService notificationService;
    private final LockboxEncryptionService lockboxEncryptionService;
    private final ApplicationEventPublisher eventPublisher;

    // Metrics
    private final Counter hostCompletedCounter;
    private final Counter guestCompletedCounter;
    private final Counter handshakeCompletedCounter;
    private final Timer checkInDurationTimer;

    @Value("${app.checkin.noshow.grace-minutes:30}")
    private int noShowGraceMinutes;

    public CheckInCommandService(
            BookingRepository bookingRepository,
            CheckInEventService eventService,
            CheckInPhotoRepository photoRepository,
            GeofenceService geofenceService,
            NotificationService notificationService,
            LockboxEncryptionService lockboxEncryptionService,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.eventService = eventService;
        this.photoRepository = photoRepository;
        this.geofenceService = geofenceService;
        this.notificationService = notificationService;
        this.lockboxEncryptionService = lockboxEncryptionService;
        this.eventPublisher = eventPublisher;

        this.hostCompletedCounter = Counter.builder("checkin.command.host.completed")
                .description("Host check-in completions via command service")
                .register(meterRegistry);

        this.guestCompletedCounter = Counter.builder("checkin.command.guest.completed")
                .description("Guest check-in completions via command service")
                .register(meterRegistry);

        this.handshakeCompletedCounter = Counter.builder("checkin.command.handshake.completed")
                .description("Successful trip starts via command service")
                .register(meterRegistry);

        this.checkInDurationTimer = Timer.builder("checkin.command.duration")
                .description("Time from check-in open to trip start")
                .register(meterRegistry);
    }

    // ========== HOST WORKFLOW COMMANDS ==========

    /**
     * Command: Complete host check-in with odometer/fuel readings.
     * 
     * <p>Validates:
     * <ul>
     *   <li>User is the host of the booking</li>
     *   <li>Booking is in CHECK_IN_OPEN status</li>
     *   <li>All required photo types have been uploaded</li>
     * </ul>
     * 
     * <p>Publishes: {@link CheckInDomainEvent.HostCheckInCompleted}
     * 
     * @param dto Submission data with odometer, fuel, and location
     * @param userId Current user ID
     * @return Booking ID for confirmation
     * @throws AccessDeniedException if user is not the host
     * @throws IllegalStateException if booking is not in correct status
     */
    @Transactional
    public Long completeHostCheckIn(HostCheckInSubmissionDTO dto, Long userId) {
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

        // Update booking state
        booking.setStartOdometer(dto.getOdometerReading());
        booking.setStartFuelLevel(dto.getFuelLevelPercent());
        booking.setHostCheckInCompletedAt(Instant.now());
        booking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);

        // Set car location if provided
        if (dto.getCarLatitude() != null && dto.getCarLongitude() != null) {
            booking.setCarLatitude(BigDecimal.valueOf(dto.getCarLatitude()));
            booking.setCarLongitude(BigDecimal.valueOf(dto.getCarLongitude()));
        }

        // Set host location
        if (dto.getHostLatitude() != null && dto.getHostLongitude() != null) {
            booking.setHostCheckInLatitude(BigDecimal.valueOf(dto.getHostLatitude()));
            booking.setHostCheckInLongitude(BigDecimal.valueOf(dto.getHostLongitude()));
        }

        // Handle lockbox code with AES-256-GCM encryption
        if (dto.getLockboxCode() != null && !dto.getLockboxCode().isBlank()) {
            byte[] encryptedCode = lockboxEncryptionService.encrypt(dto.getLockboxCode());
            booking.setLockboxCodeEncrypted(encryptedCode);

            log.info("[CheckIn-Command] Lockbox code encrypted and stored for booking {}", booking.getId());

            eventService.recordEvent(
                    booking,
                    booking.getCheckInSessionId(),
                    CheckInEventType.HOST_LOCKBOX_SUBMITTED,
                    userId,
                    CheckInActorRole.HOST,
                    Map.of("codeLength", dto.getLockboxCode().length())
            );
        }

        // Record domain events
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

        // Publish domain event for read model sync
        eventPublisher.publishEvent(new CheckInDomainEvent.HostCheckInCompleted(
                booking.getId(),
                UUID.fromString(booking.getCheckInSessionId()),
                userId,
                Instant.now(),
                booking.getStartOdometer(),
                booking.getStartFuelLevel()
        ));

        hostCompletedCounter.increment();
        log.info("[CheckIn-Command] Host completed check-in for booking {}", booking.getId());

        // Notify guest
        notifyGuestCheckInReady(booking);

        return booking.getId();
    }

    // ========== GUEST WORKFLOW COMMANDS ==========

    /**
     * Command: Guest acknowledges vehicle condition.
     * 
     * <p>Validates:
     * <ul>
     *   <li>User is the guest of the booking</li>
     *   <li>Booking is in CHECK_IN_HOST_COMPLETE status</li>
     *   <li>Condition is accepted</li>
     * </ul>
     * 
     * <p>Publishes: {@link CheckInDomainEvent.GuestConditionAcknowledged}
     * 
     * @param dto Acknowledgment data with location and hotspots
     * @param userId Current user ID
     * @return Booking ID for confirmation
     * @throws AccessDeniedException if user is not the guest
     * @throws IllegalStateException if booking is not in correct status
     */
    @Transactional
    public Long acknowledgeCondition(GuestConditionAcknowledgmentDTO dto, Long userId) {
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

        // Update booking state
        booking.setGuestCheckInCompletedAt(Instant.now());
        booking.setStatus(BookingStatus.CHECK_IN_COMPLETE);

        // Set guest location
        if (dto.getGuestLatitude() != null && dto.getGuestLongitude() != null) {
            booking.setGuestCheckInLatitude(BigDecimal.valueOf(dto.getGuestLatitude()));
            booking.setGuestCheckInLongitude(BigDecimal.valueOf(dto.getGuestLongitude()));
        }

        // Record events
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

        // Publish domain event for read model sync
        eventPublisher.publishEvent(new CheckInDomainEvent.GuestConditionAcknowledged(
                booking.getId(),
                UUID.fromString(booking.getCheckInSessionId()),
                userId,
                Instant.now(),
                dto.getHotspots() != null ? dto.getHotspots().size() : 0
        ));

        guestCompletedCounter.increment();
        log.info("[CheckIn-Command] Guest acknowledged condition for booking {}", booking.getId());

        // Notify both parties that handshake is ready
        notifyHandshakeReady(booking);

        return booking.getId();
    }

    // ========== HANDSHAKE COMMANDS ==========

    /**
     * Command: Confirm handshake (both parties must confirm to start trip).
     * 
     * <p>Uses pessimistic locking to prevent race conditions on concurrent confirmations.
     * 
     * <p>Validates:
     * <ul>
     *   <li>User is participant of the booking</li>
     *   <li>Booking is in CHECK_IN_COMPLETE status (or IN_TRIP for idempotency)</li>
     *   <li>Geofence validation for remote handoff</li>
     * </ul>
     * 
     * <p>Publishes: {@link CheckInDomainEvent.TripStarted} when both parties confirm
     * 
     * @param dto Confirmation data with location
     * @param userId Current user ID
     * @return Result containing booking ID and trip started flag
     * @throws AccessDeniedException if user is not a participant
     * @throws IllegalStateException if booking is not in correct status
     * @throws GeofenceViolationException if geofence validation fails
     */
    @Transactional
    public HandshakeResult confirmHandshake(HandshakeConfirmationDTO dto, Long userId) {
        // Acquire pessimistic lock via repository (lock mode set in query)
        Booking booking = bookingRepository.findById(dto.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));

        // Idempotency check - if already in trip, return success
        if (booking.getStatus() == BookingStatus.IN_TRIP) {
            log.info("[CheckIn-Command] Handshake already completed for booking {} - idempotent return", dto.getBookingId());
            return new HandshakeResult(booking.getId(), true);
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
            processHostHandshake(booking, dto, userId);
        }

        // Process guest confirmation
        if (isGuest) {
            processGuestHandshake(booking, dto, userId);
        }

        // Check if both parties have confirmed
        boolean hostConfirmed = isHostHandshakeConfirmed(booking);
        boolean guestConfirmed = isGuestHandshakeConfirmed(booking);
        boolean tripStarted = false;

        if (hostConfirmed && guestConfirmed) {
            tripStarted = startTrip(booking, userId, isHost);
        }

        bookingRepository.save(booking);

        return new HandshakeResult(booking.getId(), tripStarted);
    }

    private void processHostHandshake(Booking booking, HandshakeConfirmationDTO dto, Long userId) {
        if (booking.getHandshakeCompletedAt() != null && isHostHandshakeConfirmed(booking)) {
            log.debug("[CheckIn-Command] Host already confirmed handshake for booking {}", booking.getId());
            return;
        }

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

    private void processGuestHandshake(Booking booking, HandshakeConfirmationDTO dto, Long userId) {
        // Geofence validation for remote handoff
        if (booking.getLockboxCodeEncrypted() != null && dto.getLatitude() != null) {
            validateGeofence(booking, dto, userId);
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

    private void validateGeofence(Booking booking, HandshakeConfirmationDTO dto, Long userId) {
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
        }

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

        booking.setGuestCheckInLatitude(BigDecimal.valueOf(dto.getLatitude()));
        booking.setGuestCheckInLongitude(BigDecimal.valueOf(dto.getLongitude()));
    }

    private boolean startTrip(Booking booking, Long userId, boolean isHost) {
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

        log.info("[CheckIn-Command] Trip started for booking {} - handshake complete", booking.getId());

        // Publish domain event for read model sync
        eventPublisher.publishEvent(new CheckInDomainEvent.TripStarted(
                booking.getId(),
                UUID.fromString(booking.getCheckInSessionId()),
                Instant.now(),
                booking.getLockboxCodeEncrypted() != null ? "REMOTE" : "IN_PERSON"
        ));

        // Notify both parties
        notifyTripStarted(booking);

        return true;
    }

    // ========== NO-SHOW COMMANDS ==========

    /**
     * Command: Process a no-show scenario.
     * 
     * <p>Called by scheduler when grace period expires without completion.
     * 
     * <p>Publishes: {@link CheckInDomainEvent.NoShowProcessed}
     * 
     * @param booking The booking entity
     * @param party "HOST" or "GUEST"
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

            notifyNoShow(booking, "GUEST");
        }

        bookingRepository.save(booking);

        // Publish domain event for read model sync
        eventPublisher.publishEvent(new CheckInDomainEvent.NoShowProcessed(
                booking.getId(),
                UUID.fromString(booking.getCheckInSessionId()),
                party,
                Instant.now()
        ));

        log.warn("[CheckIn-Command] No-show processed for booking {}, party: {}", booking.getId(), party);
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

    private void notifyGuestCheckInReady(Booking booking) {
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.CHECK_IN_HOST_COMPLETE)
                .message("Domaćin je završio prijem vozila. Potvrdite stanje vozila da biste započeli putovanje.")
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

    private boolean isHostHandshakeConfirmed(Booking booking) {
        return eventService.hasEventOfType(booking.getId(), CheckInEventType.HANDSHAKE_HOST_CONFIRMED);
    }

    private boolean isGuestHandshakeConfirmed(Booking booking) {
        return eventService.hasEventOfType(booking.getId(), CheckInEventType.HANDSHAKE_GUEST_CONFIRMED);
    }

    private String calculateMissedBy(Booking booking) {
        Instant now = Instant.now();
        Instant startInstant = booking.getStartTime().atZone(SERBIA_ZONE).toInstant();
        Duration missed = Duration.between(startInstant.plusSeconds(noShowGraceMinutes * 60L), now);
        return missed.toMinutes() + " minuta";
    }

    // ========== RESULT CLASSES ==========

    /**
     * Result of handshake confirmation command.
     */
    public record HandshakeResult(Long bookingId, boolean tripStarted) {}

    /**
     * Exception for geofence violations during handshake.
     */
    public static class GeofenceViolationException extends RuntimeException {
        public GeofenceViolationException(String message) {
            super(message);
        }
    }
}
