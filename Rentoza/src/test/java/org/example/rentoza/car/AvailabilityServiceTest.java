package org.example.rentoza.car;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingTimeUtil;
import org.example.rentoza.car.dto.AvailabilitySearchRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AvailabilityService - validates edge cases and business logic
 * Tests do NOT hit the database - all dependencies are mocked
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AvailabilityService Edge Case Tests")
class AvailabilityServiceTest {

    @Mock
    private CarRepository carRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private BookingTimeUtil bookingTimeUtil;

    @InjectMocks
    private AvailabilityService availabilityService;

    private AvailabilitySearchRequestDTO validRequest;

    @BeforeEach
    void setUp() {
        // Create a valid baseline request for testing
        validRequest = AvailabilitySearchRequestDTO.builder()
                .location("Beograd")
                .startDate(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(9, 0))
                .endDate(LocalDate.now().plusDays(3))
                .endTime(LocalTime.of(18, 0))
                .page(0)
                .size(20)
                .build();
    }

    @Test
    @DisplayName("Test Case 1: Past start date should throw IllegalArgumentException")
    void testPastStartDate() {
        // Arrange
        AvailabilitySearchRequestDTO request = AvailabilitySearchRequestDTO.builder()
                .location("Beograd")
                .startDate(LocalDate.now().minusDays(1)) // PAST DATE
                .startTime(LocalTime.of(9, 0))
                .endDate(LocalDate.now().plusDays(1))
                .endTime(LocalTime.of(18, 0))
                .page(0)
                .size(20)
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> request.validate()
        );

        assertTrue(exception.getMessage().contains("Start date cannot be in the past"));
    }

    @Test
    @DisplayName("Test Case 2: End before start should throw IllegalArgumentException")
    void testEndBeforeStart() {
        // Arrange
        AvailabilitySearchRequestDTO request = AvailabilitySearchRequestDTO.builder()
                .location("Beograd")
                .startDate(LocalDate.now().plusDays(3))
                .startTime(LocalTime.of(18, 0))
                .endDate(LocalDate.now().plusDays(1)) // BEFORE START
                .endTime(LocalTime.of(9, 0))
                .page(0)
                .size(20)
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> request.validate()
        );

        assertTrue(exception.getMessage().contains("End date/time must be after start date/time"));
    }

    @Test
    @DisplayName("Test Case 3: Same day but endTime < startTime should throw IllegalArgumentException")
    void testSameDayEndTimeBeforeStartTime() {
        // Arrange
        LocalDate sameDay = LocalDate.now().plusDays(1);
        AvailabilitySearchRequestDTO request = AvailabilitySearchRequestDTO.builder()
                .location("Beograd")
                .startDate(sameDay)
                .startTime(LocalTime.of(18, 0)) // 18:00
                .endDate(sameDay)
                .endTime(LocalTime.of(9, 0)) // 09:00 - BEFORE START TIME
                .page(0)
                .size(20)
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> request.validate()
        );

        assertTrue(exception.getMessage().contains("End date/time must be after start date/time"));
    }

    @Test
    @DisplayName("Test Case 4: Duration under 1 hour should throw IllegalArgumentException")
    void testDurationUnderOneHour() {
        // Arrange
        LocalDate today = LocalDate.now().plusDays(1);
        AvailabilitySearchRequestDTO request = AvailabilitySearchRequestDTO.builder()
                .location("Beograd")
                .startDate(today)
                .startTime(LocalTime.of(9, 0))
                .endDate(today)
                .endTime(LocalTime.of(9, 30)) // Only 30 minutes duration
                .page(0)
                .size(20)
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> request.validate()
        );

        assertTrue(exception.getMessage().contains("Minimum rental duration is 1 hour"));
    }

    @Test
    @DisplayName("Test Case 5: Range > 90 days should throw IllegalArgumentException")
    void testRangeExceeds90Days() {
        // Arrange
        AvailabilitySearchRequestDTO request = AvailabilitySearchRequestDTO.builder()
                .location("Beograd")
                .startDate(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(9, 0))
                .endDate(LocalDate.now().plusDays(91)) // 91 days range
                .endTime(LocalTime.of(18, 0))
                .page(0)
                .size(20)
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> request.validate()
        );

        assertTrue(exception.getMessage().contains("Maximum search range is 90 days"));
    }

    @Test
    @DisplayName("Test Case 6: Empty location should throw IllegalArgumentException")
    void testEmptyLocation() {
        // Arrange
        AvailabilitySearchRequestDTO request = AvailabilitySearchRequestDTO.builder()
                .location("   ") // EMPTY/BLANK
                .startDate(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(9, 0))
                .endDate(LocalDate.now().plusDays(3))
                .endTime(LocalTime.of(18, 0))
                .page(0)
                .size(20)
                .build();

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> request.validate()
        );

        assertTrue(exception.getMessage().contains("Location is required"));
    }

    @Test
    @DisplayName("Test Case 7: Overlapping bookings - car should NOT be available")
    void testOverlappingBookings() {
        // Arrange
        Car car = new Car();
        car.setId(1L);
        car.setAvailable(true);
        car.setLocation("Beograd");

        Booking overlappingBooking = new Booking();
        overlappingBooking.setId(100L);
        overlappingBooking.setStartTime(LocalDateTime.of(LocalDate.now().plusDays(2), LocalTime.of(9, 0)));
        overlappingBooking.setEndTime(LocalDateTime.of(LocalDate.now().plusDays(4), LocalTime.of(18, 0)));

        // Mock repository responses
        when(carRepository.findByLocationIgnoreCaseAndAvailableTrue("beograd"))
                .thenReturn(List.of(car));
        when(bookingRepository.findPublicBookingsForCar(1L))
                .thenReturn(List.of(overlappingBooking));

        // Mock booking time derivation - booking overlaps with request
        when(bookingTimeUtil.derivePickupDateTime(overlappingBooking))
                .thenReturn(LocalDateTime.of(LocalDate.now().plusDays(2), LocalTime.of(9, 0)));
        when(bookingTimeUtil.deriveDropoffDateTime(overlappingBooking))
                .thenReturn(LocalDateTime.of(LocalDate.now().plusDays(4), LocalTime.of(18, 0)));

        // Act
        validRequest.validate(); // Should pass validation
        Page<Car> results = availabilityService.searchAvailableCars(validRequest);

        // Assert - Car should NOT be in results due to overlap
        assertEquals(0, results.getTotalElements(), "Car should be filtered out due to booking overlap");
        verify(carRepository).findByLocationIgnoreCaseAndAvailableTrue("beograd");
        verify(bookingRepository).findPublicBookingsForCar(1L);
    }

    @Test
    @DisplayName("Test Case 8: Booking ends exactly when request starts - car should be available")
    void testBookingEndsExactlyWhenRequestStarts() {
        // Arrange
        Car car = new Car();
        car.setId(1L);
        car.setAvailable(true);
        car.setLocation("Beograd");

        Booking nonOverlappingBooking = new Booking();
        nonOverlappingBooking.setId(100L);
        nonOverlappingBooking.setStartTime(LocalDateTime.of(LocalDate.now().minusDays(2), LocalTime.of(9, 0)));
        nonOverlappingBooking.setEndTime(LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(18, 0)));

        // Mock repository responses
        when(carRepository.findByLocationIgnoreCaseAndAvailableTrue("beograd"))
                .thenReturn(List.of(car));
        when(bookingRepository.findPublicBookingsForCar(1L))
                .thenReturn(List.of(nonOverlappingBooking));

        // Booking ends EXACTLY when request starts - NO OVERLAP
        LocalDateTime requestStart = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(9, 0));
        when(bookingTimeUtil.derivePickupDateTime(nonOverlappingBooking))
                .thenReturn(LocalDateTime.of(LocalDate.now().minusDays(2), LocalTime.of(9, 0)));
        when(bookingTimeUtil.deriveDropoffDateTime(nonOverlappingBooking))
                .thenReturn(requestStart); // ENDS EXACTLY WHEN REQUEST STARTS

        // Act
        validRequest.validate();
        Page<Car> results = availabilityService.searchAvailableCars(validRequest);

        // Assert - Car should be available (no overlap)
        assertEquals(1, results.getTotalElements(), "Car should be available when booking ends exactly when request starts");
        assertEquals(car.getId(), results.getContent().get(0).getId());
    }

    @Test
    @DisplayName("Test Case 9: Booking starts exactly when request ends - car should be available")
    void testBookingStartsExactlyWhenRequestEnds() {
        // Arrange
        Car car = new Car();
        car.setId(1L);
        car.setAvailable(true);
        car.setLocation("Beograd");

        Booking nonOverlappingBooking = new Booking();
        nonOverlappingBooking.setId(100L);
        nonOverlappingBooking.setStartTime(LocalDateTime.of(LocalDate.now().plusDays(3), LocalTime.of(18, 0)));
        nonOverlappingBooking.setEndTime(LocalDateTime.of(LocalDate.now().plusDays(5), LocalTime.of(18, 0)));

        // Mock repository responses
        when(carRepository.findByLocationIgnoreCaseAndAvailableTrue("beograd"))
                .thenReturn(List.of(car));
        when(bookingRepository.findPublicBookingsForCar(1L))
                .thenReturn(List.of(nonOverlappingBooking));

        // Booking starts EXACTLY when request ends - NO OVERLAP
        LocalDateTime requestEnd = LocalDateTime.of(LocalDate.now().plusDays(3), LocalTime.of(18, 0));
        when(bookingTimeUtil.derivePickupDateTime(nonOverlappingBooking))
                .thenReturn(requestEnd); // STARTS EXACTLY WHEN REQUEST ENDS
        when(bookingTimeUtil.deriveDropoffDateTime(nonOverlappingBooking))
                .thenReturn(LocalDateTime.of(LocalDate.now().plusDays(5), LocalTime.of(18, 0)));

        // Act
        validRequest.validate();
        Page<Car> results = availabilityService.searchAvailableCars(validRequest);

        // Assert - Car should be available (no overlap)
        assertEquals(1, results.getTotalElements(), "Car should be available when booking starts exactly when request ends");
        assertEquals(car.getId(), results.getContent().get(0).getId());
    }

    @Test
    @DisplayName("Test Case 10: No bookings - car should be available")
    void testNoBookings() {
        // Arrange
        Car car = new Car();
        car.setId(1L);
        car.setAvailable(true);
        car.setLocation("Beograd");

        // Mock repository responses - NO BOOKINGS
        when(carRepository.findByLocationIgnoreCaseAndAvailableTrue("beograd"))
                .thenReturn(List.of(car));
        when(bookingRepository.findPublicBookingsForCar(1L))
                .thenReturn(Collections.emptyList()); // NO BOOKINGS

        // Act
        validRequest.validate();
        Page<Car> results = availabilityService.searchAvailableCars(validRequest);

        // Assert - Car should be available
        assertEquals(1, results.getTotalElements(), "Car with no bookings should be available");
        assertEquals(car.getId(), results.getContent().get(0).getId());
        verify(bookingRepository).findPublicBookingsForCar(1L);
    }
}
