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
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @GetMapping("/me")
    public ResponseEntity<List<UserBookingResponseDTO>> getMyBookings(@RequestHeader("Authorization") String authHeader) {
        try {
            List<UserBookingResponseDTO> bookings = service.getMyBookings(authHeader);
            return ResponseEntity.ok(bookings);
        } catch (RuntimeException e) {
            log.error("Error fetching user bookings", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping
    public ResponseEntity<?> createBooking(@RequestBody BookingRequestDTO dto, @RequestHeader("Authorization") String authHeader) {
        try {
            Booking booking = service.createBooking(dto, authHeader);
            return ResponseEntity.ok(new BookingResponseDTO(booking));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/user/{email}")
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

    @GetMapping("/car/{carId}")
    public ResponseEntity<List<BookingResponseDTO>> getBookingsForCar(@PathVariable Long carId) {
        return ResponseEntity.ok(service.getBookingsForCar(carId));
    }

    /**
     * Get booking by ID - for internal service communication
     * Accessible with INTERNAL_SERVICE authority
     * Returns full booking details with eagerly loaded relationships
     */
    @GetMapping("/{id}")
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

    @PutMapping("/cancel/{id}")
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
}