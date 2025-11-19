package org.example.rentoza.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.springframework.stereotype.Component;

import org.springframework.transaction.annotation.Transactional;

/**
 * Security service for SpEL-based authorization expressions in @PreAuthorize/@PostAuthorize.
 * Enables declarative access control with custom business logic checks.
 * 
 * <p>Usage example:
 * <pre>
 * &#64;PreAuthorize("@bookingSecurity.canAccessBooking(#id, authentication.principal.id)")
 * public Booking getBookingById(Long id) { ... }
 * </pre>
 * 
 * <p>Why use this pattern:
 * - Declarative security at controller level (more visible than service checks)
 * - Reusable across multiple endpoints
 * - Integrates with Spring Security's expression-based access control
 * - Fails fast before method execution (better performance)
 * 
 * @see org.springframework.security.access.prepost.PreAuthorize
 * @see org.springframework.security.access.expression.SecurityExpressionRoot
 */
@Component("bookingSecurity")
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BookingSecurityService {

    private final BookingRepository bookingRepository;
    private final CurrentUser currentUser;

    /**
     * Checks if the user can access a specific booking.
     * User can access if they are:
     * - The renter (user who made the booking)
     * - The car owner (user who owns the booked car)
     * - An admin (bypasses all checks)
     * 
     * @param bookingId Booking ID to check access for
     * @param userId Authenticated user's ID
     * @return true if user can access the booking
     */
    public boolean canAccessBooking(Long bookingId, Long userId) {
        // Admin bypass
        if (currentUser.isAdmin()) {
            log.debug("Admin access granted for booking {}", bookingId);
            return true;
        }

        // Find booking with relations to prevent LazyInitializationException
        Booking booking = bookingRepository.findByIdWithRelations(bookingId).orElse(null);
        if (booking == null) {
            log.warn("Booking {} not found for access check", bookingId);
            return false;
        }

        // Check ownership: user is renter OR car owner
        boolean isRenter = booking.getRenter().getId().equals(userId);
        boolean isOwner = booking.getCar().getOwner().getId().equals(userId);

        boolean canAccess = isRenter || isOwner;
        
        log.debug("Access check for booking {}: userId={}, isRenter={}, isOwner={}, canAccess={}",
                bookingId, userId, isRenter, isOwner, canAccess);

        return canAccess;
    }

    /**
     * Checks if the user can modify (cancel, update) a booking.
     * Only the renter can modify their booking (owners cannot unilaterally cancel).
     * Admins can modify any booking.
     * 
     * @param bookingId Booking ID to check
     * @param userId Authenticated user's ID
     * @return true if user can modify the booking
     */
    public boolean canModifyBooking(Long bookingId, Long userId) {
        // Admin bypass
        if (currentUser.isAdmin()) {
            return true;
        }

        // Use findByIdWithRelations to ensure renter is loaded
        Booking booking = bookingRepository.findByIdWithRelations(bookingId).orElse(null);
        if (booking == null) {
            return false;
        }

        // Only renter can cancel their booking
        boolean isRenter = booking.getRenter().getId().equals(userId);
        
        log.debug("Modify check for booking {}: userId={}, isRenter={}", bookingId, userId, isRenter);
        
        return isRenter;
    }

    /**
     * Checks if the user can view bookings for a specific car.
     * Only the car owner can view all bookings for their car.
     * 
     * @param carId Car ID
     * @param userId Authenticated user's ID  
     * @return true if user is the car owner or admin
     */
    public boolean canViewCarBookings(Long carId, Long userId) {
        // This will be checked at service layer using repository queries
        // This method is a placeholder for potential controller-level @PreAuthorize
        return true; // Actual check done in service layer with findByCarIdForOwner
    }

    // ========== HOST APPROVAL WORKFLOW SECURITY METHODS ==========

    /**
     * Checks if the user is the owner of the car associated with the booking.
     * Used for approval/decline authorization.
     * 
     * @param bookingId Booking ID
     * @param userId Authenticated user's ID
     * @return true if user is the car owner or admin
     */
    public boolean isOwner(Long bookingId, Long userId) {
        // Admin bypass
        if (currentUser.isAdmin()) {
            log.debug("Admin bypass for isOwner check on booking {}", bookingId);
            return true;
        }

        // Use findByIdWithRelations to ensure car and owner are loaded
        Booking booking = bookingRepository.findByIdWithRelations(bookingId).orElse(null);
        if (booking == null) {
            log.warn("Booking {} not found for isOwner check", bookingId);
            return false;
        }

        boolean isOwner = booking.getCar().getOwner().getId().equals(userId);
        
        log.debug("isOwner check for booking {}: userId={}, carOwnerId={}, isOwner={}",
                bookingId, userId, booking.getCar().getOwner().getId(), isOwner);

        return isOwner;
    }

    /**
     * Alias for isOwner - used for approve/decline operations.
     * Checks if the user can make approval decisions on the booking.
     * 
     * @param bookingId Booking ID
     * @param userId Authenticated user's ID
     * @return true if user can approve/decline (is car owner or admin)
     */
    public boolean canDecide(Long bookingId, Long userId) {
        return isOwner(bookingId, userId);
    }
}
