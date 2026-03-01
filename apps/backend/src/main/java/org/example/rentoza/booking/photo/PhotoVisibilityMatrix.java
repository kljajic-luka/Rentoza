package org.example.rentoza.booking.photo;

import org.example.rentoza.booking.BookingStatus;
import org.springframework.stereotype.Component;

/**
 * P0-1 FIX: Photo visibility matrix that defines who can see which photos at each booking status.
 * 
 * <p>This is the single source of truth for photo access rules. It prevents implicit
 * authorization bugs by making visibility rules explicit and testable.
 * 
 * <h2>Visibility Rules</h2>
 * 
 * | Booking Status | Host Sees | Guest Sees |
 * |---|---|---|
 * | CHECK_IN_OPEN | Own photos | Nothing |
 * | CHECK_IN_HOST_COMPLETE | All check-in | Host photos |
 * | CHECK_IN_COMPLETE | All photos | All photos |
 * | IN_TRIP | All photos | All photos |
 * | CHECKOUT_OPEN | All photos | Own photos |
 * | CHECKOUT_GUEST_COMPLETE | All photos | All photos |
 * | CHECKOUT_COMPLETE | All photos | All photos |
 */
@Component
public class PhotoVisibilityMatrix {

    /**
     * Check if a user can view a specific photo based on booking status and photo metadata.
     *
     * @param status Current booking status
     * @param userId ID of user requesting access
     * @param hostId ID of booking host
     * @param guestId ID of booking guest
     * @param phase "CHECK_IN" or "CHECK_OUT"
     * @param capturedBy "HOST" or "GUEST"
     * @param isAdmin true if the requesting user has the ADMIN role
     * @return true if access is permitted
     */
    public boolean canViewPhoto(BookingStatus status, Long userId, Long hostId, Long guestId,
                                String phase, String capturedBy, boolean isAdmin) {

        if (isAdmin) {
            return true;
        }

        // Determine if requesting user is host or guest
        boolean isHost = userId.equals(hostId);
        boolean isGuest = userId.equals(guestId);
        
        if (!isHost && !isGuest) {
            return false; // User not involved in booking
        }
        
        // Apply visibility rules based on status
        return switch (status) {
            // CHECK_IN PHASES
            case CHECK_IN_OPEN -> {
                // Only host can see their own photos during initial upload
                yield isHost && "CHECK_IN".equals(phase) && "HOST".equals(capturedBy);
            }
            
            case CHECK_IN_HOST_COMPLETE -> {
                // Host sees all check-in photos
                // Guest sees host photos (for comparison)
                if (isHost) {
                    yield "CHECK_IN".equals(phase);
                } else {
                    yield "CHECK_IN".equals(phase) && "HOST".equals(capturedBy);
                }
            }
            
            case CHECK_IN_COMPLETE -> {
                // Both host and guest see all check-in photos
                yield "CHECK_IN".equals(phase);
            }
            
            // TRIP PHASE
            case IN_TRIP -> {
                // Both parties see all check-in photos (read-only reference)
                yield "CHECK_IN".equals(phase);
            }
            
            // CHECKOUT PHASES
            case CHECKOUT_OPEN -> {
                // Guest can see their own checkout photos + all check-in
                // Host cannot see guest checkout yet
                if (isGuest) {
                    yield "CHECK_IN".equals(phase) || "CHECKOUT".equals(phase);
                } else {
                    yield "CHECK_IN".equals(phase);
                }
            }
            
            case CHECKOUT_GUEST_COMPLETE -> {
                // Both see all photos (guest checkout ready, host about to verify)
                yield true;
            }
            
            case CHECKOUT_HOST_COMPLETE -> {
                // Both see all photos (checkout complete)
                yield true;
            }
            
            case COMPLETED -> {
                // Both see all photos (final state, trip finished)
                yield true;
            }
            
            // Other terminal/error states
            case CANCELLED -> {
                // Both parties can access all photos in cancelled state for dispute resolution
                yield true;
            }
            
            // No-show and other states
            case NO_SHOW_HOST, NO_SHOW_GUEST, EXPIRED, EXPIRED_SYSTEM, PENDING_APPROVAL,
                 ACTIVE, APPROVED, PENDING_CHECKOUT, DECLINED -> {
                // These are not relevant for photo access
                yield false;
            }
            
            // Paranoid default
            default -> false;
        };
    }

    /**
     * Check if a user can see the full list of photos for a booking.
     * Used for batch endpoints like /api/bookings/{id}/photos
     *
     * @param status Booking status
     * @param userId User ID
     * @param hostId Host ID
     * @param guestId Guest ID
     * @param isAdmin true if the requesting user has the ADMIN role
     * @return true if user can list photos for this booking
     */
    public boolean canListPhotos(BookingStatus status, Long userId, Long hostId, Long guestId, boolean isAdmin) {
        if (isAdmin) {
            return true;
        }

        boolean isHost = userId.equals(hostId);
        boolean isGuest = userId.equals(guestId);
        
        if (!isHost && !isGuest) {
            return false;
        }
        
        // Host can list photos from CHECK_IN_OPEN onwards
        if (isHost) {
            return status.ordinal() >= BookingStatus.CHECK_IN_OPEN.ordinal();
        }
        
        // Guest can list photos from CHECK_IN_HOST_COMPLETE onwards
        return status.ordinal() >= BookingStatus.CHECK_IN_HOST_COMPLETE.ordinal();
    }
}
