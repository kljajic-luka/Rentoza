package org.example.rentoza.booking.checkin.cqrs;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.FuelType;
import org.example.rentoza.car.TransmissionType;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for CheckInStatusView upsert operation.
 * 
 * <p><b>Purpose:</b> Validates the race condition fix using PostgreSQL ON CONFLICT.
 * 
 * <p><b>The Problem (Before Fix):</b>
 * <pre>
 * Thread A: SELECT → Row doesn't exist → INSERT
 * Thread B: SELECT → Row doesn't exist → INSERT ← RACE CONDITION!
 * 
 * Result: DataIntegrityViolationException (duplicate key)
 * </pre>
 * 
 * <p><b>The Solution (After Fix):</b>
 * <pre>
 * Thread A: INSERT...ON CONFLICT DO UPDATE ← Atomic at DB level
 * Thread B: INSERT...ON CONFLICT DO UPDATE ← Atomic at DB level
 * 
 * Result: Race condition impossible, both succeed
 * </pre>
 * 
 * <p><b>Test Strategy:</b>
 * <ul>
 *   <li>10 concurrent threads</li>
 *   <li>Same booking ID (maximum contention)</li>
 *   <li>All call upsertPhotoCount() simultaneously</li>
 *   <li>Expected result: All succeed, photo_count = 10</li>
 * </ul>
 * 
 * @see CheckInStatusViewRepository#upsertPhotoCount
 */
@SpringBootTest
@ActiveProfiles("test")
class CheckInStatusViewConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(CheckInStatusViewConcurrencyTest.class);

    @Autowired
    private CheckInStatusViewRepository viewRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CarRepository carRepository;

    private Long testBookingId;
    private UUID testSessionId;
    private Long hostUserId;
    private Long guestUserId;
    private Long carId;

    @BeforeEach
    void setUp() {
        // Clean up
        viewRepository.deleteAll();
        bookingRepository.deleteAll();
        carRepository.deleteAll();
        userRepository.deleteAll();

        // Create test data
        User host = createTestUser("host@test.com", "Host", "User");
        User guest = createTestUser("guest@test.com", "Guest", "User");
        Car car = createTestCar(host);
        Booking booking = createTestBooking(car, guest);

        testBookingId = booking.getId();
        testSessionId = UUID.randomUUID();
        hostUserId = host.getId();
        guestUserId = guest.getId();
        carId = car.getId();
    }

    /**
     * ✅ CRITICAL TEST: 10 concurrent threads, same booking.
     * 
     * This test validates that the PostgreSQL ON CONFLICT fix prevents
     * the SELECT-then-INSERT race condition entirely.
     * 
     * Before fix: Would fail with DataIntegrityViolationException
     * After fix: All 10 succeed, photo_count = 10
     */
    @Test
    @DisplayName("✅ Concurrency Fix: 10 concurrent upserts to same booking should all succeed")
    void testConcurrentUpsertsToSameBooking() throws InterruptedException {
        // Arrange
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(10);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Act: 10 concurrent threads all upserting photo count
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready, then execute simultaneously
                    startLatch.await();

                    viewRepository.upsertPhotoCount(
                            testBookingId,
                            testSessionId,
                            hostUserId,
                            "Host Name",
                            "+1234567890",
                            guestUserId,
                            "Guest Name",
                            "+0987654321",
                            carId,
                            "Tesla",
                            "Model 3",
                            2024,
                            "https://example.com/car.jpg",
                            "ABC-123",
                            BookingStatus.CHECK_IN_OPEN.name(),
                            "Check-In Open",
                            LocalDateTime.now().plusHours(1),
                            true,
                            50
                    );
                    
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    log.debug("Error in concurrent upsert: {}", e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously to maximize race condition potential
        startLatch.countDown();

        // Wait for all to complete
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);

        // Shutdown executor
        executor.shutdown();

        // Assert: All 10 should succeed with no errors
        assertThat(completed).as("All 10 threads should complete within 30 seconds").isTrue();
        assertThat(errorCount.get()).as("No errors should occur (fix prevents race condition)")
                .isEqualTo(0);
        assertThat(successCount.get()).as("All 10 threads should succeed")
                .isEqualTo(10);

        // Assert: Photo count should be exactly 10 (not 1 due to race condition)
        CheckInStatusView view = viewRepository.findByBookingId(testBookingId)
                .orElseThrow(() -> new AssertionError("View should exist after upserts"));

        assertThat(view.getPhotoCount())
                .as("Photo count should be 10 (one per thread)")
                .isEqualTo(10);

        assertThat(view.getVersion())
                .as("Insert initializes version at 0, then the remaining 9 updates increment it")
                .isEqualTo(9);

        log.debug("Race condition fix validated: 10 concurrent upserts succeeded, photo_count={}, version={}",
                view.getPhotoCount(), view.getVersion());
    }

    /**
     * Test with even higher concurrency (20 threads) to stress-test the fix.
     */
    @Test
    @DisplayName("✅ Stress Test: 20 concurrent upserts should maintain consistency")
    void testHighConcurrencyUpserts() throws InterruptedException {
        // Arrange
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(20);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Act: 20 concurrent threads
        for (int i = 0; i < 20; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();

                    viewRepository.upsertPhotoCount(
                            testBookingId,
                            testSessionId,
                            hostUserId,
                            "Host Name",
                            "+1234567890",
                            guestUserId,
                            "Guest Name",
                            "+0987654321",
                            carId,
                            "Tesla",
                            "Model 3",
                            2024,
                            "https://example.com/car.jpg",
                            "ABC-123",
                            BookingStatus.CHECK_IN_OPEN.name(),
                            "Check-In Open",
                            LocalDateTime.now().plusHours(1),
                            true,
                            50
                    );
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert
        assertThat(completed).as("All 20 threads should complete").isTrue();
        assertThat(errorCount.get()).as("No errors with 20 concurrent threads")
                .isEqualTo(0);

        CheckInStatusView view = viewRepository.findByBookingId(testBookingId)
                .orElseThrow();

        assertThat(view.getPhotoCount())
                .as("Photo count must be exactly 20")
                .isEqualTo(20);

        log.debug("Stress test passed: 20 concurrent upserts, photo_count={}", view.getPhotoCount());
    }

    /**
     * Validates that denormalized fields are correctly populated on INSERT.
     * (First upsert that creates the row)
     */
    @Test
    @DisplayName("✅ Denormalized Fields: First upsert should populate all 19 parameters")
    void testDenormalizedFieldsPopulatedOnInsert() {
        // Act: Single upsert (INSERT path)
        viewRepository.upsertPhotoCount(
                testBookingId,
                testSessionId,
                hostUserId,
                "John Host",
                "+1 (555) 123-4567",
                guestUserId,
                "Jane Guest",
                "+1 (555) 987-6543",
                carId,
                "BMW",
                "X5",
                2023,
                "https://example.com/bmw-x5.jpg",
                "BMW-2023-001",
                BookingStatus.CHECK_IN_OPEN.name(),
                "Check-In Open",
                LocalDateTime.now().plusHours(2),
                true,
                120
        );

        // Assert: All fields populated
        CheckInStatusView view = viewRepository.findByBookingId(testBookingId)
                .orElseThrow();

        assertThat(view.getBookingId()).isEqualTo(testBookingId);
        assertThat(view.getSessionId()).isEqualTo(testSessionId);
        assertThat(view.getHostUserId()).isEqualTo(hostUserId);
        assertThat(view.getHostName()).isEqualTo("John Host");
        assertThat(view.getHostPhone()).isEqualTo("+1 (555) 123-4567");
        assertThat(view.getGuestUserId()).isEqualTo(guestUserId);
        assertThat(view.getGuestName()).isEqualTo("Jane Guest");
        assertThat(view.getGuestPhone()).isEqualTo("+1 (555) 987-6543");
        assertThat(view.getCarId()).isEqualTo(carId);
        assertThat(view.getCarBrand()).isEqualTo("BMW");
        assertThat(view.getCarModel()).isEqualTo("X5");
        assertThat(view.getCarYear()).isEqualTo(2023);
        assertThat(view.getCarImageUrl()).isEqualTo("https://example.com/bmw-x5.jpg");
        assertThat(view.getCarLicensePlate()).isEqualTo("BMW-2023-001");
        assertThat(view.getStatus()).isEqualTo(BookingStatus.CHECK_IN_OPEN);
        assertThat(view.getStatusDisplay()).isEqualTo("Check-In Open");
        assertThat(view.isLockboxAvailable()).isTrue();
        assertThat(view.getGeofenceDistanceMeters()).isEqualTo(120);
        assertThat(view.getPhotoCount()).isEqualTo(1);

        log.debug("All 19 denormalized fields populated correctly on INSERT");
    }

    /**
     * Validates that UPDATE path correctly increments photo_count and version.
     */
    @Test
    @DisplayName("✅ Update Path: Subsequent upserts should increment photo_count and version")
    void testUpdatePathIncrementsCountAndVersion() {
        // Arrange: First upsert (INSERT)
        viewRepository.upsertPhotoCount(
                testBookingId, testSessionId, hostUserId, "Host", "+1234567890",
                guestUserId, "Guest", "+0987654321", carId, "Tesla", "Model 3",
                2024, "https://example.com/car.jpg", "ABC-123",
                BookingStatus.CHECK_IN_OPEN.name(), "Check-In Open",
                LocalDateTime.now(), true, 50
        );

        CheckInStatusView viewAfterInsert = viewRepository.findByBookingId(testBookingId)
                .orElseThrow();
        assertThat(viewAfterInsert.getPhotoCount()).isEqualTo(1);
        assertThat(viewAfterInsert.getVersion()).isEqualTo(0);

        // Act: Second upsert (UPDATE path)
        viewRepository.upsertPhotoCount(
                testBookingId, testSessionId, hostUserId, "Host", "+1234567890",
                guestUserId, "Guest", "+0987654321", carId, "Tesla", "Model 3",
                2024, "https://example.com/car.jpg", "ABC-123",
                BookingStatus.CHECK_IN_OPEN.name(), "Check-In Open",
                LocalDateTime.now(), true, 50
        );

        // Assert: photo_count and version incremented
        CheckInStatusView viewAfterUpdate = viewRepository.findByBookingId(testBookingId)
                .orElseThrow();

        assertThat(viewAfterUpdate.getPhotoCount())
                .as("photo_count should increment on UPDATE")
                .isEqualTo(2);

        assertThat(viewAfterUpdate.getVersion())
                .as("version should increment on UPDATE")
                .isEqualTo(1);

        log.debug("UPDATE path correctly increments photo_count and version");
    }

    // ========== Helper Methods ==========

    private User createTestUser(String email, String firstName, String lastName) {
        User user = new User();
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPassword("pass");
        user.setAge(30);
        return userRepository.save(user);
    }

    private Car createTestCar(User owner) {
        Car car = new Car();
        car.setOwner(owner);
        car.setBrand("Tesla");
        car.setModel("Model 3");
        car.setYear(2024);
        car.setPricePerDay(new BigDecimal("7500.00"));
        car.setLocation("Belgrade");
        car.setSeats(5);
        car.setFuelType(FuelType.BENZIN);
        car.setTransmissionType(TransmissionType.AUTOMATIC);
        return carRepository.save(car);
    }

    private Booking createTestBooking(Car car, User renter) {
        Booking booking = new Booking();
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStatus(BookingStatus.ACTIVE);
        booking.setStartTime(LocalDateTime.now().plusDays(1));
        booking.setEndTime(LocalDateTime.now().plusDays(2));
        booking.setCheckInSessionId(UUID.randomUUID().toString());
        booking.setTotalPrice(new BigDecimal("15000.00"));
        return bookingRepository.save(booking);
    }
}
