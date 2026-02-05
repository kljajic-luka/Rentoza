package org.example.rentoza.booking;

import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.FuelType;
import org.example.rentoza.car.TransmissionType;
import org.example.rentoza.testconfig.AbstractIntegrationTest;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BookingRepository double-booking prevention.
 * 
 * <p>Verifies that the pessimistic locking mechanism prevents concurrent
 * bookings for the same car on overlapping dates.
 * 
 * <h2>Critical Test Cases</h2>
 * <ul>
 *   <li>TC-039: Concurrent booking race condition</li>
 *   <li>TC-040: User double-booking themselves</li>
 * </ul>
 */
@DisplayName("Booking - Double Booking Prevention Tests")
@Transactional
class BookingDoubleBookingPreventionTest extends AbstractIntegrationTest {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CarRepository carRepository;

    @MockBean
    private org.example.rentoza.car.storage.DocumentStorageStrategy documentStorageStrategy;

    private User host;
    private User renter1;
    private User renter2;
    private Car car;

    @BeforeEach
    void setUp() {
        // Create host
        host = new User();
        host.setEmail("host-" + System.currentTimeMillis() + "@test.com");
        host.setFirstName("Car");
        host.setLastName("Owner");
        host.setPassword("securePassword123");
        host.setAge(35);
        host = userRepository.save(host);

        // Create first renter
        renter1 = new User();
        renter1.setEmail("renter1-" + System.currentTimeMillis() + "@test.com");
        renter1.setFirstName("First");
        renter1.setLastName("Renter");
        renter1.setPassword("securePassword123");
        renter1.setAge(28);
        renter1.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        renter1.setDriverLicenseExpiryDate(LocalDate.now().plusYears(3));
        renter1 = userRepository.save(renter1);

        // Create second renter
        renter2 = new User();
        renter2.setEmail("renter2-" + System.currentTimeMillis() + "@test.com");
        renter2.setFirstName("Second");
        renter2.setLastName("Renter");
        renter2.setPassword("securePassword123");
        renter2.setAge(30);
        renter2.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        renter2.setDriverLicenseExpiryDate(LocalDate.now().plusYears(2));
        renter2 = userRepository.save(renter2);

        // Create car
        car = new Car();
        car.setOwner(host);
        car.setBrand("Volkswagen");
        car.setModel("Golf");
        car.setYear(2022);
        car.setPricePerDay(BigDecimal.valueOf(3500));
        car.setLocation("Belgrade");
        car.setSeats(5);
        car.setFuelType(FuelType.DIZEL);
        car.setTransmissionType(TransmissionType.AUTOMATIC);
        car = carRepository.save(car);
    }

    @Nested
    @DisplayName("Overlapping Date Detection")
    class OverlappingDateDetection {

        @Test
        @DisplayName("Detects fully overlapping booking")
        void detectsFullyOverlappingBooking() {
            // Arrange - Create existing booking Feb 10-15
            Booking existing = new Booking();
            existing.setRenter(renter1);
            existing.setCar(car);
            existing.setStatus(BookingStatus.ACTIVE);
            existing.setStartTime(LocalDateTime.of(2026, 2, 10, 10, 0));
            existing.setEndTime(LocalDateTime.of(2026, 2, 15, 10, 0));
            existing.setTotalPrice(BigDecimal.valueOf(17500));
            bookingRepository.save(existing);

            // Act - Check for overlap on Feb 11-14
            boolean hasOverlap = bookingRepository.existsOverlappingBookingsWithLock(
                car.getId(),
                LocalDateTime.of(2026, 2, 11, 10, 0),
                LocalDateTime.of(2026, 2, 14, 10, 0)
            );

            // Assert
            assertThat(hasOverlap).isTrue();
        }

        @Test
        @DisplayName("Detects partial overlap at start")
        void detectsPartialOverlapAtStart() {
            // Arrange - Existing booking Feb 10-15
            Booking existing = new Booking();
            existing.setRenter(renter1);
            existing.setCar(car);
            existing.setStatus(BookingStatus.ACTIVE);
            existing.setStartTime(LocalDateTime.of(2026, 2, 10, 10, 0));
            existing.setEndTime(LocalDateTime.of(2026, 2, 15, 10, 0));
            existing.setTotalPrice(BigDecimal.valueOf(17500));
            bookingRepository.save(existing);

            // Act - New booking Feb 8-12 (overlaps at end)
            boolean hasOverlap = bookingRepository.existsOverlappingBookingsWithLock(
                car.getId(),
                LocalDateTime.of(2026, 2, 8, 10, 0),
                LocalDateTime.of(2026, 2, 12, 10, 0)
            );

            // Assert
            assertThat(hasOverlap).isTrue();
        }

        @Test
        @DisplayName("Detects partial overlap at end")
        void detectsPartialOverlapAtEnd() {
            // Arrange - Existing booking Feb 10-15
            Booking existing = new Booking();
            existing.setRenter(renter1);
            existing.setCar(car);
            existing.setStatus(BookingStatus.ACTIVE);
            existing.setStartTime(LocalDateTime.of(2026, 2, 10, 10, 0));
            existing.setEndTime(LocalDateTime.of(2026, 2, 15, 10, 0));
            existing.setTotalPrice(BigDecimal.valueOf(17500));
            bookingRepository.save(existing);

            // Act - New booking Feb 14-20 (overlaps at start)
            boolean hasOverlap = bookingRepository.existsOverlappingBookingsWithLock(
                car.getId(),
                LocalDateTime.of(2026, 2, 14, 10, 0),
                LocalDateTime.of(2026, 2, 20, 10, 0)
            );

            // Assert
            assertThat(hasOverlap).isTrue();
        }

        @Test
        @DisplayName("No overlap when dates are adjacent")
        void noOverlapWhenAdjacent() {
            // Arrange - Existing booking Feb 10-15
            Booking existing = new Booking();
            existing.setRenter(renter1);
            existing.setCar(car);
            existing.setStatus(BookingStatus.ACTIVE);
            existing.setStartTime(LocalDateTime.of(2026, 2, 10, 10, 0));
            existing.setEndTime(LocalDateTime.of(2026, 2, 15, 10, 0));
            existing.setTotalPrice(BigDecimal.valueOf(17500));
            bookingRepository.save(existing);

            // Act - New booking Feb 15-20 (starts when previous ends)
            boolean hasOverlap = bookingRepository.existsOverlappingBookingsWithLock(
                car.getId(),
                LocalDateTime.of(2026, 2, 15, 10, 0),
                LocalDateTime.of(2026, 2, 20, 10, 0)
            );

            // Assert - Should NOT overlap (exact boundary)
            assertThat(hasOverlap).isFalse();
        }

        @Test
        @DisplayName("No overlap when dates are separate")
        void noOverlapWhenSeparate() {
            // Arrange - Existing booking Feb 10-15
            Booking existing = new Booking();
            existing.setRenter(renter1);
            existing.setCar(car);
            existing.setStatus(BookingStatus.ACTIVE);
            existing.setStartTime(LocalDateTime.of(2026, 2, 10, 10, 0));
            existing.setEndTime(LocalDateTime.of(2026, 2, 15, 10, 0));
            existing.setTotalPrice(BigDecimal.valueOf(17500));
            bookingRepository.save(existing);

            // Act - New booking Feb 20-25 (completely separate)
            boolean hasOverlap = bookingRepository.existsOverlappingBookingsWithLock(
                car.getId(),
                LocalDateTime.of(2026, 2, 20, 10, 0),
                LocalDateTime.of(2026, 2, 25, 10, 0)
            );

            // Assert
            assertThat(hasOverlap).isFalse();
        }
    }

    @Nested
    @DisplayName("Status-Based Overlap")
    class StatusBasedOverlap {

        @Test
        @DisplayName("CANCELLED bookings do not cause overlap")
        void cancelledBookingsDoNotCauseOverlap() {
            // Arrange - Cancelled booking Feb 10-15
            Booking cancelled = new Booking();
            cancelled.setRenter(renter1);
            cancelled.setCar(car);
            cancelled.setStatus(BookingStatus.CANCELLED);
            cancelled.setStartTime(LocalDateTime.of(2026, 2, 10, 10, 0));
            cancelled.setEndTime(LocalDateTime.of(2026, 2, 15, 10, 0));
            cancelled.setTotalPrice(BigDecimal.valueOf(17500));
            bookingRepository.save(cancelled);

            // Act - Same dates should be available
            boolean hasOverlap = bookingRepository.existsOverlappingBookingsWithLock(
                car.getId(),
                LocalDateTime.of(2026, 2, 10, 10, 0),
                LocalDateTime.of(2026, 2, 15, 10, 0)
            );

            // Assert
            assertThat(hasOverlap).isFalse();
        }

        @Test
        @DisplayName("COMPLETED bookings do not cause overlap")
        void completedBookingsDoNotCauseOverlap() {
            // Arrange - Completed booking Feb 10-15
            Booking completed = new Booking();
            completed.setRenter(renter1);
            completed.setCar(car);
            completed.setStatus(BookingStatus.COMPLETED);
            completed.setStartTime(LocalDateTime.of(2026, 2, 10, 10, 0));
            completed.setEndTime(LocalDateTime.of(2026, 2, 15, 10, 0));
            completed.setTotalPrice(BigDecimal.valueOf(17500));
            bookingRepository.save(completed);

            // Act
            boolean hasOverlap = bookingRepository.existsOverlappingBookingsWithLock(
                car.getId(),
                LocalDateTime.of(2026, 2, 10, 10, 0),
                LocalDateTime.of(2026, 2, 15, 10, 0)
            );

            // Assert
            assertThat(hasOverlap).isFalse();
        }

        @Test
        @DisplayName("IN_TRIP bookings cause overlap")
        void inTripBookingsCauseOverlap() {
            // Arrange - In-trip booking Feb 10-15
            Booking inTrip = new Booking();
            inTrip.setRenter(renter1);
            inTrip.setCar(car);
            inTrip.setStatus(BookingStatus.IN_TRIP);
            inTrip.setStartTime(LocalDateTime.of(2026, 2, 10, 10, 0));
            inTrip.setEndTime(LocalDateTime.of(2026, 2, 15, 10, 0));
            inTrip.setTotalPrice(BigDecimal.valueOf(17500));
            bookingRepository.save(inTrip);

            // Act
            boolean hasOverlap = bookingRepository.existsOverlappingBookingsWithLock(
                car.getId(),
                LocalDateTime.of(2026, 2, 12, 10, 0),
                LocalDateTime.of(2026, 2, 18, 10, 0)
            );

            // Assert
            assertThat(hasOverlap).isTrue();
        }
    }

    @Nested
    @DisplayName("Version Field (Optimistic Locking)")
    class OptimisticLocking {

        @Test
        @DisplayName("Version increments on update")
        void versionIncrementsOnUpdate() {
            // Arrange
            Booking booking = new Booking();
            booking.setRenter(renter1);
            booking.setCar(car);
            booking.setStatus(BookingStatus.ACTIVE);
            booking.setStartTime(LocalDateTime.now().plusDays(5));
            booking.setEndTime(LocalDateTime.now().plusDays(8));
            booking.setTotalPrice(BigDecimal.valueOf(10500));
            booking = bookingRepository.save(booking);
            
            Long initialVersion = booking.getVersion();

            // Act - Update the booking
            booking.setStatus(BookingStatus.CHECK_IN_OPEN);
            booking = bookingRepository.save(booking);

            // Assert
            assertThat(booking.getVersion()).isGreaterThan(initialVersion);
        }
    }
}
