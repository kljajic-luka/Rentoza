package org.example.rentoza.booking;

import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
@CrossOrigin(origins = "*")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> createBooking(@RequestBody BookingRequestDTO dto, @RequestHeader("Authorization") String authHeader) {
        try {
            Booking booking = service.createBooking(dto, authHeader);
            return ResponseEntity.ok(new BookingResponseDTO(
                    booking.getId(),
                    booking.getCar().getId(),
                    booking.getRenter().getEmail(),
                    booking.getStartDate(),
                    booking.getEndDate(),
                    booking.getTotalPrice(),
                    booking.getStatus().name()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/user/{email}")
    public ResponseEntity<List<Booking>> getUserBookings(@PathVariable String email) {
        return ResponseEntity.ok(service.getBookingsByUser(email));
    }

    @GetMapping("/car/{carId}")
    public ResponseEntity<List<BookingResponseDTO>> getBookingsForCar(@PathVariable Long carId) {
        return ResponseEntity.ok(service.getBookingsForCar(carId));
    }

    @PutMapping("/cancel/{id}")
    public ResponseEntity<?> cancelBooking(@PathVariable Long id) {
        try {
            Booking booking = service.cancelBooking(id);
            return ResponseEntity.ok(new BookingResponseDTO(
                    booking.getId(),
                    booking.getCar().getId(),
                    booking.getRenter().getEmail(),
                    booking.getStartDate(),
                    booking.getEndDate(),
                    booking.getTotalPrice(),
                    booking.getStatus().name()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}