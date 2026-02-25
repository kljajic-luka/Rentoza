package org.example.rentoza.booking;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.cancellation.CancellationPolicyService;
import org.example.rentoza.booking.cancellation.CancellationReason;
import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.booking.dto.CancellationPreviewDTO;
import org.example.rentoza.booking.dto.CancellationRequestDTO;
import org.example.rentoza.booking.dto.CancellationResultDTO;
import org.example.rentoza.booking.dto.UserBookingResponseDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.chat.ChatServiceClient;
import org.example.rentoza.common.GeoPoint;
import org.example.rentoza.delivery.DeliveryFeeCalculator;
import org.example.rentoza.delivery.DeliveryFeeCalculator.DeliveryFeeResult;
import org.example.rentoza.exception.BookingConflictException;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.exception.UserOverlapException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.review.Review;
import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.dto.BookingEligibilityDTO;
import org.hibernate.Hibernate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BookingService {

    // ── R1-FIX: Result wrapper for booking creation ─────────────────────────
    // Carries the created booking plus optional 3DS redirect data so the controller
    // can return redirect info to the frontend without an exception/rollback.
    public record BookingCreationResult(
            Booking booking,
            boolean redirectRequired,
            String redirectUrl
    ) {
        /** Convenience factory for the normal (non-redirect) path. */
        public static BookingCreationResult success(Booking booking) {
            return new BookingCreationResult(booking, false, null);
        }

        /** Factory for the 3DS/SCA redirect path. */
        public static BookingCreationResult redirect(Booking booking, String redirectUrl) {
            return new BookingCreationResult(booking, true, redirectUrl);
        }
    }

    private final BookingRepository repo;
    private final CarRepository carRepo;
    private final UserRepository userRepo;
    private final ReviewRepository reviewRepo;
    private final ChatServiceClient chatServiceClient;
    private final NotificationService notificationService;
    private final org.example.rentoza.security.CurrentUser currentUser;
    private final CancellationPolicyService cancellationPolicyService;
    private final DeliveryFeeCalculator deliveryFeeCalculator;
    private final RenterVerificationService renterVerificationService;
    private final org.example.rentoza.payment.BookingPaymentService bookingPaymentService;

    @org.springframework.beans.factory.annotation.Value("${app.booking.host-approval.enabled:false}")
    private boolean approvalEnabled;

    @org.springframework.beans.factory.annotation.Value("${app.booking.host-approval.beta-users:}")
    private java.util.List<Long> betaUsers;

    @org.springframework.beans.factory.annotation.Value("${app.booking.host-approval.approval-sla-hours:${app.booking.host-approval.expiry-hours:24}}")
    private int approvalSlaHours;

    @org.springframework.beans.factory.annotation.Value("${app.booking.host-approval.min-guest-preparation-hours:12}")
    private int minGuestPreparationHours;
    
    @org.springframework.beans.factory.annotation.Value("${app.renter-verification.license-required:true}")
    private boolean licenseRequired;

    @org.springframework.beans.factory.annotation.Value("${app.payment.deposit.amount-rsd:30000}")
    private int defaultDepositAmountRsd;

    @org.springframework.beans.factory.annotation.Value("${app.payment.service-fee-rate:0.15}")
    private double serviceFeeRate;

    public BookingService(BookingRepository repo, CarRepository carRepo, UserRepository userRepo,
                          ReviewRepository reviewRepo, ChatServiceClient chatServiceClient,
                          NotificationService notificationService, org.example.rentoza.security.CurrentUser currentUser,
                          CancellationPolicyService cancellationPolicyService,
                          DeliveryFeeCalculator deliveryFeeCalculator,
                          RenterVerificationService renterVerificationService,
                          org.example.rentoza.payment.BookingPaymentService bookingPaymentService) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.userRepo = userRepo;
        this.reviewRepo = reviewRepo;
        this.chatServiceClient = chatServiceClient;
        this.notificationService = notificationService;
        this.currentUser = currentUser;
        this.cancellationPolicyService = cancellationPolicyService;
        this.deliveryFeeCalculator = deliveryFeeCalculator;
        this.renterVerificationService = renterVerificationService;
        this.bookingPaymentService = bookingPaymentService;
    }

    @Transactional
    @CacheEvict(value = "bookingAvailability", key = "'slots-' + #dto.carId")
    public BookingCreationResult createBooking(BookingRequestDTO dto, String renterEmail) {

        // ========================================================================
        // P1 FIX: IDEMPOTENCY CHECK
        // ========================================================================
        // If client provides an idempotency key, check for existing booking first.
        // This prevents duplicate bookings (and duplicate payment holds) on retry.
        // ========================================================================
        if (dto.getIdempotencyKey() != null && !dto.getIdempotencyKey().isBlank()) {
            java.util.Optional<Booking> existing = repo.findByIdempotencyKeyWithRelations(dto.getIdempotencyKey());
            if (existing.isPresent()) {
                Booking b = existing.get();
                log.info("Idempotent replay: returning existing booking {} for key {}",
                        b.getId(), dto.getIdempotencyKey());
                // For SCA bookings still waiting for 3DS completion, reconstruct the redirect envelope
                // so the client gets a consistent response shape on retry.
                if ("REDIRECT_REQUIRED".equals(b.getPaymentStatus())) {
                    java.util.Optional<String> redirectUrl = bookingPaymentService.findPendingRedirectUrl(b.getId());
                    if (redirectUrl.isPresent()) {
                        return BookingCreationResult.redirect(b, redirectUrl.get());
                    }
                }
                return BookingCreationResult.success(b);
            }
        }

        User renter = userRepo.findByEmail(renterEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Validate age requirement
        if (renter.getAge() == null || renter.getAge() < 21) {
            throw new org.example.rentoza.exception.ValidationException("Drivers must be at least 21 years old to rent a car.");
        }

        Car car = carRepo.findById(dto.getCarId())
                .orElseThrow(() -> new ResourceNotFoundException("Car not found"));

        // ========================================================================
        // P0 FIX: APPROVAL & AVAILABILITY GATE
        // ========================================================================
        // Prevent booking of unapproved or unavailable cars.
        // Unapproved listings must not be bookable even if accessed by direct ID.
        // ========================================================================
        if (car.getApprovalStatus() != org.example.rentoza.car.ApprovalStatus.APPROVED) {
            throw new org.example.rentoza.exception.ValidationException("This car listing is not yet approved and cannot be booked.");
        }
        if (!car.isAvailable()) {
            throw new org.example.rentoza.exception.ValidationException("This car is currently unavailable for booking.");
        }

        // ========================================================================
        // CONCURRENCY HARDENING: Pessimistic Lock Before Booking Creation
        // ========================================================================
        // CRITICAL: This MUST happen BEFORE creating the Booking entity.
        // Uses SELECT ... FOR UPDATE to acquire exclusive row lock on conflicting bookings.
        // Prevents race condition where two users could book same times simultaneously.
        //
        // Lock Scope: Only rows matching (car_id, time range, active/pending status)
        // Lock Duration: Until transaction commits/rolls back
        // Timeout: 5 seconds (configured in repository query hint)
        boolean hasConflict = repo.existsOverlappingBookingsWithLock(
                dto.getCarId(),
                dto.getStartTime(),
                dto.getEndTime()
        );
        
        if (hasConflict) {
            // Before surfacing a 409, check whether the conflict is caused by a concurrent
            // duplicate submit carrying the same idempotency key. This happens when two
            // requests race past the idempotency early-check (both see no existing row),
            // request-A commits first, and request-B then sees request-A's booking as an
            // overlap. In that case request-B should return canonically, not 409.
            if (dto.getIdempotencyKey() != null && !dto.getIdempotencyKey().isBlank()) {
                java.util.Optional<Booking> raceWinner =
                        repo.findByIdempotencyKeyWithRelations(dto.getIdempotencyKey());
                if (raceWinner.isPresent()) {
                    Booking b = raceWinner.get();
                    log.info("[Idempotency] Conflict-path race resolution: returning existing booking {} for key {}",
                            b.getId(), dto.getIdempotencyKey());
                    if ("REDIRECT_REQUIRED".equals(b.getPaymentStatus())) {
                        java.util.Optional<String> redirectUrl =
                                bookingPaymentService.findPendingRedirectUrl(b.getId());
                        if (redirectUrl.isPresent()) {
                            return BookingCreationResult.redirect(b, redirectUrl.get());
                        }
                    }
                    return BookingCreationResult.success(b);
                }
            }
            log.warn("Booking conflict detected: carId={}, times={} to {}",
                    dto.getCarId(), dto.getStartTime(), dto.getEndTime());
            throw new BookingConflictException(
                    "This car is already booked for the selected times. Please choose different times."
            );
        }

        // ========================================================================
        // RENTER AVAILABILITY CHECK: "One Driver, One Car" Constraint
        // ========================================================================
        // A user cannot physically drive two cars simultaneously.
        // Check if the renter already has an active or pending booking for these times.
        // 
        // Blocking statuses: PENDING_APPROVAL, ACTIVE
        // Non-blocking: CANCELLED, DECLINED, COMPLETED, EXPIRED, EXPIRED_SYSTEM
        //
        // This is the "soft guardrail" - provides user-friendly error message.
        // The database trigger (V18) is the "hard guardrail" for race conditions.
        boolean hasUserOverlap = repo.existsOverlappingUserBooking(
                renter.getId(),
                dto.getStartTime(),
                dto.getEndTime()
        );
        
        if (hasUserOverlap) {
            log.warn("User overlap conflict: userId={}, times={} to {}. User already has an active/pending booking.", 
                    renter.getId(), dto.getStartTime(), dto.getEndTime());
            throw new UserOverlapException(
                    "Ne možete rezervisati dva vozila u isto vreme. " +
                    "Već imate aktivnu ili čekajuću rezervaciju za period " + 
                    dto.getStartTime() + " do " + dto.getEndTime() + "."
            );
        }

        // ========================================================================
        // TRIP START BUFFER VALIDATION (Short Notice Protection)
        // ========================================================================
        // Reject booking if trip starts within required advance notice period.
        // Default: 1 hour minimum, but hosts can configure per-car settings.
        //
        // PHASE 2 ENHANCEMENT: Use car-specific advance notice from CarBookingSettings
        // Falls back to system default (1 hour) if not configured.
        //
        // All booking times are interpreted as Europe/Belgrade local time.
        // ========================================================================
        final java.time.ZoneId SERBIA_ZONE = java.time.ZoneId.of("Europe/Belgrade");
        LocalDateTime tripStartDateTime = dto.getStartTime();
        LocalDateTime tripEndDateTime = dto.getEndTime();
        LocalDateTime nowInSerbia = LocalDateTime.now(SERBIA_ZONE);
        
        // Get car-specific booking settings (or defaults)
        var carBookingSettings = car.getEffectiveBookingSettings();
        int advanceNoticeHours = carBookingSettings.getEffectiveAdvanceNoticeHours();
        LocalDateTime minStartTime = nowInSerbia.plusHours(advanceNoticeHours);
        
        if (tripStartDateTime.isBefore(minStartTime)) {
            log.warn("Lead time validation failed: tripStart={}, minRequired={}, advanceNotice={}h, now={}", 
                    tripStartDateTime, minStartTime, advanceNoticeHours, nowInSerbia);
            throw new org.example.rentoza.exception.ValidationException(
                    String.format("Rezervacija mora biti kreirana najmanje %d sat(a) pre početka. " +
                    "Najraniji mogući početak: %s (CET/CEST)", 
                    advanceNoticeHours,
                    minStartTime.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))
            );
        }
        
        // ========================================================================
        // MINIMUM TRIP DURATION VALIDATION (Phase 2 Enhancement)
        // ========================================================================
        // Ensure trip meets car-specific minimum duration.
        // Default: 24 hours, but hosts can configure per-car.
        // ========================================================================
        int minTripHours = carBookingSettings.getEffectiveMinTripHours();
        long tripDurationHours = ChronoUnit.HOURS.between(tripStartDateTime, tripEndDateTime);
        
        if (tripDurationHours < minTripHours) {
            log.warn("Minimum duration validation failed: duration={}h, required={}h, carId={}", 
                    tripDurationHours, minTripHours, car.getId());
            throw new org.example.rentoza.exception.ValidationException(
                    String.format("Minimalno trajanje iznajmljivanja za ovaj automobil je %d sati. " +
                    "Vaše izabrano trajanje: %d sati.", minTripHours, tripDurationHours)
            );
        }
        
        // ========================================================================
        // MAXIMUM TRIP DURATION VALIDATION (Phase 2 Enhancement)
        // ========================================================================
        // Ensure trip doesn't exceed car-specific maximum duration.
        // Default: 30 days, but hosts can configure per-car.
        // ========================================================================
        int maxTripDays = carBookingSettings.getEffectiveMaxTripDays();
        long tripDurationDays = ChronoUnit.DAYS.between(tripStartDateTime.toLocalDate(), tripEndDateTime.toLocalDate());
        
        if (tripDurationDays > maxTripDays) {
            log.warn("Maximum duration validation failed: duration={}d, max={}d, carId={}", 
                    tripDurationDays, maxTripDays, car.getId());
            throw new org.example.rentoza.exception.ValidationException(
                    String.format("Maksimalno trajanje iznajmljivanja za ovaj automobil je %d dana. " +
                    "Vaše izabrano trajanje: %d dana.", maxTripDays, tripDurationDays)
            );
        }

        // ========================================================================
        // DRIVER LICENSE VERIFICATION CHECK (Renter Identity Verification)
        // ========================================================================
        // Validate that the renter has a verified, non-expired driver's license.
        // The eligibility check considers:
        // 1. License verification status (must be APPROVED)
        // 2. License expiry date (must be valid through end of trip)
        // 3. Suspension status (must not be SUSPENDED)
        //
        // This is a critical safety gate - unverified renters cannot book vehicles.
        // The check is configurable via app.renter-verification.license-required property.
        if (licenseRequired) {
            BookingEligibilityDTO eligibility = renterVerificationService.checkBookingEligibilityForUser(
                    renter, dto.getEndTime().toLocalDate());
            
            if (!eligibility.isEligible()) {
                log.warn("Renter license verification failed: userId={}, reason={}", 
                        renter.getId(), eligibility.getBlockReason());
                throw new org.example.rentoza.exception.ValidationException(eligibility.getMessageSr());
            }
            
            log.debug("Renter license verification passed: userId={}, status={}", 
                    renter.getId(), renter.getDriverLicenseStatus());
        }

        Booking booking = new Booking();
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStartTime(dto.getStartTime());
        booking.setEndTime(dto.getEndTime());
        
        // P1 FIX: Set idempotency key for duplicate detection
        if (dto.getIdempotencyKey() != null && !dto.getIdempotencyKey().isBlank()) {
            booking.setIdempotencyKey(dto.getIdempotencyKey());
        }

        // Determine initial status based on feature flag
        BookingStatus initialStatus;
        boolean approvalWorkflowEnabledForUser = approvalEnabled || (betaUsers != null && betaUsers.contains(renter.getId()));
        boolean instantBookEnabledForCar = carBookingSettings.isInstantBookEnabled();

        // Explicit policy:
        // - If approval workflow feature is enabled AND car is not instant-book -> PENDING_APPROVAL
        // - Otherwise -> ACTIVE instant booking
        // This preserves per-car instant-booking semantics and avoids forcing all requests into pending state.
        if (approvalWorkflowEnabledForUser && !instantBookEnabledForCar) {
            initialStatus = BookingStatus.PENDING_APPROVAL;
            
            // ========================================================================
            // DYNAMIC DEADLINE CALCULATION (Logic Matrix Implementation)
            // ========================================================================
                // Formula: MIN(createdAt + approvalSlaHours, tripStartTime - minGuestPreparationHours)
            // 
            // This ensures:
                // 1. Standard case: Host gets up to configured SLA to respond
            // 2. Short notice: Host response window shrinks to ensure guest has at least
                //    minGuestPreparationHours between approval deadline and trip start
            // 
            // Examples:
                // - Trip in 72h, SLA=48h, prep=12h: deadline = now + 48h
                // - Trip in 36h, SLA=48h, prep=12h: deadline = start - 12h (= now + 24h)
                java.time.LocalDateTime effectiveDeadline = calculateDecisionDeadline(nowInSerbia, tripStartDateTime);

                if (!effectiveDeadline.isAfter(nowInSerbia)) {
                throw new org.example.rentoza.exception.ValidationException(
                    "Rezervacija zahteva odobrenje, ali rok za odluku je već istekao za izabrani početak. " +
                    "Izaberite kasniji termin početka ili vozilo sa instant rezervacijom."
                );
                }
            
            booking.setDecisionDeadlineAt(effectiveDeadline);
            
            log.debug("Booking created with PENDING_APPROVAL status. " +
                    "Deadline calculation: createdAt={}, approvalSlaHours={}, minGuestPreparationHours={}, effectiveDeadline={}",
                    nowInSerbia, approvalSlaHours, minGuestPreparationHours, effectiveDeadline);
        } else {
            initialStatus = BookingStatus.ACTIVE;
            // Backfill approval metadata for instant bookings
                booking.setApprovedAt(nowInSerbia);
            booking.setPaymentStatus("AUTHORIZED");
                log.debug("Booking created with ACTIVE status (instant booking mode)");
        }
        booking.setStatus(initialStatus);

        // Set insurance type and prepaid refuel
        booking.setInsuranceType(dto.getInsuranceType() != null ? dto.getInsuranceType() : "BASIC");
        booking.setPrepaidRefuel(dto.isPrepaidRefuel());

        // ========================================================================
        // GEOSPATIAL PICKUP LOCATION SNAPSHOT (Phase 2.4)
        // ========================================================================
        // Capture the agreed pickup location at booking time. This is IMMUTABLE
        // and represents the contractual handover point.
        //
        // Priority:
        // 1. If custom coordinates provided → use custom location
        // 2. Otherwise → use car's home location (default self-pickup)
        //
        // This snapshot enables:
        // - Check-in geofence validation against agreed location (not current car position)
        // - Delivery fee calculation based on distance from car's home
        // - Audit trail for dispute resolution if car is moved
        
        GeoPoint pickupLocation;
        BigDecimal deliveryFee = BigDecimal.ZERO;
        BigDecimal deliveryDistanceKm = null;
        
        if (dto.hasCustomPickupLocation()) {
            // Custom pickup location provided (delivery or specific pickup point)
            pickupLocation = new GeoPoint(
                    dto.getPickupLatitude(),
                    dto.getPickupLongitude(),
                    dto.getPickupAddress(),
                    dto.getPickupCity(),
                    dto.getPickupZipCode(),
                    null // accuracy not applicable for user-selected locations
            );
            
            // Calculate delivery fee if delivery is requested
            if (dto.isDeliveryRequested() && car.hasGeoLocation()) {
                DeliveryFeeResult feeResult = deliveryFeeCalculator.calculateDeliveryFee(car, pickupLocation);
                
                if (feeResult.available()) {
                    deliveryFee = feeResult.fee();
                    deliveryDistanceKm = BigDecimal.valueOf(feeResult.distanceKm())
                            .setScale(2, RoundingMode.HALF_UP);
                    
                    log.debug("Delivery fee calculated: distance={}km, fee={}, source={}, poi={}",
                            feeResult.distanceKm(), feeResult.fee(),
                            feeResult.routingSource(), feeResult.appliedPoiCode());
                } else if (feeResult.maxRadiusKm() != null) {
                    // Destination outside car's delivery radius
                    throw new org.example.rentoza.exception.ValidationException(
                            String.format("Delivery location is outside the car's delivery radius. " +
                                    "Maximum delivery distance: %.1f km, your distance: %.1f km",
                                    feeResult.maxRadiusKm(), feeResult.distanceKm())
                    );
                } else {
                    // Delivery not available for other reasons (car doesn't offer delivery, etc.)
                    throw new org.example.rentoza.exception.ValidationException(
                            "Delivery is not available for this car: " + feeResult.unavailableReason()
                    );
                }
            }
            
            log.debug("Custom pickup location set: lat={}, lon={}, address={}, deliveryRequested={}",
                    dto.getPickupLatitude(), dto.getPickupLongitude(), 
                    dto.getPickupAddress(), dto.isDeliveryRequested());
        } else {
            // Default to car's home location (self-pickup)
            if (car.hasGeoLocation()) {
                GeoPoint carLocation = car.getLocationGeoPoint();
                pickupLocation = new GeoPoint(
                        carLocation.getLatitude(),
                        carLocation.getLongitude(),
                        carLocation.getAddress(),
                        carLocation.getCity(),
                        carLocation.getZipCode(),
                        carLocation.getAccuracyMeters()
                );
                log.debug("Using car's home location for pickup: lat={}, lon={}, city={}",
                        carLocation.getLatitude(), carLocation.getLongitude(), carLocation.getCity());
            } else {
                // Car has no geolocation yet (legacy data) - pickup location will be null
                pickupLocation = null;
                log.warn("Car {} has no geolocation. Pickup location not set for booking.", car.getId());
            }
        }
        
        // Set pickup location and delivery fields on booking
        booking.setPickupLocation(pickupLocation);
        booking.setDeliveryDistanceKm(deliveryDistanceKm);
        booking.setDeliveryFeeCalculated(deliveryFee);

        // ========================================================================
        // PRICE CALCULATION WITH BigDecimal (Turo-Standard Breakdown)
        // ========================================================================
        // Uses BigDecimal.multiply() and .add() instead of * and + operators.
        // RoundingMode.HALF_UP ensures consistent banker's rounding.
        // Scale of 2 matches DECIMAL(19, 2) column in database.
        //
        // Exact Timestamp Architecture: Calculate 24-hour periods, rounded up.
        // Example: 36 hours = 2 periods (ceil(36/24) = 2)
        long hours = ChronoUnit.HOURS.between(dto.getStartTime(), dto.getEndTime());
        int periods = Math.max(1, (int) Math.ceil(hours / 24.0));
        BigDecimal basePrice = car.getPricePerDay()
                .multiply(BigDecimal.valueOf(periods));

        // Apply insurance multiplier (precise decimal representation)
        BigDecimal insuranceMultiplier = switch (booking.getInsuranceType().toUpperCase()) {
            case "STANDARD" -> new BigDecimal("1.10");
            case "PREMIUM" -> new BigDecimal("1.20");
            default -> BigDecimal.ONE; // 1.00 for BASIC
        };

        // Insurance cost (additional cost above base, not the multiplier itself)
        BigDecimal insuranceCost = basePrice.multiply(insuranceMultiplier)
                .subtract(basePrice)
                .setScale(2, RoundingMode.HALF_UP);

        // Service fee (Turo standard: configurable rate, default 15%)
        BigDecimal serviceFee = basePrice
                .multiply(new BigDecimal(String.valueOf(serviceFeeRate)))
                .setScale(2, RoundingMode.HALF_UP);

        // Calculate refuel cost if applicable
        BigDecimal refuelCost = BigDecimal.ZERO;
        if (booking.isPrepaidRefuel() && car.getFuelConsumption() != null) {
            // Approximate: fuelConsumption * 6.5 (EUR/L) * 10 (assumed liters per rental)
            // Using string constructor for precise decimal representation
            refuelCost = new BigDecimal(car.getFuelConsumption().toString())
                    .multiply(new BigDecimal("6.5"))
                    .multiply(BigDecimal.TEN)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // Security deposit amount (held, not charged)
        BigDecimal depositAmount = BigDecimal.valueOf(defaultDepositAmountRsd);

        // Final calculation with proper rounding (includes all components)
        // Total = basePrice + insuranceCost + serviceFee + refuelCost + deliveryFee
        BigDecimal totalPrice = basePrice
                .add(insuranceCost)
                .add(serviceFee)
                .add(refuelCost)
                .add(deliveryFee)
                .setScale(2, RoundingMode.HALF_UP);
        
        booking.setTotalPrice(totalPrice);
        booking.setSecurityDeposit(depositAmount);
        
        // P2 FIX: Persist fee breakdown at creation time to prevent price drift.
        // Previously these were recalculated from current rates in BookingResponseDTO,
        // which could drift if fee rates or insurance multipliers change after booking.
        booking.setServiceFeeSnapshot(serviceFee);
        booking.setInsuranceCostSnapshot(insuranceCost);
        
        log.debug("Price breakdown: hours={}, periods={}, base={}, insurance={}, serviceFee={}, refuel={}, delivery={}, deposit={}, total={}",
                hours, periods, basePrice, insuranceCost, serviceFee, refuelCost, deliveryFee, depositAmount, totalPrice);

        // ========================================================================
        // CANCELLATION POLICY: Snapshot Daily Rate at Booking Time
        // ========================================================================
        // CRITICAL: Lock the car's daily rate at booking creation.
        // This ensures penalty calculations use the rate agreed upon at booking,
        // even if the owner changes the price before a potential cancellation.
        // 
        // Used by: TuroCancellationPolicyService.calculateGuestCancellation()
        // Immutable: This value should NEVER be updated after booking creation.
        // Store the tokenized payment method ID before saving — needed at check-in
        // to authorize the security deposit close to the trip start (P0-3 fix).
        booking.setStoredPaymentMethodId(dto.getPaymentMethodId());
        booking.setSnapshotDailyRate(car.getPricePerDay());

        final Booking savedBooking;
        try {
            savedBooking = repo.save(booking);
        } catch (DataIntegrityViolationException ex) {
            // Race-safe collision fallback: two concurrent requests carrying the same
            // idempotency key both cleared the early check before either committed.
            // The unique constraint on idempotency_key rejected the second insert —
            // resolve canonically rather than surfacing a 500.
            if (dto.getIdempotencyKey() != null && !dto.getIdempotencyKey().isBlank()) {
                log.warn("[Idempotency] DB collision on key {} — resolving via hydrated lookup",
                        dto.getIdempotencyKey());
                return repo.findByIdempotencyKeyWithRelations(dto.getIdempotencyKey())
                        .map(b -> {
                            if ("REDIRECT_REQUIRED".equals(b.getPaymentStatus())) {
                                return bookingPaymentService.findPendingRedirectUrl(b.getId())
                                        .map(url -> BookingCreationResult.redirect(b, url))
                                        .orElseGet(() -> BookingCreationResult.success(b));
                            }
                            return BookingCreationResult.success(b);
                        })
                        .orElseThrow(() -> ex);
            }
            throw ex;
        }

        // ========================================================================
        // PAYMENT AUTHORIZATION (P0 Fix - Must happen before booking is finalized)
        // ========================================================================
        // Authorize booking payment (hold total amount). Authorization places a hold
        // on the guest's card but does not capture the funds.
        //
        // Security deposit is intentionally NOT authorized here. It is authorized
        // when the check-in window opens (T-Xh before trip start), keeping the
        // hold within the card-authorization lifetime window (P0-3 fix).
        // ========================================================================
        String paymentMethodId = dto.getPaymentMethodId();

        // 1. Authorize booking payment (hold total amount)
        org.example.rentoza.payment.PaymentProvider.PaymentResult bookingPaymentResult =
                bookingPaymentService.processBookingPayment(savedBooking.getId(), paymentMethodId);

        // R1-FIX: Handle 3DS/SCA redirect as a non-exception path. When the provider
        // returns REDIRECT_REQUIRED, the booking and PaymentTransaction rows must persist
        // (not roll back) so the guest can complete 3DS verification and return.
        // processBookingPayment() already saved paymentStatus="REDIRECT_REQUIRED" on the
        // booking entity and persisted the PaymentTransaction with REDIRECT_REQUIRED status.
        if (bookingPaymentResult.isRedirectRequired()) {
            log.info("Payment requires 3DS redirect for booking {} — persisting booking and returning redirect URL: {}",
                    savedBooking.getId(), bookingPaymentResult.getRedirectUrl());
            Hibernate.initialize(savedBooking.getCar());
            Hibernate.initialize(savedBooking.getCar().getOwner());
            // Do NOT send booking-confirmed notifications — payment is not yet confirmed.
            // Notifications will be sent when the webhook confirms the payment.
            return BookingCreationResult.redirect(savedBooking, bookingPaymentResult.getRedirectUrl());
        }

        if (!bookingPaymentResult.isSuccess()) {
            log.warn("Payment authorization failed for booking {}: {}",
                    savedBooking.getId(), bookingPaymentResult.getErrorMessage());
            throw new org.example.rentoza.exception.PaymentAuthorizationException(
                    "Autorizacija plaćanja nije uspela: " + bookingPaymentResult.getErrorMessage(),
                    bookingPaymentResult.getErrorCode() != null ? bookingPaymentResult.getErrorCode() : "PAYMENT_FAILED"
            );
        }

        // Note: BookingPaymentService.processBookingPayment() already persists
        // paymentVerificationRef, bookingAuthorizationId, and paymentStatus on the
        // booking entity. Deposit authorization is deferred to check-in window opening.
        log.info("Payment authorized for booking {} — deposit will be authorized at check-in. bookingAuth={}",
                savedBooking.getId(), bookingPaymentResult.getAuthorizationId());

        // Initialize car and owner to avoid lazy loading issues
        Hibernate.initialize(savedBooking.getCar());
        Hibernate.initialize(savedBooking.getCar().getOwner());

        // Handle post-creation logic based on booking status
        if (savedBooking.getStatus() == BookingStatus.PENDING_APPROVAL) {
            // Approval workflow: Send request notifications, DO NOT create chat
            sendPendingApprovalNotifications(savedBooking, renter, car);
            log.info("Booking request submitted: bookingId={}, renterId={}, carId={}, decisionDeadline={}",
                    savedBooking.getId(), renter.getId(), car.getId(), savedBooking.getDecisionDeadlineAt());
        } else {
            // Legacy instant booking: Create chat and send confirmed notifications
            createChatConversationForInstantBooking(savedBooking, renter, car);
            sendInstantBookingNotifications(savedBooking, renter, car);
            log.info("Instant booking created: bookingId={}, renterId={}, carId={}",
                    savedBooking.getId(), renter.getId(), car.getId());
        }

        return BookingCreationResult.success(savedBooking);
    }

    LocalDateTime calculateDecisionDeadline(LocalDateTime createdAt, LocalDateTime tripStartDateTime) {
        LocalDateTime slaDeadline = createdAt.plusHours(approvalSlaHours);
        LocalDateTime preparationDeadline = tripStartDateTime.minusHours(minGuestPreparationHours);
        return slaDeadline.isBefore(preparationDeadline) ? slaDeadline : preparationDeadline;
    }

    /**
     * Get bookings by user email with ownership verification.
     * RLS-ENFORCED: Returns bookings only if requester email matches or is admin.
     * 
     * @param email User's email
     * @return List of bookings for the user
     * @throws org.springframework.security.access.AccessDeniedException if requester is not the user or admin
     */
    public List<Booking> getBookingsByUser(String email) {
        // RLS ENFORCEMENT: Verify requester is the user or admin
        String requesterEmail = currentUser.email();
        if (!requesterEmail.equalsIgnoreCase(email) && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to access bookings for user: " + email
            );
        }
        
        return repo.findByRenterEmailIgnoreCase(email);
    }

    /**
     * Get booking by ID with ownership verification.
     * RLS-ENFORCED: Returns booking only if user is the renter, car owner, or admin.
     * 
     * @param id Booking ID
     * @return Booking entity with all related entities loaded
     * @throws ResourceNotFoundException if booking not found or user lacks access
     */
    public Booking getBookingById(Long id) {
        // RLS ENFORCEMENT: Use ownership-scoped query
        Long userId = currentUser.id();
        return repo.findByIdForUser(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found with id: " + id + " or user lacks access"
                ));
    }

    /**
     * Get all booking IDs.
     * ADMIN-ONLY: This method is for internal/admin use only.
     * 
     * P0-5 FIX: Uses optimized ID-only query instead of loading full entities.
     * 
     * @return List of all booking IDs
     */
    public List<Long> getAllBookingIds() {
        // RLS ENFORCEMENT: Admin-only operation
        if (!currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized: Only admins can access all booking IDs"
            );
        }
        
        return repo.findAllBookingIds();
    }

    /**
     * Get public-safe booking slots for a specific car (calendar availability).
     * 
     * Purpose:
     * - Expose only date ranges (carId, startDate, endDate) for calendar UI
     * - Allow renters/guests to see which dates are unavailable
     * - No PII exposure (no renter, owner, pricing information)
     * 
     * Security:
     * - Public-safe: returns minimal DTO (BookingSlotDTO)
     * - Only includes blocking statuses: ACTIVE, PENDING_APPROVAL
     * - CANCELLED, DECLINED, COMPLETED, EXPIRED are excluded (dates are free)
     * - Does NOT require authentication or ownership verification
     * - Compliant with RLS: no sensitive data exposure
     * 
     * @param carId Car ID to fetch booking slots for
     * @return List of BookingSlotDTO with only date ranges
     */
    @Cacheable(value = "bookingAvailability", key = "'slots-' + #carId", unless = "#result.isEmpty()")
    public List<org.example.rentoza.booking.dto.BookingSlotDTO> getPublicBookedSlots(Long carId) {
        // Use optimized query that already filters by blocking statuses (ACTIVE, PENDING_APPROVAL)
        List<Booking> bookings = repo.findPublicBookingsForCar(carId);
        
        // Map to public-safe DTO with only date ranges
        return bookings.stream()
                .map(org.example.rentoza.booking.dto.BookingSlotDTO::new)
                .toList();
    }

    /**
     * Get conversation-safe booking summary for chat enrichment.
     * 
     * Purpose:
     * - Enable chat microservice to enrich conversations with booking context
     * - Show "Future trip with 2020 BMW X5" in chat UI without exposing PII
     * - Support service-to-service trust with user assertion
     * 
     * Security (RLS-ENFORCED):
     * - Verifies actAsUserId matches booking participant (renterId or ownerId)
     * - Throws AccessDeniedException if user not authorized
     * - No PII exposure: Returns BookingConversationDTO (no renter/owner names, emails, pricing)
     * - Audit logging: Logs service-to-service calls with actAsUserId
     * 
     * Access Patterns:
     * - Direct JWT: User requests their own booking (actAsUserId = authenticated user ID)
     * - Service-to-service: Chat service asserts user context (X-Act-As-User-Id header)
     * 
     * Computed Fields:
     * - tripStatus: FUTURE (before start), CURRENT (during trip), PAST (after end)
    * - messagingAllowed: true for active lifecycle states and COMPLETED, false for cancelled/declined/expired/no-show
     * 
     * @param bookingId Booking ID
     * @param actAsUserId User ID asserting access (must be renter or owner)
     * @return BookingConversationDTO with conversation-safe booking summary
     * @throws ResourceNotFoundException if booking not found
     * @throws org.springframework.security.access.AccessDeniedException if actAsUserId not a participant
     */
    public org.example.rentoza.booking.dto.BookingConversationDTO getConversationView(Long bookingId, Long actAsUserId) {
        // Fetch booking with eager loading of car, images, and participants
        Booking booking = repo.findByIdForConversationView(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found with id: " + bookingId
                ));
        
        // RLS ENFORCEMENT: Verify actAsUserId is a participant (renter or owner)
        Long renterId = booking.getRenter().getId();
        Long ownerId = booking.getCar().getOwner().getId();
        
        boolean isRenter = actAsUserId.equals(renterId);
        boolean isOwner = actAsUserId.equals(ownerId);
        
        log.debug("[RLS] Validating conversation view access: bookingId={}, actAsUserId={}, isRenter={}, isOwner={}", 
                bookingId, actAsUserId, isRenter, isOwner);
        
        if (!isRenter && !isOwner) {
            log.warn("Unauthorized conversation view access: bookingId={}, actAsUserId={}", bookingId, actAsUserId);
            throw new org.springframework.security.access.AccessDeniedException(
                    "User " + actAsUserId + " is not authorized to view booking " + bookingId + 
                    " (not a participant)"
            );
        }
        
        // Audit log for successful access
        String role = isRenter ? "RENTER" : "OWNER";
        log.info("Conversation view granted: bookingId={}, role={}", bookingId, role);
        
        // Return conversation-safe DTO (NO PII)
        return new org.example.rentoza.booking.dto.BookingConversationDTO(booking);
    }

    /**
     * Get all bookings for a specific car with owner verification.
     * RLS-ENFORCED: Returns bookings only if requester is the car owner or admin.
     * 
     * @param carId Car ID
     * @return List of bookings for the car
     * @throws org.springframework.security.access.AccessDeniedException if requester is not the car owner or admin
     */
    public List<BookingResponseDTO> getBookingsForCar(Long carId) {
        // RLS ENFORCEMENT: Verify car ownership
        Long ownerId = currentUser.id();
        
        // Use ownership-scoped query unless admin
        List<Booking> bookings;
        if (currentUser.isAdmin()) {
            bookings = repo.findByCarId(carId);
        } else {
            bookings = repo.findByCarIdForOwner(carId, ownerId);
            
            // If no bookings found, verify car exists and belongs to user
            if (bookings.isEmpty()) {
                Car car = carRepo.findById(carId)
                        .orElseThrow(() -> new ResourceNotFoundException("Car not found with id: " + carId));
                
                if (!car.getOwner().getId().equals(ownerId)) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "Unauthorized to access bookings for car " + carId + ": user is not the owner"
                    );
                }
            }
        }

        return bookings.stream()
                .map(BookingResponseDTO::new)
                .toList();
    }

    // ==================== CANCELLATION POLICY MIGRATION (Phase 2) ====================

    /**
     * Generate a cancellation preview WITHOUT executing the cancellation.
     * 
     * <p>Allows users to see the financial consequences before committing.
     * RLS-ENFORCED: Only renter or host can preview their booking cancellation.
     * 
     * @param id Booking ID
     * @return Preview DTO with penalty/refund calculations
     */
    @Transactional(readOnly = true)
    public CancellationPreviewDTO getCancellationPreview(Long id) {
        var booking = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        
        // RLS ENFORCEMENT: Only renter or host can preview
        Long userId = currentUser.id();
        User initiator = getCurrentUserEntity();
        
        boolean isRenter = booking.getRenter().getId().equals(userId);
        boolean isHost = booking.getCar().getOwner().getId().equals(userId);
        
        if (!isRenter && !isHost && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to preview cancellation for booking " + id
            );
        }
        
        // Force initialize for calculation
        Hibernate.initialize(booking.getCar());
        Hibernate.initialize(booking.getRenter());
        Hibernate.initialize(booking.getCar().getOwner());
        
        return cancellationPolicyService.generatePreview(booking, initiator);
    }

    /**
     * Cancel booking with Turo-style policy enforcement.
     * 
     * <p>This method:
     * <ul>
     *   <li>Validates booking state and user authorization</li>
     *   <li>Calculates penalties/refunds via CancellationPolicyService</li>
     *   <li>Creates immutable CancellationRecord audit entry</li>
     *   <li>Updates booking state and denormalized fields</li>
     *   <li>Applies host penalty escalation if applicable</li>
     *   <li>Sends notifications to both parties</li>
     * </ul>
     * 
     * <p>RLS-ENFORCED: Only the renter or host can cancel their booking.
     * 
     * @param id Booking ID
     * @param request Cancellation request with reason and optional notes
     * @return Cancellation result with financial details
     * @throws RuntimeException if booking not found
     * @throws IllegalStateException if booking cannot be cancelled
     * @throws org.springframework.security.access.AccessDeniedException if user is not authorized
     */
    @Transactional
    public CancellationResultDTO cancelBookingWithPolicy(Long id, CancellationRequestDTO request) {
        var booking = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        
        // RLS ENFORCEMENT: Only renter or host can cancel
        Long userId = currentUser.id();
        User initiator = getCurrentUserEntity();
        
        boolean isRenter = booking.getRenter().getId().equals(userId);
        boolean isHost = booking.getCar().getOwner().getId().equals(userId);
        
        if (!isRenter && !isHost && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to cancel booking " + id + ": only the renter or host can cancel"
            );
        }
        
        // Force initialize for processing
        Hibernate.initialize(booking.getCar());
        Hibernate.initialize(booking.getRenter());
        Hibernate.initialize(booking.getCar().getOwner());
        
        // Process cancellation through policy service
        CancellationResultDTO result = cancellationPolicyService.processCancellation(
            booking, 
            initiator, 
            request.reason(), 
            request.notes()
        );
        
        // Send notifications only after successful commit
        runAfterCommit(() -> sendCancellationNotifications(booking, result));
        
        return result;
    }

    /**
     * Send cancellation notifications to both parties with financial details.
     */
    private void sendCancellationNotifications(Booking booking, CancellationResultDTO result) {
        try {
            Car car = booking.getCar();
            User renter = booking.getRenter();
            User owner = car.getOwner();
            String carInfo = car.getBrand() + " " + car.getModel();
            
            // Determine cancelling party for messaging
            String cancellerName = switch (result.cancelledBy()) {
                case GUEST -> renter.getFirstName();
                case HOST -> owner.getFirstName();
                case SYSTEM -> "sistem";
            };
            
            // Format refund/penalty for notification
            String refundInfo = result.refundToGuest().compareTo(java.math.BigDecimal.ZERO) > 0
                ? String.format(" Povraćaj: %,.2f RSD.", result.refundToGuest())
                : "";
            
            String penaltyInfo = result.penaltyAmount().compareTo(java.math.BigDecimal.ZERO) > 0
                ? String.format(" Penali: %,.2f RSD.", result.penaltyAmount())
                : "";
            
            // Notify renter
            String renterMessage = switch (result.cancelledBy()) {
                case GUEST -> "Otkazali ste rezervaciju za " + carInfo + "." + refundInfo;
                case HOST -> "Vlasnik je otkazao vašu rezervaciju za " + carInfo + ". Dobićete pun povraćaj.";
                case SYSTEM -> "Vaša rezervacija za " + carInfo + " je otkazana od strane sistema." + refundInfo;
            };
            
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(renter.getId())
                    .type(NotificationType.BOOKING_CANCELLED)
                    .message(renterMessage)
                    .relatedEntityId("booking-" + booking.getId())
                    .build());

            // Notify owner
            String ownerMessage = switch (result.cancelledBy()) {
                case GUEST -> "Gost " + renter.getFirstName() + " je otkazao rezervaciju za " + carInfo + "." + penaltyInfo;
                case HOST -> "Otkazali ste rezervaciju za " + carInfo + " gostu " + renter.getFirstName() + ".";
                case SYSTEM -> "Rezervacija za " + carInfo + " je otkazana od strane sistema.";
            };
            
            // Add host penalty/suspension info if applicable
            if (result.cancelledBy() == org.example.rentoza.booking.cancellation.CancelledBy.HOST) {
                if (result.hostPenaltyApplied() != null && result.hostPenaltyApplied().compareTo(java.math.BigDecimal.ZERO) > 0) {
                    ownerMessage += String.format(" Penali: %,.2f RSD.", result.hostPenaltyApplied());
                }
                if (result.hostSuspendedUntil() != null) {
                    ownerMessage += " Suspendovani ste do " + 
                        result.hostSuspendedUntil().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + ".";
                }
            }
            
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(owner.getId())
                    .type(NotificationType.BOOKING_CANCELLED)
                    .message(ownerMessage)
                    .relatedEntityId("booking-" + booking.getId())
                    .build());

            log.info("Cancellation notifications sent for booking {} (cancelledBy={})", 
                booking.getId(), result.cancelledBy());
        } catch (Exception e) {
            log.error("Failed to send cancellation notifications: {}", e.getMessage(), e);
        }
    }

    /**
     * Ensure side effects (emails/notifications) execute only after a successful commit.
     */
    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * Get the current authenticated user as a User entity.
     */
    private User getCurrentUserEntity() {
        String email = currentUser.email();
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Current user not found: " + email));
    }

    @Transactional
    public List<UserBookingResponseDTO> getMyBookings(String userEmail) {

        User user = userRepo.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Fetch all bookings with car details using optimized query (no N+1)
        List<Booking> bookings = repo.findByRenterIdWithDetails(user.getId());

        if (bookings.isEmpty()) {
            return List.of();
        }

        // Fetch all reviews for these bookings in one query
        List<Long> bookingIds = bookings.stream()
                .map(Booking::getId)
                .collect(Collectors.toList());

        Map<Long, Review> reviewsByBookingId = reviewRepo
                .findByBookingIdInAndDirection(bookingIds, ReviewDirection.FROM_USER)
                .stream()
                .collect(Collectors.toMap(
                        r -> r.getBooking().getId(),
                        r -> r,
                        (existing, replacement) -> existing // Keep first if duplicate
                ));

        // Map to DTOs
        return bookings.stream()
                .map(booking -> {
                    Car car = booking.getCar();
                    Review review = reviewsByBookingId.get(booking.getId());

                    return new UserBookingResponseDTO(
                            booking.getId(),
                            car.getId(),
                            car.getBrand(),
                            car.getModel(),
                            car.getYear(),
                            car.getImageUrl(),
                            car.getLocation(),
                            car.getPricePerDay(),
                            booking.getStartTime(),
                            booking.getEndTime(),
                            booking.getTotalPrice(),
                            booking.getStatus().name(),
                            booking.getDecisionDeadlineAt(),
                            booking.getApprovedAt(),
                            booking.getDeclinedAt(),
                            booking.getDeclineReason(),
                            review != null,
                            review != null ? review.getRating() : null,
                            review != null ? review.getComment() : null
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * Scheduled task to automatically mark overdue bookings as COMPLETED.
     * Runs every hour to ensure database consistency.
     *
     * This maintains data integrity by ensuring bookings whose end date has passed
     * are automatically marked as COMPLETED, keeping the database aligned with
     * the unified completion logic used in review validation.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Belgrade") // Every hour at minute 0
    @Transactional
    public void autoCompleteOverdueBookings() {
        LocalDateTime now = LocalDateTime.now();
        List<Booking> overdueBookings = repo.findOverdueBookings(now);

        if (!overdueBookings.isEmpty()) {
            log.info("Auto-completing {} overdue bookings", overdueBookings.size());

            for (Booking booking : overdueBookings) {
                booking.setStatus(BookingStatus.COMPLETED);
                log.debug("Auto-completed booking ID {} (end time: {})", booking.getId(), booking.getEndTime());
            }

            repo.saveAll(overdueBookings);
            log.info("Successfully auto-completed {} bookings", overdueBookings.size());
        }
    }

    /**
     * Check if a booking is considered completed.
     * A booking is completed if:
     * 1. Status is explicitly set to COMPLETED, OR
     * 2. The end date is in the past (regardless of status)
     *
     * This unified check ensures frontend and backend consistency for review eligibility.
     *
     * @param booking The booking to check
     * @return true if the booking is completed
     */
    public boolean isBookingCompleted(Booking booking) {
        if (booking == null) {
            return false;
        }

        return booking.getStatus() == BookingStatus.COMPLETED
            || (booking.getEndTime() != null && booking.getEndTime().isBefore(LocalDateTime.now()));
    }

    /**
     * Format trip time info for notifications and display.
     * 
     * @param startTime Trip start timestamp
     * @param endTime Trip end timestamp
     * @return Formatted time string in Serbian
     */
    private String formatTripTimeInfo(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            return "Vreme nije navedeno";
        }
        
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        return startTime.format(formatter) + " - " + endTime.format(formatter);
    }

    /**
     * Check if a booking can be made for the given time range.
     * Validates availability without persisting the booking (used for pre-submit validation).
     * 
     * @param dto The booking request containing car ID and time range
     * @return true if times are available, false if there's a conflict
     */
    public boolean checkAvailability(BookingRequestDTO dto) {
        // Get all confirmed bookings for the car in the given time range
        List<Booking> existingBookings = repo.findByCarIdAndTimeRangeBlocking(
                dto.getCarId(),
                dto.getStartTime(),
                dto.getEndTime()
        );

        // If any overlapping bookings exist, times are not available
        return existingBookings.isEmpty();
    }

    // ========== HOST APPROVAL WORKFLOW HELPER METHODS ==========

    /**
     * Send notifications for pending approval workflow.
     * Notifies both guest (request sent) and host (request received).
     */
    private void sendPendingApprovalNotifications(Booking booking, User renter, Car car) {
        try {
            String carInfo = car.getBrand() + " " + car.getModel();
            String insuranceInfo = booking.getInsuranceType() != null ? booking.getInsuranceType() : "Basic";
            String refuelInfo = booking.isPrepaidRefuel() ? " sa plaćenim gorivom" : "";
            String tripTimeInfo = formatTripTimeInfo(booking.getStartTime(), booking.getEndTime());

            // Notify renter: request sent
            String renterMessage = String.format(
                    "Vaš zahtev za rezervaciju %s je poslat! Čeka se odobrenje vlasnika. (%s insurance%s, Period: %s)",
                    carInfo, insuranceInfo, refuelInfo, tripTimeInfo);
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(renter.getId())
                    .type(NotificationType.BOOKING_REQUEST_SENT)
                    .message(renterMessage)
                    .relatedEntityId("booking-" + booking.getId())
                    .build());

            // Notify owner: request received
            String ownerMessage = String.format(
                    "Novi zahtev za rezervaciju %s od %s %s! (%s insurance%s, Period: %s)",
                    carInfo, renter.getFirstName(), renter.getLastName(), insuranceInfo, refuelInfo, tripTimeInfo);
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(car.getOwner().getId())
                    .type(NotificationType.BOOKING_REQUEST_RECEIVED)
                    .message(ownerMessage)
                    .relatedEntityId("booking-" + booking.getId())
                    .build());

            log.debug("Pending approval notifications sent for booking {}", booking.getId());
        } catch (Exception e) {
            log.error("Failed to send pending approval notifications for booking {}: {}",
                    booking.getId(), e.getMessage(), e);
        }
    }

    /**
     * Send notifications for instant booking (legacy workflow).
     * Notifies both renter and owner that booking is confirmed.
     */
    private void sendInstantBookingNotifications(Booking booking, User renter, Car car) {
        try {
            String carInfo = car.getBrand() + " " + car.getModel();
            String insuranceInfo = booking.getInsuranceType() != null ? booking.getInsuranceType() : "Basic";
            String refuelInfo = booking.isPrepaidRefuel() ? " with prepaid refuel" : "";
            String tripTimeInfo = formatTripTimeInfo(booking.getStartTime(), booking.getEndTime());

            // Notify renter: confirmed
            String renterMessage = String.format("Vaša rezervacija za %s je potvrđena! (%s insurance%s, Period: %s)",
                    carInfo, insuranceInfo, refuelInfo, tripTimeInfo);
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(renter.getId())
                    .type(NotificationType.BOOKING_CONFIRMED)
                    .message(renterMessage)
                    .relatedEntityId("booking-" + booking.getId())
                    .build());

            // Notify owner: confirmed
            String ownerMessage = String.format("Nova rezervacija za %s od %s %s (%s insurance%s, Period: %s)",
                    carInfo, renter.getFirstName(), renter.getLastName(), insuranceInfo, refuelInfo, tripTimeInfo);
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(car.getOwner().getId())
                    .type(NotificationType.BOOKING_CONFIRMED)
                    .message(ownerMessage)
                    .relatedEntityId("booking-" + booking.getId())
                    .build());

            log.debug("Instant booking notifications sent for booking {}", booking.getId());
        } catch (Exception e) {
            log.error("Failed to send instant booking notifications for booking {}: {}",
                    booking.getId(), e.getMessage(), e);
        }
    }

    /**
     * Create chat conversation for instant booking (legacy workflow).
     * Conversation is created immediately for ACTIVE bookings.
     */
    private void createChatConversationForInstantBooking(Booking booking, User renter, Car car) {
        try {
            log.debug("Creating conversation for instant booking {} between renter {} and owner {}",
                    booking.getId(), renter.getId(), car.getOwner().getId());

            // Retrieve token from SecurityContext (set by JwtAuthFilter)
            String token = (String) org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getCredentials();

            chatServiceClient.createConversationAsync(
                    booking.getId().toString(),
                    renter.getId().toString(),
                    car.getOwner().getId().toString(),
                    token
            );
        } catch (Exception e) {
            // Log error but don't fail the booking
            log.error("Failed to initiate conversation creation for instant booking {}: {}",
                    booking.getId(), e.getMessage());
        }
    }

    /**
     * Get detailed booking information for the renter (or owner/admin).
     * Includes rich data about the trip, car, and host.
     * 
     * Security:
     * - RLS-ENFORCED: Only renter, owner, or admin can access.
     * - License Plate Visibility: Only exposed if booking is ACTIVE.
     * 
     * @param id Booking ID
     * @return BookingDetailsDTO
     */
    @Transactional(readOnly = true)
    public org.example.rentoza.booking.dto.BookingDetailsDTO getBookingDetails(Long id) {
        // 1. Fetch booking with relations
        Booking booking = repo.findByIdWithRelations(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + id));

        // 2. Security Check
        Long currentUserId = currentUser.id();
        boolean isRenter = booking.getRenter().getId().equals(currentUserId);
        boolean isOwner = booking.getCar().getOwner().getId().equals(currentUserId);
        boolean isAdmin = currentUser.isAdmin();

        if (!isRenter && !isOwner && !isAdmin) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to view details for booking " + id
            );
        }

        // 3. Fetch Host Stats
        User host = booking.getCar().getOwner();
        // P0-2 FIX: Visibility-filtered host rating (double-blind enforcement)
        Instant visibilityTimeout = Instant.now().minus(14, ChronoUnit.DAYS);
        Double hostRating = reviewRepo.findVisibleAverageRatingForReviewee(
                host.getId(), 
                ReviewDirection.FROM_USER, // Renter reviewing Owner
                visibilityTimeout
        );
        long hostTotalTrips = repo.countByOwnerIdAndStatus(host.getId(), BookingStatus.COMPLETED);

        // 4. Map to DTO
        Car car = booking.getCar();
        
        // License Plate Visibility Rule (Issue 1.2 - Privacy)
        // Guests should NOT see license plate when booking is ACTIVE (up to 48h before trip).
        // Only show when check-in window opens (T-1h before trip start) or later phases.
        // Owner and Admin always see the license plate.
        boolean showLicensePlate = booking.getStatus().isCheckInPhaseOrLater() || isOwner || isAdmin;
        String licensePlate = showLicensePlate ? car.getLicensePlate() : null;

        // 5. Resolve Pickup Location (with car home fallback for legacy bookings)
        Double pickupLat = null;
        Double pickupLon = null;
        String pickupAddress = null;
        String pickupCity = null;
        String pickupZipCode = null;
        boolean pickupEstimated = false;

        if (booking.getPickupLocation() != null && booking.getPickupLocation().hasCoordinates()) {
            // Use agreed pickup location from booking
            pickupLat = booking.getPickupLocation().getLatitude().doubleValue();
            pickupLon = booking.getPickupLocation().getLongitude().doubleValue();
            pickupAddress = booking.getPickupLocation().getAddress();
            pickupCity = booking.getPickupLocation().getCity();
            pickupZipCode = booking.getPickupLocation().getZipCode();
        } else if (car.getLocationGeoPoint() != null && car.getLocationGeoPoint().hasCoordinates()) {
            // Fallback to car's home location for legacy bookings
            pickupLat = car.getLocationGeoPoint().getLatitude().doubleValue();
            pickupLon = car.getLocationGeoPoint().getLongitude().doubleValue();
            pickupAddress = car.getLocationGeoPoint().getAddress();
            pickupCity = car.getLocationGeoPoint().getCity();
            pickupZipCode = car.getLocationGeoPoint().getZipCode();
            pickupEstimated = true; // Mark as estimate for UI indication
        }

        // 6. Calculate variance status for check-in phase
        org.example.rentoza.booking.dto.BookingDetailsDTO.LocationVarianceStatus varianceStatus = 
            calculateVarianceStatus(booking.getPickupLocationVarianceMeters());

        return org.example.rentoza.booking.dto.BookingDetailsDTO.builder()
                // Trip
                .id(booking.getId())
                .status(booking.getStatus())
                .startTime(booking.getStartTime())
                .endTime(booking.getEndTime())
                .totalPrice(booking.getTotalPrice())
                .insuranceType(booking.getInsuranceType())
                .prepaidRefuel(booking.isPrepaidRefuel())
                .cancellationPolicy(car.getCancellationPolicy() != null ? car.getCancellationPolicy().name() : "FLEXIBLE")
                
                // Car
                .carId(car.getId())
                .brand(car.getBrand())
                .model(car.getModel())
                .year(car.getYear())
                .licensePlate(licensePlate)
                .location(car.getLocation())
                .primaryImageUrl(car.getImageUrl())
                .seats(car.getSeats())
                .fuelType(car.getFuelType() != null ? car.getFuelType().name() : null)
                .fuelConsumption(car.getFuelConsumption())
                .transmissionType(car.getTransmissionType() != null ? car.getTransmissionType().name() : null)
                .minRentalDays(car.getMinRentalDays())
                .maxRentalDays(car.getMaxRentalDays())

                // Host
                .hostId(host.getId())
                .hostName(host.getFirstName() + " " + host.getLastName())
                .hostRating(hostRating != null ? hostRating : 0.0)
                .hostTotalTrips((int) hostTotalTrips)
                .hostJoinedDate(host.getCreatedAt().toString()) // ISO format
                .hostAvatarUrl(host.getAvatarUrl())

                // Pickup Location (Phase 2.4)
                .pickupLatitude(pickupLat)
                .pickupLongitude(pickupLon)
                .pickupAddress(pickupAddress)
                .pickupCity(pickupCity)
                .pickupZipCode(pickupZipCode)
                .pickupLocationEstimated(pickupEstimated)
                
                // Variance (Check-in phase)
                .pickupLocationVarianceMeters(booking.getPickupLocationVarianceMeters())
                .varianceStatus(varianceStatus)
                
                // Delivery Info
                .deliveryDistanceKm(booking.getDeliveryDistanceKm())
                .deliveryFeeCalculated(booking.getDeliveryFeeCalculated())
                .build();
    }

    /**
     * Calculate location variance status for check-in display.
     * 
     * @param varianceMeters Distance between agreed pickup and actual car position
     * @return LocationVarianceStatus enum (NONE, WARNING, BLOCKING)
     */
    private org.example.rentoza.booking.dto.BookingDetailsDTO.LocationVarianceStatus calculateVarianceStatus(
            Integer varianceMeters) {
        if (varianceMeters == null) {
            return org.example.rentoza.booking.dto.BookingDetailsDTO.LocationVarianceStatus.NONE;
        }
        if (varianceMeters > 2000) {
            return org.example.rentoza.booking.dto.BookingDetailsDTO.LocationVarianceStatus.BLOCKING;
        }
        if (varianceMeters > 500) {
            return org.example.rentoza.booking.dto.BookingDetailsDTO.LocationVarianceStatus.WARNING;
        }
        return org.example.rentoza.booking.dto.BookingDetailsDTO.LocationVarianceStatus.NONE;
    }

    /**
     * Get guest preview for a booking.
     * RLS-ENFORCED: Only the car owner can view the guest preview.
     * 
     * @param bookingId Booking ID
     * @param requesterId ID of the user requesting the preview
     * @return GuestBookingPreviewDTO with enterprise-grade guest info
     */
    @Transactional(readOnly = true)
    public org.example.rentoza.dto.GuestBookingPreviewDTO getGuestPreview(Long bookingId, Long requesterId) {
        // 1. Fetch booking with relations
        Booking booking = repo.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        // 2. Verify ownership (RLS)
        if (!booking.getCar().getOwner().getId().equals(requesterId) && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized: Only the car owner can view guest details for booking " + bookingId
            );
        }

        // 3. Fetch guest stats
        User renter = booking.getRenter();
        // P0-2 FIX: Visibility-filtered guest rating (double-blind enforcement)
        Instant guestVisTimeout = Instant.now().minus(14, ChronoUnit.DAYS);
        Double averageRating = reviewRepo.findVisibleAverageRatingForReviewee(
                renter.getId(), 
                ReviewDirection.FROM_OWNER,
                guestVisTimeout
        );
        
        // Count completed trips as renter
        long tripCount = repo.countByRenterIdAndStatus(renter.getId(), BookingStatus.COMPLETED);
        
        // Count guest-initiated cancellations for reliability assessment
        long cancelledCount = repo.countByRenterIdAndStatus(renter.getId(), BookingStatus.CANCELLED);

        // 4. Fetch recent visible reviews from hosts (limit 5 for preview)
        // P0-2 FIX: Visibility-filtered guest reviews (double-blind enforcement)
        List<Review> reviews = reviewRepo.findVisibleByRevieweeIdAndDirection(
                renter.getId(), 
                ReviewDirection.FROM_OWNER,
                guestVisTimeout
        ).stream().limit(5).collect(Collectors.toList());

        // 5. Map to DTO with all enterprise-grade fields
        return org.example.rentoza.mapper.GuestBookingMapper.toDTO(
                booking, 
                reviews, 
                averageRating, 
                (int) tripCount,
                (int) cancelledCount
        );
    }
}