package org.example.rentoza.booking.dto;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BookingConversationDTO mapper.
 * 
 * Tests canonical contract enforcement:
 * - Date-first trip status computation (FUTURE/CURRENT/PAST/UNAVAILABLE)
 * - Messaging allowed only for ACTIVE bookings
 * - Image extraction (imageUrls[0] > imageUrl fallback > null)
 * - No PENDING/CONFIRMED references (removed from enum)
 */
class BookingConversationDTOTest {

    @Test
    void testFutureTripWithActiveBooking() {
        // Given: ACTIVE booking starting tomorrow
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDate nextWeek = LocalDate.now().plusDays(7);
        
        Booking booking = createTestBooking(BookingStatus.ACTIVE, tomorrow, nextWeek);
        
        // When: Creating DTO
        BookingConversationDTO dto = new BookingConversationDTO(booking);
        
        // Then: Should have FUTURE trip status and messaging allowed
        assertThat(dto.tripStatus()).isEqualTo("FUTURE");
        assertThat(dto.messagingAllowed()).isTrue();
        assertThat(dto.bookingStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void testCurrentTripWithActiveBooking() {
        // Given: ACTIVE booking happening now
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        
        Booking booking = createTestBooking(BookingStatus.ACTIVE, yesterday, tomorrow);
        
        // When: Creating DTO
        BookingConversationDTO dto = new BookingConversationDTO(booking);
        
        // Then: Should have CURRENT trip status and messaging allowed
        assertThat(dto.tripStatus()).isEqualTo("CURRENT");
        assertThat(dto.messagingAllowed()).isTrue();
        assertThat(dto.bookingStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void testPastTripWithActiveBooking() {
        // Given: ACTIVE booking ended yesterday (edge case - should be auto-completed)
        LocalDate lastWeek = LocalDate.now().minusDays(7);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        
        Booking booking = createTestBooking(BookingStatus.ACTIVE, lastWeek, yesterday);
        
        // When: Creating DTO
        BookingConversationDTO dto = new BookingConversationDTO(booking);
        
        // Then: Should have PAST trip status (date-first logic)
        assertThat(dto.tripStatus()).isEqualTo("PAST");
        assertThat(dto.messagingAllowed()).isTrue(); // Still ACTIVE status
    }

    @Test
    void testCompletedBooking() {
        // Given: COMPLETED booking in the past
        LocalDate lastMonth = LocalDate.now().minusDays(30);
        LocalDate lastWeek = LocalDate.now().minusDays(7);
        
        Booking booking = createTestBooking(BookingStatus.COMPLETED, lastMonth, lastWeek);
        
        // When: Creating DTO
        BookingConversationDTO dto = new BookingConversationDTO(booking);
        
        // Then: Should have PAST trip status and messaging disabled
        assertThat(dto.tripStatus()).isEqualTo("PAST");
        assertThat(dto.messagingAllowed()).isFalse();
        assertThat(dto.bookingStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void testCancelledBookingFutureDate() {
        // Given: CANCELLED booking with future dates
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDate nextWeek = LocalDate.now().plusDays(7);
        
        Booking booking = createTestBooking(BookingStatus.CANCELLED, tomorrow, nextWeek);
        
        // When: Creating DTO
        BookingConversationDTO dto = new BookingConversationDTO(booking);
        
        // Then: Date-first logic still computes FUTURE, but messaging disabled
        assertThat(dto.tripStatus()).isEqualTo("FUTURE");
        assertThat(dto.messagingAllowed()).isFalse();
        assertThat(dto.bookingStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void testCancelledBookingPastDate() {
        // Given: CANCELLED booking with past dates
        LocalDate lastMonth = LocalDate.now().minusDays(30);
        LocalDate lastWeek = LocalDate.now().minusDays(7);
        
        Booking booking = createTestBooking(BookingStatus.CANCELLED, lastMonth, lastWeek);
        
        // When: Creating DTO
        BookingConversationDTO dto = new BookingConversationDTO(booking);
        
        // Then: Should have PAST trip status and messaging disabled
        assertThat(dto.tripStatus()).isEqualTo("PAST");
        assertThat(dto.messagingAllowed()).isFalse();
        assertThat(dto.bookingStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void testMissingDatesReturnsUnavailable() {
        // Given: Booking with null dates (edge case)
        Booking booking = createTestBooking(BookingStatus.ACTIVE, null, null);
        
        // When: Creating DTO
        BookingConversationDTO dto = new BookingConversationDTO(booking);
        
        // Then: Should return UNAVAILABLE trip status
        assertThat(dto.tripStatus()).isEqualTo("UNAVAILABLE");
        assertThat(dto.messagingAllowed()).isTrue(); // ACTIVE status
    }

    @Test
    void testCarImagePriorityImageUrls() {
        // Given: Car with both imageUrls (new) and imageUrl (legacy)
        Booking booking = createTestBooking(BookingStatus.ACTIVE, 
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(7));
        
        List<String> imageUrls = new ArrayList<>();
        imageUrls.add("https://cdn.example.com/car1.jpg");
        imageUrls.add("https://cdn.example.com/car2.jpg");
        booking.getCar().setImageUrls(imageUrls);
        booking.getCar().setImageUrl("data:image/png;base64,legacy..."); // Legacy base64
        
        // When: Creating DTO
        BookingConversationDTO dto = new BookingConversationDTO(booking);
        
        // Then: Should prioritize imageUrls[0] over legacy imageUrl
        assertThat(dto.carImageUrl()).isEqualTo("https://cdn.example.com/car1.jpg");
    }

    @Test
    void testCarImageFallbackToLegacy() {
        // Given: Car with only legacy imageUrl
        Booking booking = createTestBooking(BookingStatus.ACTIVE, 
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(7));
        
        booking.getCar().setImageUrls(new ArrayList<>()); // Empty list
        booking.getCar().setImageUrl("https://legacy.example.com/car.jpg");
        
        // When: Creating DTO
        BookingConversationDTO dto = new BookingConversationDTO(booking);
        
        // Then: Should use legacy imageUrl
        assertThat(dto.carImageUrl()).isEqualTo("https://legacy.example.com/car.jpg");
    }

    @Test
    void testCarImageNullWhenBothEmpty() {
        // Given: Car with no images
        Booking booking = createTestBooking(BookingStatus.ACTIVE, 
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(7));
        
        booking.getCar().setImageUrls(new ArrayList<>());
        booking.getCar().setImageUrl(null);
        
        // When: Creating DTO
        BookingConversationDTO dto = new BookingConversationDTO(booking);
        
        // Then: Should be null
        assertThat(dto.carImageUrl()).isNull();
    }

    @Test
    void testCarDetailsMapping() {
        // Given: Booking with car details
        Booking booking = createTestBooking(BookingStatus.ACTIVE, 
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(7));
        
        booking.getCar().setBrand("BMW");
        booking.getCar().setModel("X5");
        booking.getCar().setYear(2020);
        
        // When: Creating DTO
        BookingConversationDTO dto = new BookingConversationDTO(booking);
        
        // Then: Should map car details correctly
        assertThat(dto.carBrand()).isEqualTo("BMW");
        assertThat(dto.carModel()).isEqualTo("X5");
        assertThat(dto.carYear()).isEqualTo(2020);
        assertThat(dto.carId()).isEqualTo(1L);
    }

    @Test
    void testComputeTripStatusStaticMethod() {
        // Test the public static helper method
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDate nextWeek = LocalDate.now().plusDays(7);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate lastWeek = LocalDate.now().minusDays(7);
        
        assertThat(BookingConversationDTO.computeTripStatusFromDates(tomorrow, nextWeek))
                .isEqualTo("FUTURE");
        
        assertThat(BookingConversationDTO.computeTripStatusFromDates(yesterday, tomorrow))
                .isEqualTo("CURRENT");
        
        assertThat(BookingConversationDTO.computeTripStatusFromDates(lastWeek, yesterday))
                .isEqualTo("PAST");
        
        assertThat(BookingConversationDTO.computeTripStatusFromDates(null, nextWeek))
                .isEqualTo("UNAVAILABLE");
        
        assertThat(BookingConversationDTO.computeTripStatusFromDates(tomorrow, null))
                .isEqualTo("UNAVAILABLE");
    }

    // Helper method to create test booking
    private Booking createTestBooking(BookingStatus status, LocalDate startDate, LocalDate endDate) {
        User renter = new User();
        renter.setId(100L);
        
        User owner = new User();
        owner.setId(200L);
        
        Car car = new Car();
        car.setId(1L);
        car.setBrand("Toyota");
        car.setModel("Camry");
        car.setYear(2022);
        car.setOwner(owner);
        car.setImageUrls(new ArrayList<>());
        car.setImageUrl(null);
        
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setStatus(status);
        booking.setStartDate(startDate);
        booking.setEndDate(endDate);
        booking.setRenter(renter);
        booking.setCar(car);
        
        return booking;
    }
}
