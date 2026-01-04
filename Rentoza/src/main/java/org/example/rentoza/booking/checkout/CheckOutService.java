package org.example.rentoza.booking.checkout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.*;
import org.example.rentoza.booking.checkin.dto.CheckInPhotoDTO;
import org.example.rentoza.booking.checkout.dto.*;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaOrchestrator;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core orchestrator for the checkout workflow.
 * 
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Checkout initiation (scheduler or manual)</li>
 *   <li>Guest checkout completion (photos, odometer, fuel)</li>
 *   <li>Host checkout confirmation (damage assessment)</li>
 *   <li>Late return detection and fee calculation</li>
 *   <li>Trip completion</li>
 * </ul>
 * 
 * <h2>State Flow</h2>
 * <pre>
 * IN_TRIP → CHECKOUT_OPEN → CHECKOUT_GUEST_COMPLETE → CHECKOUT_HOST_COMPLETE → COMPLETED
 * </pre>
 *
 * @see CheckInService for check-in workflow
 * @see CheckInEventService for audit trail
 */
@Service
@Slf4j
public class CheckOutService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    private static final int REQUIRED_CHECKOUT_PHOTO_TYPES = 6;

    private final BookingRepository bookingRepository;
    private final CheckInEventService eventService;
    private final CheckInPhotoRepository photoRepository;
    private final NotificationService notificationService;
    private final CheckoutSagaOrchestrator checkoutSagaOrchestrator;
    
    // Metrics
    private final Counter checkoutInitiatedCounter;
    private final Counter guestCompletedCounter;
    private final Counter hostConfirmedCounter;
    private final Counter damageReportedCounter;
    private final Counter sagaInvokedCounter;
    private final Counter sagaInvocationExceptionsCounter;
    private final Timer tripDurationTimer;

    @Value("${app.checkout.late.grace-minutes:15}")
    private int lateGraceMinutes;

    @Value("${app.checkout.late.fee-per-hour-rsd:500}")
    private int lateFeePerHourRsd;

    @Value("${app.checkout.late.max-hours:24}")
    private int maxLateHours;
    
    // ========== PHASE 4F: IMPROPER RETURN THRESHOLDS ==========
    
    @Value("${app.checkout.improper-return.fuel-difference-threshold:25}")
    private int fuelDifferenceThreshold;
    
    @Value("${app.checkout.improper-return.mileage-multiplier-threshold:2}")
    private double mileageMultiplierThreshold;

    public CheckOutService(
            BookingRepository bookingRepository,
            CheckInEventService eventService,
            CheckInPhotoRepository photoRepository,
            NotificationService notificationService,
            CheckoutSagaOrchestrator checkoutSagaOrchestrator,
            MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.eventService = eventService;
        this.photoRepository = photoRepository;
        this.notificationService = notificationService;
        this.checkoutSagaOrchestrator = checkoutSagaOrchestrator;
        
        this.checkoutInitiatedCounter = Counter.builder("checkout.initiated")
                .description("Checkout processes initiated")
                .register(meterRegistry);
        
        this.guestCompletedCounter = Counter.builder("checkout.guest.completed")
                .description("Guest checkout completions")
                .register(meterRegistry);
        
        this.hostConfirmedCounter = Counter.builder("checkout.host.confirmed")
                .description("Host checkout confirmations")
                .register(meterRegistry);
        
        this.damageReportedCounter = Counter.builder("checkout.damage.reported")
                .description("Damage reports at checkout")
                .register(meterRegistry);
        
        this.sagaInvokedCounter = Counter.builder("checkout.saga.invoked")
                .description("Times checkout saga was invoked from service")
                .register(meterRegistry);
        
        this.sagaInvocationExceptionsCounter = Counter.builder("checkout.saga.invocation.exceptions")
                .description("Exceptions thrown during saga invocation")
                .register(meterRegistry);
        
        this.tripDurationTimer = Timer.builder("trip.duration")
                .description("Total trip duration")
                .register(meterRegistry);
    }

    // ========== STATUS RETRIEVAL ==========

    /**
     * Get current checkout status for a booking.
     */
    @Transactional(readOnly = true)
    public CheckOutStatusDTO getCheckOutStatus(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        validateAccess(booking, userId);
        
        return mapToStatusDTO(booking, userId);
    }

    // ========== CHECKOUT INITIATION ==========

    /**
     * Initiate checkout process.
     * Can be called by scheduler at trip end, or manually for early return.
     * 
     * @param bookingId The booking to checkout
     * @param userId The user initiating checkout
     * @param isEarlyReturn True if guest is returning early
     * @return Updated checkout status
     */
    @Transactional
    public CheckOutStatusDTO initiateCheckout(Long bookingId, Long userId, boolean isEarlyReturn) {
        Booking booking = bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate access
        if (!isGuest(booking, userId) && !isHost(booking, userId)) {
            throw new AccessDeniedException("Nemate pristup ovoj rezervaciji");
        }
        
        // Validate status - must be IN_TRIP
        if (booking.getStatus() != BookingStatus.IN_TRIP) {
            throw new IllegalStateException("Checkout nije moguć. Trenutni status: " + booking.getStatus());
        }
        
        // Generate checkout session ID
        booking.setCheckoutSessionId(UUID.randomUUID().toString());
        booking.setCheckoutOpenedAt(Instant.now());
        booking.setStatus(BookingStatus.CHECKOUT_OPEN);
        
        // Set scheduled return time from exact endTime
        LocalDateTime scheduledReturn = booking.getEndTime();
        booking.setScheduledReturnTime(scheduledReturn.atZone(SERBIA_ZONE).toInstant());
        
        // Record event
        eventService.recordEvent(
            booking,
            booking.getCheckoutSessionId(),
            CheckInEventType.CHECKOUT_INITIATED,
            userId,
            isHost(booking, userId) ? CheckInActorRole.HOST : CheckInActorRole.GUEST,
            Map.of(
                "initiatedBy", isHost(booking, userId) ? "HOST" : "GUEST",
                "reason", isEarlyReturn ? "EARLY_RETURN" : "TRIP_END",
                "scheduledReturnTime", scheduledReturn.toString()
            )
        );
        
        if (isEarlyReturn) {
            eventService.recordEvent(
                booking,
                booking.getCheckoutSessionId(),
                CheckInEventType.EARLY_RETURN_INITIATED,
                userId,
                CheckInActorRole.GUEST,
                Map.of(
                    "scheduledEndDate", booking.getEndDate().toString(),
                    "actualReturnDate", LocalDate.now(SERBIA_ZONE).toString()
                )
            );
        }
        
        bookingRepository.save(booking);
        
        checkoutInitiatedCounter.increment();
        log.info("[CheckOut] Checkout initiated for booking {}, earlyReturn={}", bookingId, isEarlyReturn);
        
        // Notify other party
        if (isGuest(booking, userId)) {
            notifyHostCheckoutStarted(booking);
        } else {
            notifyGuestCheckoutStarted(booking);
        }
        
        return mapToStatusDTO(booking, userId);
    }

    /**
     * Initiate checkout by scheduler (automated at trip end).
     */
    @Transactional
    public void initiateCheckoutByScheduler(Booking booking) {
        if (booking.getStatus() != BookingStatus.IN_TRIP) {
            log.debug("[CheckOut] Skipping checkout initiation for booking {} - not IN_TRIP", booking.getId());
            return;
        }
        
        booking.setCheckoutSessionId(UUID.randomUUID().toString());
        booking.setCheckoutOpenedAt(Instant.now());
        booking.setStatus(BookingStatus.CHECKOUT_OPEN);
        
        // Set scheduled return time from exact endTime
        LocalDateTime scheduledReturn = booking.getEndTime();
        booking.setScheduledReturnTime(scheduledReturn.atZone(SERBIA_ZONE).toInstant());
        
        eventService.recordSystemEvent(
            booking,
            booking.getCheckoutSessionId(),
            CheckInEventType.CHECKOUT_INITIATED,
            Map.of(
                "initiatedBy", "SCHEDULER",
                "reason", "TRIP_END",
                "scheduledReturnTime", scheduledReturn.toString()
            )
        );
        
        bookingRepository.save(booking);
        
        checkoutInitiatedCounter.increment();
        log.info("[CheckOut] Checkout initiated by scheduler for booking {}", booking.getId());
        
        // Notify both parties
        notifyGuestCheckoutStarted(booking);
        notifyHostCheckoutStarted(booking);
    }

    // ========== GUEST WORKFLOW ==========

    /**
     * Complete guest checkout with end readings.
     * 
     * @param dto Submission data with end odometer/fuel
     * @param userId Current user ID
     * @return Updated checkout status
     */
    @Transactional
    public CheckOutStatusDTO completeGuestCheckout(GuestCheckOutSubmissionDTO dto, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(dto.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate guest access
        if (!isGuest(booking, userId)) {
            throw new AccessDeniedException("Samo gost može završiti checkout");
        }
        
        // Validate status
        if (booking.getStatus() != BookingStatus.CHECKOUT_OPEN) {
            throw new IllegalStateException("Checkout nije otvoren. Trenutni status: " + booking.getStatus());
        }
        
        // Validate photos
        long validPhotoTypes = photoRepository.countCheckoutPhotoTypes(booking.getId());
        if (validPhotoTypes < REQUIRED_CHECKOUT_PHOTO_TYPES) {
            throw new IllegalStateException(String.format(
                "Potrebno je minimum %d tipova fotografija. Pronađeno: %d", 
                REQUIRED_CHECKOUT_PHOTO_TYPES, validPhotoTypes));
        }
        
        // Validate odometer (must be >= start)
        if (booking.getStartOdometer() != null && dto.getEndOdometerReading() < booking.getStartOdometer()) {
            throw new IllegalArgumentException(String.format(
                "Završna kilometraža (%d) ne može biti manja od početne (%d)",
                dto.getEndOdometerReading(), booking.getStartOdometer()));
        }
        
        // Update booking
        booking.setEndOdometer(dto.getEndOdometerReading());
        booking.setEndFuelLevel(dto.getEndFuelLevelPercent());
        booking.setGuestCheckoutCompletedAt(Instant.now());
        booking.setActualReturnTime(Instant.now());
        booking.setStatus(BookingStatus.CHECKOUT_GUEST_COMPLETE);
        
        // Set guest location
        if (dto.getGuestLatitude() != null && dto.getGuestLongitude() != null) {
            booking.setGuestCheckoutLatitude(BigDecimal.valueOf(dto.getGuestLatitude()));
            booking.setGuestCheckoutLongitude(BigDecimal.valueOf(dto.getGuestLongitude()));
        }
        
        // Check for late return
        checkAndRecordLateReturn(booking, userId);
        
        // ================================================================
        // PHASE 4F: IMPROPER RETURN DETECTION
        // ================================================================
        // Check for conditions that indicate improper vehicle return:
        // - Significant fuel difference (>25% by default)
        // - Excessive mileage (>2x estimated by default)
        checkAndFlagImproperReturn(booking, dto, userId);
        
        // Calculate total mileage
        Integer totalMileage = null;
        if (booking.getStartOdometer() != null) {
            totalMileage = dto.getEndOdometerReading() - booking.getStartOdometer();
        }
        
        // Record events
        eventService.recordEvent(
            booking,
            booking.getCheckoutSessionId(),
            CheckInEventType.CHECKOUT_GUEST_ODOMETER_SUBMITTED,
            userId,
            CheckInActorRole.GUEST,
            Map.of(
                "reading", dto.getEndOdometerReading(),
                "totalMileage", totalMileage != null ? totalMileage : "N/A"
            )
        );
        
        eventService.recordEvent(
            booking,
            booking.getCheckoutSessionId(),
            CheckInEventType.CHECKOUT_GUEST_FUEL_SUBMITTED,
            userId,
            CheckInActorRole.GUEST,
            Map.of(
                "levelPercent", dto.getEndFuelLevelPercent(),
                "startLevel", booking.getStartFuelLevel() != null ? booking.getStartFuelLevel() : "N/A",
                "difference", booking.getStartFuelLevel() != null 
                    ? dto.getEndFuelLevelPercent() - booking.getStartFuelLevel() : "N/A"
            )
        );
        
        eventService.recordEvent(
            booking,
            booking.getCheckoutSessionId(),
            CheckInEventType.CHECKOUT_GUEST_SECTION_COMPLETE,
            userId,
            CheckInActorRole.GUEST,
            Map.of(
                "photoCount", validPhotoTypes,
                "odometerSubmitted", true,
                "fuelSubmitted", true,
                "comment", dto.getConditionComment() != null ? dto.getConditionComment() : ""
            )
        );
        
        bookingRepository.save(booking);
        
        guestCompletedCounter.increment();
        log.info("[CheckOut] Guest completed checkout for booking {}", booking.getId());
        
        // Notify host
        notifyHostGuestCompleted(booking);
        
        return mapToStatusDTO(booking, userId);
    }

    // ========== HOST WORKFLOW ==========

    /**
     * Host confirms vehicle return and condition.
     * 
     * @param dto Confirmation data with damage assessment
     * @param userId Current user ID
     * @return Updated checkout status
     */
    @Transactional
    public CheckOutStatusDTO confirmHostCheckout(HostCheckOutConfirmationDTO dto, Long userId) {
        Booking booking = bookingRepository.findByIdWithRelations(dto.getBookingId())
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Validate host access
        if (!isHost(booking, userId)) {
            throw new AccessDeniedException("Samo vlasnik može potvrditi checkout");
        }
        
        // Validate status
        if (booking.getStatus() != BookingStatus.CHECKOUT_GUEST_COMPLETE) {
            throw new IllegalStateException("Gost još nije završio checkout. Trenutni status: " + booking.getStatus());
        }
        
        // Update booking
        booking.setHostCheckoutCompletedAt(Instant.now());
        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);
        
        // Set host location
        if (dto.getHostLatitude() != null && dto.getHostLongitude() != null) {
            booking.setHostCheckoutLatitude(BigDecimal.valueOf(dto.getHostLatitude()));
            booking.setHostCheckoutLongitude(BigDecimal.valueOf(dto.getHostLongitude()));
        }
        
        // Handle damage report
        if (dto.getNewDamageReported() != null && dto.getNewDamageReported()) {
            booking.setNewDamageReported(true);
            booking.setDamageAssessmentNotes(dto.getDamageDescription());
            booking.setDamageClaimAmount(dto.getEstimatedDamageCostRsd());
            booking.setDamageClaimStatus("PENDING");
            
            eventService.recordEvent(
                booking,
                booking.getCheckoutSessionId(),
                CheckInEventType.CHECKOUT_HOST_DAMAGE_REPORTED,
                userId,
                CheckInActorRole.HOST,
                Map.of(
                    "damageDescription", dto.getDamageDescription() != null ? dto.getDamageDescription() : "",
                    "estimatedCostRsd", dto.getEstimatedDamageCostRsd() != null ? dto.getEstimatedDamageCostRsd() : 0,
                    "photoIds", dto.getDamagePhotoIds() != null ? dto.getDamagePhotoIds() : List.of()
                )
            );
            
            damageReportedCounter.increment();
            log.info("[CheckOut] Damage reported for booking {}: {}", booking.getId(), dto.getDamageDescription());
        }
        
        // Record confirmation event
        eventService.recordEvent(
            booking,
            booking.getCheckoutSessionId(),
            CheckInEventType.CHECKOUT_HOST_CONFIRMED,
            userId,
            CheckInActorRole.HOST,
            Map.of(
                "conditionAccepted", dto.getConditionAccepted(),
                "newDamageReported", dto.getNewDamageReported() != null && dto.getNewDamageReported(),
                "notes", dto.getNotes() != null ? dto.getNotes() : ""
            )
        );
        
        bookingRepository.save(booking);
        
        hostConfirmedCounter.increment();
        log.info("[CheckOut] Host confirmed checkout for booking {}", booking.getId());
        
        // Complete checkout if no dispute
        // Handle null-safe check for Boolean wrapper type
        if (booking.getNewDamageReported() == null || !booking.getNewDamageReported() || dto.getConditionAccepted()) {
            completeCheckout(booking, userId);
        } else {
            // Notify guest about damage claim
            notifyGuestDamageReported(booking);
        }
        
        return mapToStatusDTO(booking, userId);
    }

    // ========== CHECKOUT COMPLETION ==========

    /**
     * Complete the checkout process and mark trip as COMPLETED.
     * Delegates charge calculation to CheckoutSagaOrchestrator (single source of truth).
     * 
     * <p>Enterprise Pattern: Saga-First Architecture</p>
     * <ul>
     *   <li>Service handles state transition only</li>
     *   <li>Saga calculates all charges (late fees, mileage, fuel)</li>
     *   <li>Saga manages payment capture/release</li>
     *   <li>Idempotent saga design handles retry scenarios</li>
     * </ul>
     * 
     * <p><strong>Isolation Level:</strong> READ_COMMITTED</p>
     * <p>Prevents dirty reads while allowing saga to read committed booking state.
     * Saga uses optimistic locking (@Version) to detect concurrent modifications.</p>
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void completeCheckout(Booking booking, Long userId) {
        booking.setCheckoutCompletedAt(Instant.now());
        booking.setTripEndedAt(Instant.now());
        booking.setStatus(BookingStatus.COMPLETED);
        
        // Calculate trip duration for metrics
        if (booking.getTripStartedAt() != null) {
            Duration tripDuration = Duration.between(booking.getTripStartedAt(), Instant.now());
            tripDurationTimer.record(tripDuration);
        }
        
        // Calculate total mileage
        Integer totalMileage = booking.getTotalMileage();
        
        eventService.recordEvent(
            booking,
            booking.getCheckoutSessionId(),
            CheckInEventType.CHECKOUT_COMPLETE,
            userId,
            userId != null && isHost(booking, userId) ? CheckInActorRole.HOST : CheckInActorRole.GUEST,
            Map.of(
                "endOdometer", booking.getEndOdometer() != null ? booking.getEndOdometer() : "N/A",
                "endFuelLevel", booking.getEndFuelLevel() != null ? booking.getEndFuelLevel() : "N/A",
                "totalMileage", totalMileage != null ? totalMileage : "N/A",
                "newDamageReported", booking.getNewDamageReported() != null && booking.getNewDamageReported(),
                "chargeCalculationDelegatedToSaga", true
            )
        );
        
        bookingRepository.save(booking);
        
        log.info("[CheckOut] Checkout completed for booking {} - delegating charge calculation to saga", 
            booking.getId());
        
        // Invoke saga for charge calculation and payment processing
        // Saga is the single source of truth for all checkout charges
        try {
            checkoutSagaOrchestrator.startSaga(booking.getId());
            sagaInvokedCounter.increment();
            log.info("[CheckOut] Saga invoked successfully for booking {}", booking.getId());
        } catch (Exception e) {
            sagaInvocationExceptionsCounter.increment();
            log.error("[CheckOut] Saga invocation failed for booking {}: {}. Recovery scheduler will retry.", 
                booking.getId(), e.getMessage(), e);
            // Don't fail checkout - saga recovery scheduler will retry
            // This ensures user experience is not blocked by saga failures
        }
        
        // Notify both parties
        notifyCheckoutComplete(booking);
    }

    // ========== LATE RETURN HANDLING ==========

    /**
     * Record late/early return timing for audit purposes.
     * 
     * <p><strong>IMPORTANT:</strong> This method NO LONGER calculates late fees.</p>
     * <p>Fee calculation is delegated to CheckoutSagaOrchestrator to maintain
     * single source of truth and enable saga compensation patterns.</p>
     * 
     * @see CheckoutSagaOrchestrator#executeCalculateCharges for fee calculation
     */
    private void checkAndRecordLateReturn(Booking booking, Long userId) {
        if (booking.getScheduledReturnTime() == null) {
            return;
        }
        
        Instant now = Instant.now();
        Instant scheduledReturn = booking.getScheduledReturnTime();
        Instant graceEnd = scheduledReturn.plus(lateGraceMinutes, ChronoUnit.MINUTES);
        
        if (now.isAfter(graceEnd)) {
            long lateMinutes = ChronoUnit.MINUTES.between(scheduledReturn, now);
            booking.setLateReturnMinutes((int) lateMinutes);
            
            // Record event for audit trail (fee calculation happens in saga)
            eventService.recordEvent(
                booking,
                booking.getCheckoutSessionId(),
                CheckInEventType.LATE_RETURN_DETECTED,
                userId,
                CheckInActorRole.GUEST,
                Map.of(
                    "scheduledReturnTime", scheduledReturn.toString(),
                    "actualReturnTime", now.toString(),
                    "lateMinutes", lateMinutes,
                    "feeCalculationNote", "Fee will be calculated by saga"
                )
            );
            
            log.info("[CheckOut] Late return detected for booking {}: {} minutes late (fee calculation delegated to saga)",
                booking.getId(), lateMinutes);
        } else if (now.isBefore(scheduledReturn)) {
            // Early return
            long earlyMinutes = ChronoUnit.MINUTES.between(now, scheduledReturn);
            booking.setLateReturnMinutes((int) -earlyMinutes); // Negative for early
        }
    }

    // ========== SCHEDULER SUPPORT ==========

    /**
     * Find bookings that need checkout window opened (trip end reached).
     */
    @Transactional(readOnly = true)
    public List<Booking> findBookingsForCheckoutOpening(LocalDate endDate) {
        return bookingRepository.findAll().stream()
                .filter(b -> b.getStatus() == BookingStatus.IN_TRIP)
                .filter(b -> b.getCheckoutSessionId() == null)
                .filter(b -> !b.getEndDate().isAfter(endDate))
                .collect(Collectors.toList());
    }

    // ========== NOTIFICATION METHODS ==========

    private void notifyGuestCheckoutStarted(Booking booking) {
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.CHECKOUT_WINDOW_OPENED)
                .message("Checkout je otvoren. Molimo vratite vozilo i uploadujte fotografije.")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
    }

    private void notifyHostCheckoutStarted(Booking booking) {
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getCar().getOwner().getId())
                .type(NotificationType.CHECKOUT_WINDOW_OPENED)
                .message("Checkout je otvoren. Gost vraća vozilo.")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
    }

    private void notifyHostGuestCompleted(Booking booking) {
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getCar().getOwner().getId())
                .type(NotificationType.CHECKOUT_GUEST_COMPLETE)
                .message("Gost je završio checkout. Molimo potvrdite stanje vozila.")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
    }

    private void notifyGuestDamageReported(Booking booking) {
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.CHECKOUT_DAMAGE_REPORTED)
                .message("Domaćin je prijavio oštećenje na vozilu. Pogledajte detalje.")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
    }

    private void notifyCheckoutComplete(Booking booking) {
        // Notify guest
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getRenter().getId())
                .type(NotificationType.CHECKOUT_COMPLETE)
                .message("Checkout je završen. Hvala na korišćenju Rentoza!")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
        
        // Notify host
        notificationService.createNotification(CreateNotificationRequestDTO.builder()
                .recipientId(booking.getCar().getOwner().getId())
                .type(NotificationType.CHECKOUT_COMPLETE)
                .message("Checkout je završen. Vozilo je vraćeno.")
                .relatedEntityId(String.valueOf(booking.getId()))
                .build());
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

    private CheckOutStatusDTO mapToStatusDTO(Booking booking, Long userId) {
        boolean isHost = isHost(booking, userId);
        boolean isGuest = isGuest(booking, userId);
        
        // Get check-in photos for comparison
        List<CheckInPhotoDTO> checkInPhotos = photoRepository.findByBookingId(booking.getId()).stream()
                .filter(p -> !p.isDeleted())
                .filter(p -> p.getPhotoType().isHostPhoto() || p.getPhotoType().isGuestPhoto())
                .map(this::mapToPhotoDTO)
                .collect(Collectors.toList());
        
        // Get checkout photos
        List<CheckInPhotoDTO> checkoutPhotos = photoRepository.findByBookingId(booking.getId()).stream()
                .filter(p -> !p.isDeleted())
                .filter(p -> p.getPhotoType().isCheckoutPhoto() && !p.getPhotoType().isHostCheckoutPhoto())
                .map(this::mapToPhotoDTO)
                .collect(Collectors.toList());
        
        // Get host checkout photos
        List<CheckInPhotoDTO> hostCheckoutPhotos = photoRepository.findByBookingId(booking.getId()).stream()
                .filter(p -> !p.isDeleted())
                .filter(p -> p.getPhotoType().isHostCheckoutPhoto())
                .map(this::mapToPhotoDTO)
                .collect(Collectors.toList());
        
        // Calculate mileage and fuel difference
        Integer totalMileage = booking.getTotalMileage();
        Integer fuelDifference = null;
        if (booking.getStartFuelLevel() != null && booking.getEndFuelLevel() != null) {
            fuelDifference = booking.getEndFuelLevel() - booking.getStartFuelLevel();
        }
        
        return CheckOutStatusDTO.builder()
                .bookingId(booking.getId())
                .checkoutSessionId(booking.getCheckoutSessionId())
                .status(booking.getStatus())
                .checkoutWindowOpen(booking.getStatus() == BookingStatus.CHECKOUT_OPEN
                        || booking.getStatus() == BookingStatus.CHECKOUT_GUEST_COMPLETE
                        || booking.getStatus() == BookingStatus.CHECKOUT_HOST_COMPLETE)
                .guestCheckOutComplete(booking.getGuestCheckoutCompletedAt() != null)
                .hostCheckOutComplete(booking.getHostCheckoutCompletedAt() != null)
                .checkoutComplete(booking.getStatus() == BookingStatus.COMPLETED)
                .checkoutOpenedAt(toLocalDateTime(booking.getCheckoutOpenedAt()))
                .guestCompletedAt(toLocalDateTime(booking.getGuestCheckoutCompletedAt()))
                .hostCompletedAt(toLocalDateTime(booking.getHostCheckoutCompletedAt()))
                .checkoutCompletedAt(toLocalDateTime(booking.getCheckoutCompletedAt()))
                .tripStartedAt(toLocalDateTime(booking.getTripStartedAt()))
                .scheduledReturnTime(toLocalDateTime(booking.getScheduledReturnTime()))
                .actualReturnTime(toLocalDateTime(booking.getActualReturnTime()))
                .lateReturnMinutes(booking.getLateReturnMinutes())
                .lateFeeAmount(booking.getLateFeeAmount())
                .startOdometer(booking.getStartOdometer())
                .endOdometer(booking.getEndOdometer())
                .totalMileage(totalMileage)
                .startFuelLevel(booking.getStartFuelLevel())
                .endFuelLevel(booking.getEndFuelLevel())
                .fuelDifference(fuelDifference)
                .checkInPhotos(checkInPhotos)
                .checkoutPhotos(checkoutPhotos)
                .hostCheckoutPhotos(hostCheckoutPhotos)
                .newDamageReported(booking.getNewDamageReported() != null && booking.getNewDamageReported())
                .damageDescription(booking.getDamageAssessmentNotes())
                .damageClaimAmount(booking.getDamageClaimAmount())
                .damageClaimStatus(booking.getDamageClaimStatus())
                .isHost(isHost)
                .isGuest(isGuest)
                .car(CheckOutStatusDTO.CarSummaryDTO.builder()
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

    // ========== PHASE 4F: IMPROPER RETURN DETECTION ==========

    /**
     * Phase 4F: Check for and flag improper vehicle return conditions.
     * 
     * <p>Conditions that trigger improper return flag:
     * <ul>
     *   <li><b>LOW_FUEL:</b> Fuel level significantly lower than start (>25% difference)</li>
     *   <li><b>EXCESSIVE_MILEAGE:</b> Miles exceeded estimated by >2x</li>
     * </ul>
     * 
     * <p>Additional conditions detected by host during confirmation:
     * <ul>
     *   <li>CLEANING_REQUIRED: Professional cleaning needed</li>
     *   <li>SMOKING_DETECTED: Evidence of smoking in vehicle</li>
     *   <li>WRONG_LOCATION: Returned to different location</li>
     * </ul>
     * 
     * @param booking The booking being checked out
     * @param dto Guest checkout submission data
     * @param userId The guest user ID
     */
    private void checkAndFlagImproperReturn(Booking booking, GuestCheckOutSubmissionDTO dto, Long userId) {
        // Check fuel difference
        if (booking.getStartFuelLevel() != null) {
            int fuelDiff = booking.getStartFuelLevel() - dto.getEndFuelLevelPercent();
            if (fuelDiff > fuelDifferenceThreshold) {
                flagImproperReturn(
                    booking, 
                    "LOW_FUEL", 
                    String.format("Nivo goriva smanjen za %d%% (početni: %d%%, završni: %d%%)",
                            fuelDiff, booking.getStartFuelLevel(), dto.getEndFuelLevelPercent()),
                    userId
                );
                return; // Only flag one condition at a time
            }
        }
        
        // Check excessive mileage
        if (booking.getStartOdometer() != null) {
            int actualMileage = dto.getEndOdometerReading() - booking.getStartOdometer();
            
            // Calculate expected mileage based on trip duration and default daily limit
            int estimatedMileage = calculateEstimatedMileage(booking);
            
            if (estimatedMileage > 0 && actualMileage > (estimatedMileage * mileageMultiplierThreshold)) {
                flagImproperReturn(
                    booking,
                    "EXCESSIVE_MILEAGE",
                    String.format("Prekoračena kilometraža: %d km (očekivano: ~%d km, faktor: %.1fx)",
                            actualMileage, estimatedMileage, (double) actualMileage / estimatedMileage),
                    userId
                );
            }
        }
    }
    
    /**
     * Flag a booking as having improper return condition.
     * 
     * @param booking The booking to flag
     * @param code Improper return code (LOW_FUEL, EXCESSIVE_MILEAGE, etc.)
     * @param notes Detailed description
     * @param userId User who triggered the detection (guest or system)
     */
    private void flagImproperReturn(Booking booking, String code, String notes, Long userId) {
        booking.setImproperReturnFlag(true);
        booking.setImproperReturnCode(code);
        booking.setImproperReturnNotes(notes);
        
        eventService.recordEvent(
            booking,
            booking.getCheckoutSessionId(),
            CheckInEventType.IMPROPER_RETURN_FLAGGED,
            userId,
            userId != null && isGuest(booking, userId) ? CheckInActorRole.GUEST : CheckInActorRole.SYSTEM,
            Map.of(
                "code", code,
                "notes", notes,
                "detectedAt", Instant.now().toString(),
                "autoDetected", true
            )
        );
        
        log.warn("[Phase4F] IMPROPER RETURN DETECTED: booking={}, code={}, notes={}",
                booking.getId(), code, notes);
    }
    
    /**
     * Calculate estimated mileage based on trip duration.
     * Uses default 200km/day limit if not configured on car.
     */
    private int calculateEstimatedMileage(Booking booking) {
        if (booking.getStartTime() == null || booking.getEndTime() == null) {
            return 0;
        }
        
        long days = ChronoUnit.DAYS.between(
                booking.getStartTime().toLocalDate(),
                booking.getEndTime().toLocalDate()) + 1;
        
        // TODO: Get daily limit from car or booking configuration
        int dailyLimit = 200; // Default 200km per day
        
        return (int) (days * dailyLimit);
    }
}
