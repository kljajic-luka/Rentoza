package org.example.rentoza.booking;

import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.FuelType;
import org.example.rentoza.car.TransmissionType;
import org.example.rentoza.testconfig.AbstractIntegrationTest;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * True concurrent race condition test for double-booking prevention.
 * 
 * <p>This test verifies the "empty-slot" race condition fix (P0-4) by launching
 * multiple threads that simultaneously attempt to book the same car for overlapping
 * dates. PostgreSQL advisory locks ensure that only one booking succeeds.
 *
 * <p><b>NOTE:</b> This test is NOT @Transactional because each thread must run in
 * its own committed transaction for the concurrency control to be testable.
 * Cleanup is handled in @AfterEach.
 *
 * <h2>Critical Test Cases</h2>
 * <ul>
 *   <li>TC-041: Empty-slot concurrent race — N threads, 1 winner</li>
 *   <li>TC-042: Adjacent-slot concurrent bookings — all succeed</li>
 * </ul>
 */
@DisplayName("Booking - Concurrent Race Condition Tests")
class BookingConcurrentRaceTest extends AbstractIntegrationTest {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CarRepository carRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private org.example.rentoza.car.storage.DocumentStorageStrategy documentStorageStrategy;

    private TransactionTemplate txTemplate;
    private Car car;
    private final List<User> renters = new ArrayList<>();
    private static final int THREAD_COUNT = 5;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(txManager);

        txTemplate.execute(status -> {
            // Create host
            User host = new User();
            host.setEmail("race-host-" + System.nanoTime() + "@test.com");
            host.setFirstName("Race");
            host.setLastName("Host");
            host.setPassword("securePassword123");
            host.setAge(35);
            host = userRepository.save(host);

            // Create car
            car = new Car();
            car.setOwner(host);
            car.setBrand("Tesla");
            car.setModel("Model 3");
            car.setYear(2023);
            car.setPricePerDay(BigDecimal.valueOf(5000));
            car.setLocation("Belgrade");
            car.setSeats(5);
            car.setFuelType(FuelType.ELEKTRIČNI);
            car.setTransmissionType(TransmissionType.AUTOMATIC);
            car = carRepository.save(car);

            // Create N unique renters
            for (int i = 0; i < THREAD_COUNT; i++) {
                User renter = new User();
                renter.setEmail("race-renter-" + i + "-" + System.nanoTime() + "@test.com");
                renter.setFirstName("Renter" + i);
                renter.setLastName("Race");
                renter.setPassword("securePassword123");
                renter.setAge(25 + i);
                renter.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
                renter.setDriverLicenseExpiryDate(LocalDate.now().plusYears(3));
                renter = userRepository.save(renter);
                renters.add(renter);
            }

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        txTemplate.execute(status -> {
            // Clean up bookings created during the test
            if (car != null) {
                bookingRepository.findAll().stream()
                        .filter(b -> b.getCar() != null && b.getCar().getId().equals(car.getId()))
                        .forEach(bookingRepository::delete);
            }
            return null;
        });
    }

    @Test
    @DisplayName("TC-041: Only one booking succeeds when N threads race for the same slot")
    void onlyOneBookingSucceedsForSameSlot() throws Exception {
        // Given: N threads ready to book the SAME car for the SAME overlapping dates
        LocalDateTime startTime = LocalDateTime.of(2027, 6, 10, 10, 0);
        LocalDateTime endTime = LocalDateTime.of(2027, 6, 15, 10, 0);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch goLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int renterIndex = i;
            futures.add(executor.submit(() -> {
                try {
                    // Signal ready
                    readyLatch.countDown();
                    // Wait for all threads to be ready before starting
                    goLatch.await(10, TimeUnit.SECONDS);

                    // Each thread tries to create a booking in its own transaction
                    txTemplate.execute(status -> {
                        Booking booking = new Booking();
                        booking.setRenter(renters.get(renterIndex));
                        booking.setCar(car);
                        booking.setStatus(BookingStatus.PENDING_APPROVAL);
                        booking.setStartTime(startTime);
                        booking.setEndTime(endTime);
                        booking.setTotalPrice(BigDecimal.valueOf(25000));
                        bookingRepository.save(booking);
                        // Force flush to trigger the DB constraints immediately
                        bookingRepository.flush();
                        return null;
                    });

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        // Wait for all threads to be ready, then fire
        readyLatch.await(10, TimeUnit.SECONDS);
        goLatch.countDown();

        // Wait for all threads to finish
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Then: Exactly one booking should have been created
        assertThat(successCount.get())
                .as("Exactly one concurrent booking attempt should succeed")
                .isEqualTo(1);
        assertThat(failureCount.get())
                .as("All other concurrent attempts should fail due to overlap prevention")
                .isEqualTo(THREAD_COUNT - 1);

        // Verify in DB: only one booking exists for this car/slot
        List<Booking> overlapping = bookingRepository.findOverlappingBookingsWithLock(
                car.getId(), startTime, endTime);
        assertThat(overlapping).hasSize(1);
    }

    @Test
    @DisplayName("TC-042: Non-overlapping concurrent bookings all succeed")
    void nonOverlappingConcurrentBookingsAllSucceed() throws Exception {
        // Given: N threads booking DIFFERENT non-overlapping time slots
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch goLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int renterIndex = i;
            // Each thread books a different week (no overlap)
            final LocalDateTime start = LocalDateTime.of(2027, 7, 1 + (i * 7), 10, 0);
            final LocalDateTime end = LocalDateTime.of(2027, 7, 6 + (i * 7), 10, 0);

            futures.add(executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    goLatch.await(10, TimeUnit.SECONDS);

                    txTemplate.execute(status -> {
                        Booking booking = new Booking();
                        booking.setRenter(renters.get(renterIndex));
                        booking.setCar(car);
                        booking.setStatus(BookingStatus.PENDING_APPROVAL);
                        booking.setStartTime(start);
                        booking.setEndTime(end);
                        booking.setTotalPrice(BigDecimal.valueOf(25000));
                        bookingRepository.save(booking);
                        bookingRepository.flush();
                        return null;
                    });

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Should not happen for non-overlapping slots
                }
            }));
        }

        readyLatch.await(10, TimeUnit.SECONDS);
        goLatch.countDown();

        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Then: All bookings should succeed since they don't overlap
        assertThat(successCount.get())
                .as("All non-overlapping concurrent bookings should succeed")
                .isEqualTo(THREAD_COUNT);
    }
}
