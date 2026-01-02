package org.example.rentoza.booking;

import org.springframework.transaction.annotation.Transactional;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BookingService {

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

    @org.springframework.beans.factory.annotation.Value("${app.booking.host-approval.enabled:false}")
    private boolean approvalEnabled;

    @org.springframework.beans.factory.annotation.Value("${app.booking.host-approval.beta-users:}")
    private java.util.List<Long> betaUsers;

    @org.springframework.beans.factory.annotation.Value("${app.booking.host-approval.expiry-hours:48}")
    private int expiryHours;
    
    @org.springframework.beans.factory.annotation.Value("${app.renter-verification.license-required:true}")
    private boolean licenseRequired;

    public BookingService(BookingRepository repo, CarRepository carRepo, UserRepository userRepo,
                          ReviewRepository reviewRepo, ChatServiceClient chatServiceClient,
                          NotificationService notificationService, org.example.rentoza.security.CurrentUser currentUser,
                          CancellationPolicyService cancellationPolicyService,
                          DeliveryFeeCalculator deliveryFeeCalculator,
                          RenterVerificationService renterVerificationService) {
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
    }

    @Transactional
    public Booking createBooking(BookingRequestDTO dto, String renterEmail) {

        User renter = userRepo.findByEmail(renterEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Validate age requirement
        if (renter.getAge() == null || renter.getAge() < 21) {
            throw new RuntimeException("Drivers must be at least 21 years old to rent a car.");
        }

        Car car = carRepo.findById(dto.getCarId())
                .orElseThrow(() -> new RuntimeException("Car not found"));

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
        // Reject booking if trip starts within 1 hour from now.
        // Rationale: Hosts need minimum time to respond, and last-minute bookings
        // create poor UX (auto-expiry would trigger almost immediately).
        LocalDateTime tripStartDateTime = dto.getStartTime();
        LocalDateTime oneHourFromNow = LocalDateTime.now().plusHours(1);
        
        if (tripStartDateTime.isBefore(oneHourFromNow)) {
            throw new org.example.rentoza.exception.ValidationException(
                    "Booking cannot be created less than 1 hour before trip start time. " +
                    "Please select a later start date."
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

        // Determine initial status based on feature flag
        BookingStatus initialStatus;
        if (approvalEnabled || (betaUsers != null && betaUsers.contains(renter.getId()))) {
            initialStatus = BookingStatus.PENDING_APPROVAL;
            
            // ========================================================================
            // DYNAMIC DEADLINE CALCULATION (Logic Matrix Implementation)
            // ========================================================================
            // Formula: MIN(Now + 48h, TripStartTime - 1h)
            // 
            // This ensures:
            // 1. Standard case: Host gets up to 48 hours to respond
            // 2. Short notice: Host response window shrinks to ensure guest has at least
            //    1 hour between approval and trip start
            // 
            // Examples:
            // - Trip in 72h: deadline = now + 48h (standard)
            // - Trip in 36h: deadline = now + 35h (buffer-constrained)
            // - Trip in 6h:  deadline = now + 5h  (buffer-constrained)
            java.time.LocalDateTime standardDeadline = java.time.LocalDateTime.now().plusHours(expiryHours);
            java.time.LocalDateTime bufferDeadline = tripStartDateTime.minusHours(1);
            
            java.time.LocalDateTime effectiveDeadline = standardDeadline.isBefore(bufferDeadline) 
                    ? standardDeadline 
                    : bufferDeadline;
            
            booking.setDecisionDeadlineAt(effectiveDeadline);
            
            log.debug("Booking created with PENDING_APPROVAL status. " +
                    "Deadline calculation: standardDeadline={}, bufferDeadline={}, effectiveDeadline={}",
                    standardDeadline, bufferDeadline, effectiveDeadline);
        } else {
            initialStatus = BookingStatus.ACTIVE;
            // Backfill approval metadata for instant bookings
            booking.setApprovedAt(java.time.LocalDateTime.now());
            booking.setPaymentStatus("AUTHORIZED");
            log.debug("Booking created with ACTIVE status (instant booking - legacy mode)");
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
        // PRICE CALCULATION WITH BigDecimal (Financial Precision)
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

        // Final calculation with proper rounding (includes delivery fee)
        BigDecimal totalPrice = basePrice
                .multiply(insuranceMultiplier)
                .add(refuelCost)
                .add(deliveryFee)
                .setScale(2, RoundingMode.HALF_UP);
        
        booking.setTotalPrice(totalPrice);
        
        log.debug("Price calculated: hours={}, periods={}, basePrice={}, multiplier={}, refuel={}, delivery={}, total={}",
                hours, periods, basePrice, insuranceMultiplier, refuelCost, deliveryFee, totalPrice);

        // ========================================================================
        // CANCELLATION POLICY: Snapshot Daily Rate at Booking Time
        // ========================================================================
        // CRITICAL: Lock the car's daily rate at booking creation.
        // This ensures penalty calculations use the rate agreed upon at booking,
        // even if the owner changes the price before a potential cancellation.
        // 
        // Used by: TuroCancellationPolicyService.calculateGuestCancellation()
        // Immutable: This value should NEVER be updated after booking creation.
        booking.setSnapshotDailyRate(car.getPricePerDay());

        Booking savedBooking = repo.save(booking);

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

        return savedBooking;
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
     * @return List of all booking IDs
     */
    public List<Long> getAllBookingIds() {
        // RLS ENFORCEMENT: Admin-only operation
        if (!currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized: Only admins can access all booking IDs"
            );
        }
        
        return repo.findAll().stream()
                .map(Booking::getId)
                .collect(Collectors.toList());
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
     * - messagingAllowed: true for PENDING/ACTIVE/CONFIRMED, false for CANCELLED/COMPLETED
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
        
        // Send notifications
        sendCancellationNotifications(booking, result);
        
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
    @Scheduled(cron = "0 0 * * * *") // Every hour at minute 0
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
        Double hostRating = reviewRepo.findAverageRatingForRevieweeAndDirection(
                host.getId(), 
                ReviewDirection.FROM_USER // Renter reviewing Owner
        );
        long hostTotalTrips = repo.countByOwnerIdAndStatus(host.getId(), BookingStatus.COMPLETED);

        // 4. Map to DTO
        Car car = booking.getCar();
        
        // License Plate Visibility Rule
        // Exposed if: (Status is ACTIVE) AND (User is Renter OR Owner OR Admin)
        // Note: Owner/Admin always see it in other views, but for this specific DTO logic:
        boolean showLicensePlate = (booking.getStatus() == BookingStatus.ACTIVE) || isOwner || isAdmin;
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
        Double averageRating = reviewRepo.findAverageRatingForRevieweeAndDirection(
                renter.getId(), 
                ReviewDirection.FROM_OWNER
        );
        
        // Count completed trips as renter
        long tripCount = repo.countByRenterIdAndStatus(renter.getId(), BookingStatus.COMPLETED);
        
        // Count guest-initiated cancellations for reliability assessment
        long cancelledCount = repo.countByRenterIdAndStatus(renter.getId(), BookingStatus.CANCELLED);

        // 4. Fetch recent reviews from hosts (limit 5 for preview)
        List<Review> reviews = reviewRepo.findByRevieweeIdAndDirectionOrderByCreatedAtDesc(
                renter.getId(), 
                ReviewDirection.FROM_OWNER
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