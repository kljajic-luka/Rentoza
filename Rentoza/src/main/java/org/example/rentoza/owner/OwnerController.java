package org.example.rentoza.owner;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.owner.dto.OwnerEarningsDTO;
import org.example.rentoza.owner.dto.OwnerStatsDTO;
import org.example.rentoza.security.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final JwtUtil jwtUtil;

    /**
     * Get owner dashboard statistics
     * GET /api/owner/stats/{email}
     */
    @GetMapping("/stats/{email}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> getOwnerStats(
            @PathVariable String email,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            // Extract authenticated user email
            String authenticatedEmail = getAuthenticatedEmail(authHeader);

            // Verify the authenticated user can only access their own data (unless ADMIN)
            if (!isAdmin() && !authenticatedEmail.equalsIgnoreCase(email)) {
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
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            // Extract authenticated user email
            String authenticatedEmail = getAuthenticatedEmail(authHeader);

            // Verify the authenticated user can only access their own data (unless ADMIN)
            if (!isAdmin() && !authenticatedEmail.equalsIgnoreCase(email)) {
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
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            // Extract authenticated user email
            String authenticatedEmail = getAuthenticatedEmail(authHeader);

            // Verify the authenticated user can only access their own data (unless ADMIN)
            if (!isAdmin() && !authenticatedEmail.equalsIgnoreCase(email)) {
                return ResponseEntity.status(403).body(Map.of("error", "Unauthorized to access other owner's earnings"));
            }

            OwnerEarningsDTO earnings = ownerService.getOwnerEarnings(email);
            return ResponseEntity.ok(earnings);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Helper method to extract authenticated user email from JWT token
     */
    private String getAuthenticatedEmail(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Missing or invalid Authorization header");
        }
        String token = authHeader.substring(7);
        return jwtUtil.getEmailFromToken(token);
    }

    /**
     * Helper method to check if the authenticated user has ADMIN role
     */
    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(grantedAuthority -> grantedAuthority.getAuthority().equals("ROLE_ADMIN"));
    }
}
