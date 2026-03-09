package org.example.rentoza.booking.checkin;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.security.JwtUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

@Component("checkInAuthorization")
@RequiredArgsConstructor
public class CheckInAuthorization {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String INTERNAL_SERVICE = "INTERNAL_SERVICE";

    private final BookingRepository bookingRepository;

    public boolean canAccessStatus(Long bookingId, Authentication authentication) {
        return hasElevatedAccess(authentication) || isParticipant(bookingId, authentication);
    }

    public boolean canManageHostCheckIn(Long bookingId, Authentication authentication) {
        return hasElevatedAccess(authentication) || isHost(bookingId, authentication);
    }

    public boolean canAcknowledgeGuestCondition(Long bookingId, Authentication authentication) {
        return hasElevatedAccess(authentication) || isGuest(bookingId, authentication);
    }

    public boolean canConfirmHandshake(Long bookingId, Authentication authentication) {
        return hasElevatedAccess(authentication) || isParticipant(bookingId, authentication);
    }

    public boolean canRevealLockbox(Long bookingId, Authentication authentication) {
        return hasElevatedAccess(authentication) || isGuest(bookingId, authentication);
    }

    private boolean isParticipant(Long bookingId, Authentication authentication) {
        Booking booking = findBooking(bookingId);
        Long userId = extractUserId(authentication);
        if (booking == null || userId == null) {
            return false;
        }
        return booking.getCar().getOwner().getId().equals(userId)
                || booking.getRenter().getId().equals(userId);
    }

    private boolean isHost(Long bookingId, Authentication authentication) {
        Booking booking = findBooking(bookingId);
        Long userId = extractUserId(authentication);
        return booking != null
                && userId != null
                && booking.getCar().getOwner().getId().equals(userId);
    }

    private boolean isGuest(Long bookingId, Authentication authentication) {
        Booking booking = findBooking(bookingId);
        Long userId = extractUserId(authentication);
        return booking != null
                && userId != null
                && booking.getRenter().getId().equals(userId);
    }

    private Booking findBooking(Long bookingId) {
        return bookingRepository.findByIdWithRelations(bookingId).orElse(null);
    }

    private boolean hasElevatedAccess(Authentication authentication) {
        return hasAuthority(authentication, ROLE_ADMIN) || hasAuthority(authentication, INTERNAL_SERVICE);
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null
                && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private Long extractUserId(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof JwtUserPrincipal jwtUserPrincipal) {
            return jwtUserPrincipal.id();
        }
        return null;
    }
}