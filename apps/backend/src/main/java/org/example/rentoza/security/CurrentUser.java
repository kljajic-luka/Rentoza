package org.example.rentoza.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Centralized utility for extracting authenticated user information from SecurityContext.
 * Eliminates manual JWT token parsing and header extraction across services.
 * 
 * <p>This component provides type-safe, consistent access to:
 * - User ID (for ownership validation)
 * - Email (for logging and lookups)
 * - Role checks (for admin bypass logic)
 * 
 * <p>Usage in services:
 * <pre>
 * &#64;Service
 * public class BookingService {
 *     private final CurrentUser currentUser;
 *     
 *     public Booking getBookingById(Long id) {
 *         Long userId = currentUser.id();
 *         // ... ownership validation
 *     }
 * }
 * </pre>
 * 
 * <p>Security enforcement strategy:
 * - Throws AccessDeniedException if authentication is missing or invalid
 * - Fails securely by default (no silent null returns)
 * - Supports admin bypass checks for privileged operations
 * 
 * @see JwtUserPrincipal
 * @see JwtAuthFilter
 */
@Component
public class CurrentUser {

    /**
     * Gets the authenticated user's database ID.
     * 
     * @return User ID from SecurityContext
     * @throws AccessDeniedException if user is not authenticated or principal is invalid
     */
    public Long id() {
        return getPrincipal()
                .map(JwtUserPrincipal::id)
                .orElseThrow(() -> new AccessDeniedException("User ID not available in SecurityContext"));
    }

    /**
     * Gets the authenticated user's database ID, or null if not authenticated.
     * 
     * <p>Use this method when anonymous access is allowed and you need to
     * conditionally apply logic based on authentication status (e.g., privacy filtering).
     * 
     * @return User ID from SecurityContext, or null if not authenticated
     */
    public Long idOrNull() {
        return getPrincipal()
                .map(JwtUserPrincipal::id)
                .orElse(null);
    }

    /**
     * Gets the authenticated user's email.
     * 
     * @return User email from SecurityContext
     * @throws AccessDeniedException if user is not authenticated or principal is invalid
     */
    public String email() {
        return getPrincipal()
                .map(JwtUserPrincipal::email)
                .orElseThrow(() -> new AccessDeniedException("User email not available in SecurityContext"));
    }

    /**
     * Checks if the authenticated user has ADMIN role.
     * Use this for admin bypass logic in service methods.
     * 
     * @return true if user has ADMIN role, false otherwise
     */
    public boolean isAdmin() {
        return getPrincipal()
                .map(JwtUserPrincipal::isAdmin)
                .orElse(false);
    }

    /**
     * Checks if the authenticated user has a specific role.
     * 
     * @param role Role name without ROLE_ prefix (e.g., "OWNER", "USER")
     * @return true if user has the specified role
     */
    public boolean hasRole(String role) {
        return getPrincipal()
                .map(principal -> principal.hasRole(role))
                .orElse(false);
    }

    /**
     * Gets the full JwtUserPrincipal if needed for complex operations.
     * 
     * @return Optional containing the principal, or empty if not authenticated
     */
    public Optional<JwtUserPrincipal> getPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        
        Object principal = authentication.getPrincipal();
        if (principal instanceof JwtUserPrincipal jwtPrincipal) {
            return Optional.of(jwtPrincipal);
        }
        
        return Optional.empty();
    }

    /**
     * Verifies that the authenticated user owns the specified entity.
     * Throws AccessDeniedException if ownership check fails.
     * 
     * @param ownerId The ID of the user who owns the entity
     * @param entityType Human-readable entity type for error messages (e.g., "booking", "car")
     * @throws AccessDeniedException if user is not the owner and not an admin
     */
    public void verifyOwnership(Long ownerId, String entityType) {
        Long currentUserId = id();
        
        if (!currentUserId.equals(ownerId) && !isAdmin()) {
            // SECURITY (L-5): Do not leak user IDs in exception messages
            throw new AccessDeniedException(
                    "Unauthorized to access %s: ownership verification failed"
                            .formatted(entityType)
            );
        }
    }

    /**
     * Verifies that the authenticated user matches one of the provided user IDs.
     * Useful for entities with multiple ownership scenarios (e.g., booking has both renter and owner).
     * 
     * @param allowedUserIds List of user IDs that can access the entity
     * @param entityType Human-readable entity type for error messages
     * @throws AccessDeniedException if user ID is not in the allowed list and not an admin
     */
    public void verifyOwnership(java.util.List<Long> allowedUserIds, String entityType) {
        Long currentUserId = id();
        
        if (!allowedUserIds.contains(currentUserId) && !isAdmin()) {
            // SECURITY (L-5): Do not leak user IDs or allowed lists in exception messages
            throw new AccessDeniedException(
                    "Unauthorized to access %s: ownership verification failed"
                            .formatted(entityType)
            );
        }
    }
}
