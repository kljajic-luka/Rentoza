package org.example.rentoza.booking;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.booking.dto.UserBookingResponseDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.chat.ChatServiceClient;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.review.Review;
import org.example.rentoza.review.ReviewDirection;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.hibernate.Hibernate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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
    private final JwtUtil jwtUtil;
    private final ChatServiceClient chatServiceClient;
    private final NotificationService notificationService;
    private final org.example.rentoza.security.CurrentUser currentUser;

    public BookingService(BookingRepository repo, CarRepository carRepo, UserRepository userRepo,
                          ReviewRepository reviewRepo, JwtUtil jwtUtil, ChatServiceClient chatServiceClient,
                          NotificationService notificationService, org.example.rentoza.security.CurrentUser currentUser) {
        this.repo = repo;
        this.carRepo = carRepo;
        this.userRepo = userRepo;
        this.reviewRepo = reviewRepo;
        this.jwtUtil = jwtUtil;
        this.chatServiceClient = chatServiceClient;
        this.notificationService = notificationService;
        this.currentUser = currentUser;
    }

    @Transactional
    public Booking createBooking(BookingRequestDTO dto, String authHeader) {
        String token = authHeader.substring(7);
        String renterEmail = jwtUtil.getEmailFromToken(token);

        User renter = userRepo.findByEmail(renterEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Validate age requirement
        if (renter.getAge() == null || renter.getAge() < 21) {
            throw new RuntimeException("Drivers must be at least 21 years old to rent a car.");
        }

        Car car = carRepo.findById(dto.getCarId())
                .orElseThrow(() -> new RuntimeException("Car not found"));

        Booking booking = new Booking();
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStartDate(dto.getStartDate());
        booking.setEndDate(dto.getEndDate());
        booking.setStatus(BookingStatus.ACTIVE);

        // Set insurance type and prepaid refuel
        booking.setInsuranceType(dto.getInsuranceType() != null ? dto.getInsuranceType() : "BASIC");
        booking.setPrepaidRefuel(dto.isPrepaidRefuel());

        // Phase 2.2: Set pickup time window and exact time (if applicable)
        booking.setPickupTimeWindow(dto.getPickupTimeWindow() != null ? dto.getPickupTimeWindow() : "MORNING");
        booking.setPickupTime(dto.getPickupTime()); // Nullable - only set when EXACT window selected

        // Calculate total price with insurance and refuel
        long days = ChronoUnit.DAYS.between(dto.getStartDate(), dto.getEndDate());
        double basePrice = days * car.getPricePerDay();

        // Apply insurance multiplier
        double insuranceMultiplier = switch (booking.getInsuranceType().toUpperCase()) {
            case "STANDARD" -> 1.10;
            case "PREMIUM" -> 1.20;
            default -> 1.00;
        };

        // Calculate refuel cost if applicable
        double refuelCost = 0.0;
        if (booking.isPrepaidRefuel() && car.getFuelConsumption() != null) {
            // Approximate: fuelConsumption * 6.5 (EUR/L) * 10 (assumed liters per rental)
            refuelCost = car.getFuelConsumption() * 6.5 * 10;
        }

        booking.setTotalPrice(basePrice * insuranceMultiplier + refuelCost);

        Booking savedBooking = repo.save(booking);

        // Initialize car and owner to avoid lazy loading issues
        Hibernate.initialize(savedBooking.getCar());
        Hibernate.initialize(savedBooking.getCar().getOwner());

        // Create conversation in chat microservice asynchronously
        try {
            log.info("Creating conversation for booking {} between renter {} and owner {}",
                    savedBooking.getId(), renter.getId(), car.getOwner().getId());

            chatServiceClient.createConversationAsync(
                    savedBooking.getId().toString(),
                    renter.getId().toString(),
                    car.getOwner().getId().toString(),
                    token
            );
        } catch (Exception e) {
            // Log error but don't fail the booking
            log.error("Failed to initiate conversation creation for booking {}: {}",
                    savedBooking.getId(), e.getMessage());
        }

        // Send booking confirmed notifications to both renter and owner
        try {
            String carInfo = car.getBrand() + " " + car.getModel();
            String insuranceInfo = savedBooking.getInsuranceType() != null ? savedBooking.getInsuranceType() : "Basic";
            String refuelInfo = savedBooking.isPrepaidRefuel() ? " with prepaid refuel" : "";
            
            // Phase 2.2: Format pickup time information
            String pickupTimeInfo = formatPickupTimeInfo(savedBooking.getPickupTimeWindow(), savedBooking.getPickupTime());

            // Notify renter
            String renterMessage = String.format("Vaša rezervacija za %s je potvrđena! (%s insurance%s, Preuzimanje: %s)",
                    carInfo, insuranceInfo, refuelInfo, pickupTimeInfo);
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(renter.getId())
                    .type(NotificationType.BOOKING_CONFIRMED)
                    .message(renterMessage)
                    .relatedEntityId("booking-" + savedBooking.getId())
                    .build());

            // Notify owner
            String ownerMessage = String.format("Nova rezervacija za %s od %s %s (%s insurance%s, Preuzimanje: %s)",
                    carInfo, renter.getFirstName(), renter.getLastName(), insuranceInfo, refuelInfo, pickupTimeInfo);
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(car.getOwner().getId())
                    .type(NotificationType.BOOKING_CONFIRMED)
                    .message(ownerMessage)
                    .relatedEntityId("booking-" + savedBooking.getId())
                    .build());

            log.info("Booking confirmed notifications sent for booking {}", savedBooking.getId());
        } catch (Exception e) {
            log.error("Failed to send booking confirmed notifications: {}", e.getMessage(), e);
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
     * - Only includes ACTIVE/CONFIRMED bookings (filters out cancelled/pending)
     * - Does NOT require authentication or ownership verification
     * - Compliant with RLS: no sensitive data exposure
     * 
     * @param carId Car ID to fetch booking slots for
     * @return List of BookingSlotDTO with only date ranges
     */
    public List<org.example.rentoza.booking.dto.BookingSlotDTO> getPublicBookedSlots(Long carId) {
        // Fetch bookings for the car
        List<Booking> bookings = repo.findByCarId(carId);
        
        // Map to public-safe DTO with only date ranges
        // Filter to only ACTIVE bookings (exclude CANCELLED, PENDING)
        return bookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.ACTIVE )
                .map(org.example.rentoza.booking.dto.BookingSlotDTO::new)
                .toList();
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

    /**
     * Cancel booking with ownership verification.
     * RLS-ENFORCED: Only the renter can cancel their booking.
     * 
     * @param id Booking ID
     * @return Cancelled booking
     * @throws RuntimeException if booking not found
     * @throws org.springframework.security.access.AccessDeniedException if user is not the renter
     */
    @Transactional
    public Booking cancelBooking(Long id) {
        var booking = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        
        // RLS ENFORCEMENT: Only renter can cancel (owners can't unilaterally cancel confirmed bookings)
        Long userId = currentUser.id();
        if (!booking.getRenter().getId().equals(userId) && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Unauthorized to cancel booking " + id + ": only the renter can cancel"
            );
        }
        
        booking.setStatus(BookingStatus.CANCELLED);

        // Force initialize before leaving the transaction
        Hibernate.initialize(booking.getCar());
        Hibernate.initialize(booking.getRenter());
        Hibernate.initialize(booking.getCar().getOwner());

        // Send booking cancelled notifications to both renter and owner
        try {
            Car car = booking.getCar();
            User renter = booking.getRenter();
            String carInfo = car.getBrand() + " " + car.getModel();

            // Notify renter
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(renter.getId())
                    .type(NotificationType.BOOKING_CANCELLED)
                    .message("Vaša rezervacija za " + carInfo + " je otkazana.")
                    .relatedEntityId("booking-" + booking.getId())
                    .build());

            // Notify owner
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(car.getOwner().getId())
                    .type(NotificationType.BOOKING_CANCELLED)
                    .message("Rezervacija za " + carInfo + " od " + renter.getFirstName() + " " + renter.getLastName() + " je otkazana.")
                    .relatedEntityId("booking-" + booking.getId())
                    .build());

            log.info("Booking cancelled notifications sent for booking {}", booking.getId());
        } catch (Exception e) {
            log.error("Failed to send booking cancelled notifications: {}", e.getMessage(), e);
        }

        return booking;
    }

    @Transactional
    public List<UserBookingResponseDTO> getMyBookings(String authHeader) {
        String token = authHeader.substring(7);
        String userEmail = jwtUtil.getEmailFromToken(token);

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
                            booking.getStartDate(),
                            booking.getEndDate(),
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
        LocalDate today = LocalDate.now();
        List<Booking> overdueBookings = repo.findOverdueBookings(today);

        if (!overdueBookings.isEmpty()) {
            log.info("Auto-completing {} overdue bookings", overdueBookings.size());

            for (Booking booking : overdueBookings) {
                booking.setStatus(BookingStatus.COMPLETED);
                log.debug("Auto-completed booking ID {} (end date: {})", booking.getId(), booking.getEndDate());
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
            || (booking.getEndDate() != null && booking.getEndDate().isBefore(LocalDate.now()));
    }

    /**
     * Phase 2.2: Format pickup time window and exact time for notifications and display
     * 
     * @param pickupTimeWindow The pickup time window (MORNING, AFTERNOON, EVENING, EXACT)
     * @param pickupTime The exact pickup time (only for EXACT window)
     * @return Formatted pickup time string in Serbian
     */
    private String formatPickupTimeInfo(String pickupTimeWindow, java.time.LocalTime pickupTime) {
        if (pickupTimeWindow == null) {
            pickupTimeWindow = "MORNING";
        }

        return switch (pickupTimeWindow.toUpperCase()) {
            case "MORNING" -> "Jutro (08:00 – 12:00)";
            case "AFTERNOON" -> "Popodne (12:00 – 16:00)";
            case "EVENING" -> "Veče (16:00 – 20:00)";
            case "EXACT" -> pickupTime != null ? pickupTime.toString() : "Tačno vreme (nije navedeno)";
            default -> "Jutro (08:00 – 12:00)";
        };
    }

    /**
     * Phase 2.3: Check if a booking can be made for the given date range
     * Validates availability without persisting the booking (used for pre-submit validation)
     * 
     * @param dto The booking request containing car ID and date range
     * @return true if dates are available, false if there's a conflict
     */
    public boolean checkAvailability(BookingRequestDTO dto) {
        // Get all confirmed bookings for the car in the given date range
        List<Booking> existingBookings = repo.findByCarIdAndDateRange(
                dto.getCarId(),
                dto.getStartDate(),
                dto.getEndDate()
        );

        // If any overlapping bookings exist, dates are not available
        return existingBookings.isEmpty();
    }
}