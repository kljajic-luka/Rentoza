package org.example.rentoza.owner;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.owner.dto.HostCancellationStatsDTO;
import org.example.rentoza.owner.dto.OwnerEarningsDTO;
import org.example.rentoza.owner.dto.OwnerPayoutsDTO;
import org.example.rentoza.owner.dto.OwnerStatsDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for owner-specific operations
 */
@RestController
@RequestMapping("/api/owner")
@RequiredArgsConstructor
public class OwnerController {

    private final OwnerService ownerService;

    /**
     * Get owner dashboard statistics
     * GET /api/owner/stats/{email}
     */
    @GetMapping("/stats/{email}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> getOwnerStats(
            @PathVariable String email,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            // Verify the authenticated user can only access their own data (unless ADMIN)
            if (!principal.isAdmin() && !principal.getUsername().equalsIgnoreCase(email)) {
                return ResponseEntity.status(403).body(Map.of("error", "Unauthorized to access other owner's statistics"));
            }

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
    public ResponseEntity<?> getOwnerBookings(
            @PathVariable String email,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            // Verify the authenticated user can only access their own data (unless ADMIN)
            if (!principal.isAdmin() && !principal.getUsername().equalsIgnoreCase(email)) {
                return ResponseEntity.status(403).body(Map.of("error", "Unauthorized to access other owner's bookings"));
            }

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
    public ResponseEntity<?> getOwnerEarnings(
            @PathVariable String email,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        try {
            // Verify the authenticated user can only access their own data (unless ADMIN)
            if (!principal.isAdmin() && !principal.getUsername().equalsIgnoreCase(email)) {
                return ResponseEntity.status(403).body(Map.of("error", "Unauthorized to access other owner's earnings"));
            }

            OwnerEarningsDTO earnings = ownerService.getOwnerEarnings(email);
            return ResponseEntity.ok(earnings);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get host cancellation statistics for the authenticated owner.
     * 
     * <p>Returns penalty tier, suspension status, and next penalty amount.
     * Used by frontend to:
     * <ul>
     *   <li>Display cancellation history on dashboard</li>
     *   <li>Show warning before cancellation confirmation</li>
     *   <li>Indicate suspension status (if applicable)</li>
     * </ul>
     * 
     * <p><b>Security:</b> Returns only the authenticated user's stats.
     * 
     * <p><b>New Hosts:</b> Returns zero-valued DTO if no cancellation history exists.
     * 
     * GET /api/owner/cancellation-stats
     * 
     * @param principal Authenticated owner
     * @return HostCancellationStatsDTO with tier info, suspension status, next penalty
     */
    @GetMapping("/cancellation-stats")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<HostCancellationStatsDTO> getCancellationStats(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        HostCancellationStatsDTO stats = ownerService.getHostCancellationStats(principal.getId());
        return ResponseEntity.ok(stats);
    }

    /**
     * Get payout statuses for the authenticated owner's bookings.
     *
     * <p>Returns per-booking payout lifecycle information including:
     * <ul>
     *   <li>Payout status (PENDING, ELIGIBLE, PROCESSING, COMPLETED, FAILED, etc.)</li>
     *   <li>Fee breakdown (trip amount, platform fee, host net payout)</li>
     *   <li>Eligibility date (after dispute hold window)</li>
     *   <li>Retry metadata (attempt count, next retry, last error)</li>
     * </ul>
     *
     * <p><b>Security:</b> Returns only the authenticated owner's payouts.
     *
     * GET /api/owner/payouts
     *
     * @param principal Authenticated owner
     * @return OwnerPayoutsDTO with per-booking payout status list
     */
    @GetMapping("/payouts")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<OwnerPayoutsDTO> getOwnerPayouts(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.rentoza.security.JwtUserPrincipal principal
    ) {
        OwnerPayoutsDTO payouts = ownerService.getOwnerPayouts(principal.getId());
        return ResponseEntity.ok(payouts);
    }


}