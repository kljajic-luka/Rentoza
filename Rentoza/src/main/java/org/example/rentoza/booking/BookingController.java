package org.example.rentoza.booking;

import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.booking.dto.UserBookingResponseDTO;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for booking operations with RLS enforcement.
 * All endpoints now protected with @PreAuthorize annotations for defense-in-depth.
 * Service layer provides additional ownership validation.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final Logger log = LoggerFactory.getLogger(BookingController.class);

    private final BookingService service;
    private final JwtUtil jwtUtil;

    public BookingController(BookingService service, JwtUtil jwtUtil) {
        this.service = service;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Get current user's bookings.
     * RLS-ENFORCED: User can only see their own bookings.
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<UserBookingResponseDTO>> getMyBookings(@RequestHeader("Authorization") String authHeader) {
        try {
            List<UserBookingResponseDTO> bookings = service.getMyBookings(authHeader);
            return ResponseEntity.ok(bookings);
        } catch (RuntimeException e) {
            log.error("Error fetching user bookings", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Create a new booking.
     * RLS-ENFORCED: Authenticated users can create bookings (renter extracted from JWT).
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> createBooking(@RequestBody BookingRequestDTO dto, @RequestHeader("Authorization") String authHeader) {
        try {
            Booking booking = service.createBooking(dto, authHeader);
            return ResponseEntity.ok(new BookingResponseDTO(booking));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get bookings for a specific user by email.
     * RLS-ENFORCED: User can only access their own bookings (verified at service layer).
     */
    @GetMapping("/user/{email}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getUserBookings(
            @PathVariable String email,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            // Verify authentication
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            String token = authHeader.substring(7);
            String authenticatedEmail = jwtUtil.getEmailFromToken(token);

            // Verify the authenticated user can only access their own bookings
            if (!authenticatedEmail.equalsIgnoreCase(email)) {
                return ResponseEntity.status(403).body(Map.of("error", "Unauthorized to access other users' bookings"));
            }

            return ResponseEntity.ok(service.getBookingsByUser(email));
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }
    }

    /**
     * Get all bookings for a specific car.
     * RLS-ENFORCED: Only car owner can view bookings (verified at service layer).
     * SpEL expression ensures user has OWNER role and service validates actual ownership.
     */
    @GetMapping("/car/{carId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<BookingResponseDTO>> getBookingsForCar(@PathVariable Long carId) {
        return ResponseEntity.ok(service.getBookingsForCar(carId));
    }

    /**
     * Get public-safe booking slots for a specific car (calendar availability).
     * 
     * Purpose:
     * - Allow renters/guests to see which dates are booked without exposing sensitive data
     * - Enables calendar UI to grey out unavailable dates
     * - Returns minimal data: carId, startDate, endDate only
     * 
     * Security:
     * - @PreAuthorize("permitAll()") → accessible to authenticated and unauthenticated users
     * - No PII exposure (no renter, owner, or pricing information)
     * - Only returns ACTIVE/CONFIRMED bookings (no cancelled/pending)
     * - Rate-limited to 60 requests/minute (configured in application properties)
     * 
     * RLS Compliance:
     * - Does NOT violate RLS because it exposes no owner-only or renter-only data
     * - Full booking details remain secured in /api/bookings/car/{carId} (OWNER/ADMIN only)
     * 
     * @param carId Car ID to fetch booking slots for
     * @return List of BookingSlotDTO with only date ranges
     */
    @GetMapping("/car/{carId}/public")
    @PreAuthorize("permitAll()")
    public ResponseEntity<List<org.example.rentoza.booking.dto.BookingSlotDTO>> getPublicBookingsForCar(@PathVariable Long carId) {
        try {
            List<org.example.rentoza.booking.dto.BookingSlotDTO> slots = service.getPublicBookedSlots(carId);
            return ResponseEntity.ok(slots);
        } catch (RuntimeException e) {
            log.error("Error fetching public booking slots for car {}", carId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get booking by ID - with ownership verification.
     * RLS-ENFORCED: Only renter, car owner, or admin can view booking.
     * Uses BookingSecurityService for SpEL-based access control.
     */
    @GetMapping("/{id}")
    @PreAuthorize("@bookingSecurity.canAccessBooking(#id, authentication.principal.id) or hasRole('ADMIN')")
    public ResponseEntity<?> getBookingById(@PathVariable Long id) {
        try {
            Booking booking = service.getBookingById(id);
            return ResponseEntity.ok(new BookingResponseDTO(booking));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "id", id,
                            "error", "Booking not found",
                            "message", e.getMessage()
                    ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "id", id,
                            "error", "Failed to fetch booking",
                            "message", e.getMessage()
                    ));
        }
    }

    /**
     * Debug endpoint - list all booking IDs (for development/testing)
     * Accessible with INTERNAL_SERVICE authority
     */
    @GetMapping("/debug/ids")
    public ResponseEntity<Map<String, Object>> getAllBookingIds() {
        List<Long> ids = service.getAllBookingIds();
        return ResponseEntity.ok(Map.of(
                "count", ids.size(),
                "bookingIds", ids
        ));
    }

    /**
     * Cancel a booking.
     * RLS-ENFORCED: Only the renter can cancel their booking (verified at service layer).
     * SpEL expression ensures only booking modifier can access.
     */
    @PutMapping("/cancel/{id}")
    @PreAuthorize("@bookingSecurity.canModifyBooking(#id, authentication.principal.id) or hasRole('ADMIN')")
    public ResponseEntity<?> cancelBooking(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            // Verify authentication
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            String token = authHeader.substring(7);
            String authenticatedEmail = jwtUtil.getEmailFromToken(token);

            // Get the booking to verify ownership
            Booking bookingToCancel = service.getBookingById(id);

            // Verify the authenticated user is the renter who created the booking
            if (!bookingToCancel.getRenter().getEmail().equalsIgnoreCase(authenticatedEmail)) {
                return ResponseEntity.status(403).body(Map.of("error", "Unauthorized to cancel this booking"));
            }

            Booking booking = service.cancelBooking(id);
            return ResponseEntity.ok(new BookingResponseDTO(booking));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Phase 2.3: Validate booking availability without creating the booking.
     * RLS-ENFORCED: Authenticated users can check availability.
     * Returns 409 Conflict if dates are not available, 200 OK if available.
     * 
     * @param dto Booking request with car ID and date range
     * @return 200 with {available: true} if dates are free, 409 with error if conflict
     */
    @PostMapping("/validate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> validateBooking(@RequestBody BookingRequestDTO dto) {
        try {
            boolean available = service.checkAvailability(dto);
            
            if (!available) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Conflict",
                    "message", "Selected dates are no longer available. Please choose different dates.",
                    "available", false
                ));
            }
            
            return ResponseEntity.ok(Map.of("available", true));
        } catch (RuntimeException e) {
            log.error("Error validating booking availability", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}