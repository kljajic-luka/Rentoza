package org.example.rentoza.booking.photo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.CheckInPhoto;
import org.example.rentoza.booking.checkin.GuestCheckInPhoto;
import org.example.rentoza.booking.checkout.HostCheckoutPhoto;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.Optional;

/**
 * P0-1 FIX: Centralized photo access authorization service.
 * 
 * <p>Ensures that any authenticated user cannot access any photo just by knowing the session ID.
 * All photo serving must verify the requesting user is a participant in the booking.
 * 
 * <h2>Authorization Rules</h2>
 * <ul>
 *   <li>Host photos: Only host or guest (when status permits)</li>
 *   <li>Guest photos: Only guest or host (when status permits)</li>
 *   <li>Checkout photos: Only parties involved</li>
 *   <li>Status checks ensure photos are visible at the right time</li>
 * </ul>
 * 
 * @see PhotoVisibilityMatrix for detailed visibility rules
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoAccessService {

    private final BookingRepository bookingRepository;
    private final PhotoVisibilityMatrix visibilityMatrix;

    /**
     * Simplified version: check if user can access any photo in a booking.
     * Full authorization is handled by PhotoVisibilityMatrix.
     * 
     * @param bookingId Booking ID
     * @param userId Current user ID
     * @throws AccessDeniedException if user cannot access this booking
     * @throws ResourceNotFoundException if booking not found
     */
    public void authorizePhotoAccess(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
        
        // Verify user is a participant
        if (!canUserAccessBooking(bookingId, userId)) {
            throw new AccessDeniedException("Niste učesnik u ovoj rezervaciji");
        }
    }



    /**
     * Check if a user can access any photo in a given booking.
     * Generic check for batch operations.
     * 
     * @param bookingId Booking ID
     * @param userId Current user ID
     * @return true if user is a participant in the booking
     */
    public boolean canUserAccessBooking(Long bookingId, Long userId) {
        Optional<Booking> booking = bookingRepository.findById(bookingId);
        if (booking.isEmpty()) {
            return false;
        }
        
        Long hostId = booking.get().getCar().getOwner().getId();
        Long guestId = booking.get().getRenter().getId();
        
        return userId.equals(hostId) || userId.equals(guestId);
    }
}
