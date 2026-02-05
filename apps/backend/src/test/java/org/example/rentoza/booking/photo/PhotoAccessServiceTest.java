//package org.example.rentoza.booking.photo;
//
//import org.example.rentoza.booking.Booking;
//import org.example.rentoza.booking.BookingRepository;
//import org.example.rentoza.booking.BookingStatus;
//import org.example.rentoza.booking.checkin.CheckInPhoto;
//import org.example.rentoza.car.Car;
//import org.example.rentoza.user.User;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.security.access.AccessDeniedException;
//
//import java.util.Optional;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.when;
//
///**
// * P0-1 Security Test Suite: Photo Access Authorization
// *
// * Verifies that unauthorized users cannot access photos from bookings they're not part of.
// */
//@ExtendWith(MockitoExtension.class)
//public class PhotoAccessServiceTest {
//
//    @Mock
//    private BookingRepository bookingRepository;
//
//    @Mock
//    private PhotoVisibilityMatrix visibilityMatrix;
//
//    private PhotoAccessService photoAccessService;
//
//    private static final Long HOST_USER_ID = 100L;
//    private static final Long GUEST_USER_ID = 200L;
//    private static final Long ATTACKER_USER_ID = 999L;
//    private static final Long BOOKING_ID = 1L;
//    private static final Long CAR_ID = 10L;
//
//    @BeforeEach
//    void setUp() {
//        photoAccessService = new PhotoAccessService(bookingRepository, visibilityMatrix);
//    }
//
//    /**
//     * P0-1 Test 1: Host can access their own check-in photos
//     */
//    @Test
//    void shouldAllowHostToAccessOwnCheckInPhotos() {
//        // Given
//        Booking booking = createMockBooking(HOST_USER_ID, GUEST_USER_ID, BookingStatus.CHECK_IN_COMPLETE);
//        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
//        when(visibilityMatrix.canViewPhoto(
//                BookingStatus.CHECK_IN_COMPLETE, HOST_USER_ID, HOST_USER_ID, GUEST_USER_ID, "CHECK_IN", "HOST"))
//                .thenReturn(true);
//
//        // When / Then - Should not throw exception
//        assertDoesNotThrow(() ->
//                photoAccessService.authorizeHostCheckInPhotoAccess(BOOKING_ID, HOST_USER_ID, createMockPhoto())
//        );
//    }
//
//    /**
//     * P0-1 Test 2: Guest can access host photos during check-in
//     */
//    @Test
//    void shouldAllowGuestToAccessHostPhotosAtAppropriateStatus() {
//        // Given
//        Booking booking = createMockBooking(HOST_USER_ID, GUEST_USER_ID, BookingStatus.CHECK_IN_HOST_COMPLETE);
//        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
//        when(visibilityMatrix.canViewPhoto(
//                BookingStatus.CHECK_IN_HOST_COMPLETE, GUEST_USER_ID, HOST_USER_ID, GUEST_USER_ID, "CHECK_IN", "HOST"))
//                .thenReturn(true);
//
//        // When / Then
//        assertDoesNotThrow(() ->
//                photoAccessService.authorizeHostCheckInPhotoAccess(BOOKING_ID, GUEST_USER_ID, createMockPhoto())
//        );
//    }
//
//    /**
//     * P0-1 Test 3: Unauthorized user CANNOT access photos (CRITICAL SECURITY TEST)
//     */
//    @Test
//    void shouldDenyUnauthorizedUserAccessToPhotos() {
//        // Given - Attacker is not part of booking
//        Booking booking = createMockBooking(HOST_USER_ID, GUEST_USER_ID, BookingStatus.CHECK_IN_COMPLETE);
//        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
//
//        // When / Then - Should throw AccessDeniedException
//        AccessDeniedException exception = assertThrows(AccessDeniedException.class, () ->
//                photoAccessService.authorizeHostCheckInPhotoAccess(BOOKING_ID, ATTACKER_USER_ID, createMockPhoto())
//        );
//
//        // Verify error message indicates user is not a participant
//        assertTrue(exception.getMessage().contains("participant") || exception.getMessage().contains("Niste"));
//    }
//
//    /**
//     * P0-1 Test 4: Guest cannot access photos when status doesn't permit it
//     */
//    @Test
//    void shouldDenyAccessWhenBookingStatusDoesNotAllowViewing() {
//        // Given
//        Booking booking = createMockBooking(HOST_USER_ID, GUEST_USER_ID, BookingStatus.CHECK_IN_OPEN);
//        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
//        when(visibilityMatrix.canViewPhoto(
//                BookingStatus.CHECK_IN_OPEN, GUEST_USER_ID, HOST_USER_ID, GUEST_USER_ID, "CHECK_IN", "HOST"))
//                .thenReturn(false);
//
//        // When / Then - Should throw AccessDeniedException (not visible in this status)
//        assertThrows(AccessDeniedException.class, () ->
//                photoAccessService.authorizeHostCheckInPhotoAccess(BOOKING_ID, GUEST_USER_ID, createMockPhoto())
//        );
//    }
//
//    /**
//     * P0-1 Test 5: Generic canUserAccessBooking check
//     */
//    @Test
//    void shouldReturnTrueIfUserIsBookingParticipant() {
//        // Given
//        Booking booking = createMockBooking(HOST_USER_ID, GUEST_USER_ID, BookingStatus.IN_TRIP);
//        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
//
//        // When
//        boolean canHostAccess = photoAccessService.canUserAccessBooking(BOOKING_ID, HOST_USER_ID);
//        boolean canGuestAccess = photoAccessService.canUserAccessBooking(BOOKING_ID, GUEST_USER_ID);
//        boolean canAttackerAccess = photoAccessService.canUserAccessBooking(BOOKING_ID, ATTACKER_USER_ID);
//
//        // Then
//        assertTrue(canHostAccess, "Host should be able to access booking");
//        assertTrue(canGuestAccess, "Guest should be able to access booking");
//        assertFalse(canAttackerAccess, "Non-participant should NOT be able to access booking");
//    }
//
//    /**
//     * P0-1 Test 6: Booking not found returns false (safe default)
//     */
//    @Test
//    void shouldReturnFalseIfBookingNotFound() {
//        // Given
//        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.empty());
//
//        // When
//        boolean canAccess = photoAccessService.canUserAccessBooking(BOOKING_ID, HOST_USER_ID);
//
//        // Then
//        assertFalse(canAccess, "Should deny access if booking doesn't exist");
//    }
//
//    // ========== Helper Methods ==========
//
//    private Booking createMockBooking(Long hostId, Long guestId, BookingStatus status) {
//        Booking booking = new Booking();
//        booking.setId(BOOKING_ID);
//        booking.setStatus(status);
//
//        Car car = new Car();
//        car.setId(CAR_ID);
//        User owner = new User();
//        owner.setId(hostId);
//        car.setOwner(owner);
//        booking.setCar(car);
//
//        User renter = new User();
//        renter.setId(guestId);
//        booking.setRenter(renter);
//
//        return booking;
//    }
//
//    private CheckInPhoto createMockPhoto() {
//        CheckInPhoto photo = new CheckInPhoto();
//        photo.setId(1L);
//        photo.setPhase("CHECK_IN");
//        photo.setCapturedBy("HOST");
//        return photo;
//    }
//}
