package org.example.rentoza.owner;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.owner.dto.OwnerEarningsDTO;
import org.example.rentoza.owner.dto.OwnerStatsDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for owner-specific operations
 */
@RestController
@RequestMapping("/api/owner")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201"})
public class OwnerController {

    private final OwnerService ownerService;

    /**
     * Get owner dashboard statistics
     * GET /api/owner/stats/{email}
     */
    @GetMapping("/stats/{email}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<OwnerStatsDTO> getOwnerStats(@PathVariable String email) {
        try {
            OwnerStatsDTO stats = ownerService.getOwnerStats(email);
            return ResponseEntity.ok(stats);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get all bookings for owner's cars
     * GET /api/owner/bookings/{email}
     */
    @GetMapping("/bookings/{email}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<BookingResponseDTO>> getOwnerBookings(@PathVariable String email) {
        try {
            List<BookingResponseDTO> bookings = ownerService.getOwnerBookings(email);
            return ResponseEntity.ok(bookings);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get owner earnings breakdown
     * GET /api/owner/earnings/{email}
     */
    @GetMapping("/earnings/{email}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<OwnerEarningsDTO> getOwnerEarnings(@PathVariable String email) {
        try {
            OwnerEarningsDTO earnings = ownerService.getOwnerEarnings(email);
            return ResponseEntity.ok(earnings);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
