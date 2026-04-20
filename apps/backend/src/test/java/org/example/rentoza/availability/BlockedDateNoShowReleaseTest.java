package org.example.rentoza.availability;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.FuelType;
import org.example.rentoza.car.TransmissionType;
import org.example.rentoza.testconfig.AbstractIntegrationTest;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the no-show → blocked_dates release fix (V60 migration).
 *
 * <p>These tests are NOT @Transactional so each {@link TransactionTemplate#execute}
 * call commits to the database and the PostgreSQL trigger fires as it would in production.
 * Cleanup is handled in {@link AfterEach}.
 *
 * <h2>Test Cases</h2>
 * <ul>
 *   <li>TC-BD-01: CHECK_IN_OPEN → NO_SHOW_HOST removes booking-linked blocked row</li>
 *   <li>TC-BD-02: CHECK_IN_HOST_COMPLETE → NO_SHOW_GUEST removes booking-linked blocked row</li>
 *   <li>TC-BD-03: Manual blocked date survives no-show cleanup</li>
 *   <li>TC-BD-04: Availability returns available after no-show transition</li>
 *   <li>TC-BD-05: Status transition INTO occupying recreates the blocked row</li>
 *   <li>TC-BD-06: Race boundary — concurrent no-show and nearby transition leave consistent state</li>
 * </ul>
 */
@DisplayName("BlockedDate - No-Show Calendar Release Tests")
class BlockedDateNoShowReleaseTest extends AbstractIntegrationTest {

    @Autowired private BlockedDateRepository blockedDateRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CarRepository carRepository;
    @Autowired private PlatformTransactionManager txManager;

    @MockBean
    private org.example.rentoza.car.storage.DocumentStorageStrategy documentStorageStrategy;

    private TransactionTemplate tx;

    private User host;
    private User renter;
    private Car car;

    // Ids saved for cleanup
    private Long hostId;
    private Long renterId;
    private Long carId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);

        tx.execute(status -> {
            host = new User();
            host.setEmail("bdns-host-" + System.nanoTime() + "@test.com");
            host.setFirstName("BDNs");
            host.setLastName("Host");
            host.setPassword("pass");
            host.setAge(40);
            host = userRepository.save(host);
            hostId = host.getId();

            renter = new User();
            renter.setEmail("bdns-renter-" + System.nanoTime() + "@test.com");
            renter.setFirstName("BDNs");
            renter.setLastName("Renter");
            renter.setPassword("pass");
            renter.setAge(28);
            renter = userRepository.save(renter);
            renterId = renter.getId();

            car = new Car();
            car.setOwner(host);
            car.setBrand("BMW");
            car.setModel("320d");
            car.setYear(2021);
            car.setPricePerDay(BigDecimal.valueOf(4000));
            car.setLocation("Belgrade");
            car.setSeats(5);
            car.setFuelType(FuelType.DIZEL);
            car.setTransmissionType(TransmissionType.AUTOMATIC);
            car = carRepository.save(car);
            carId = car.getId();

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        tx.execute(status -> {
            blockedDateRepository.deleteByCarId(carId);
            bookingRepository.findAll().stream()
                .filter(b -> b.getCar() != null && carId.equals(b.getCar().getId()))
                .forEach(bookingRepository::delete);
            carRepository.deleteById(carId);
            userRepository.deleteById(renterId);
            userRepository.deleteById(hostId);
            return null;
        });
    }

    // =========================================================================
    // Helper: persist a booking with the given status and return its ID
    // =========================================================================
    private Long saveBooking(BookingStatus status, LocalDateTime start, LocalDateTime end) {
        return tx.execute(txStatus -> {
            Booking b = new Booking();
            b.setCar(carRepository.findById(carId).orElseThrow());
            b.setRenter(userRepository.findById(renterId).orElseThrow());
            b.setStatus(status);
            b.setStartTime(start);
            b.setEndTime(end);
            b.setTotalPrice(BigDecimal.valueOf(16000));
            b = bookingRepository.save(b);
            bookingRepository.flush();
            return b.getId();
        });
    }

    private void transitionStatus(Long bookingId, BookingStatus newStatus) {
        tx.execute(txStatus -> {
            Booking b = bookingRepository.findById(bookingId).orElseThrow();
            b.setStatus(newStatus);
            bookingRepository.save(b);
            bookingRepository.flush();
            return null;
        });
    }

    // =========================================================================
    // TC-BD-01
    // =========================================================================

    @Test
    @DisplayName("TC-BD-01: CHECK_IN_OPEN → NO_SHOW_HOST removes booking-linked blocked row")
    void noShowHost_removesBlockedRow() {
        LocalDateTime start = LocalDateTime.of(2027, 8, 10, 10, 0);
        LocalDateTime end   = LocalDateTime.of(2027, 8, 14, 10, 0);

        // Arrange: booking enters CHECK_IN_OPEN (occupying → trigger creates blocked row)
        Long bookingId = saveBooking(BookingStatus.CHECK_IN_OPEN, start, end);

        // Verify trigger created the row
        assertThat(blockedDateRepository.existsByBookingId(bookingId))
                .as("Trigger must create blocked_dates row when status is CHECK_IN_OPEN")
                .isTrue();

        // Act: transition to NO_SHOW_HOST (non-occupying)
        transitionStatus(bookingId, BookingStatus.NO_SHOW_HOST);

        // Assert: trigger must have deleted the row
        assertThat(blockedDateRepository.existsByBookingId(bookingId))
                .as("Trigger must delete blocked_dates row on NO_SHOW_HOST transition")
                .isFalse();
    }

    // =========================================================================
    // TC-BD-02
    // =========================================================================

    @Test
    @DisplayName("TC-BD-02: CHECK_IN_HOST_COMPLETE → NO_SHOW_GUEST removes booking-linked blocked row")
    void noShowGuest_removesBlockedRow() {
        LocalDateTime start = LocalDateTime.of(2027, 9, 1, 10, 0);
        LocalDateTime end   = LocalDateTime.of(2027, 9, 5, 10, 0);

        Long bookingId = saveBooking(BookingStatus.CHECK_IN_HOST_COMPLETE, start, end);

        assertThat(blockedDateRepository.existsByBookingId(bookingId))
                .as("Trigger must create blocked_dates row for CHECK_IN_HOST_COMPLETE")
                .isTrue();

        transitionStatus(bookingId, BookingStatus.NO_SHOW_GUEST);

        assertThat(blockedDateRepository.existsByBookingId(bookingId))
                .as("Trigger must delete blocked_dates row on NO_SHOW_GUEST transition")
                .isFalse();
    }

    // =========================================================================
    // TC-BD-03
    // =========================================================================

    @Test
    @DisplayName("TC-BD-03: Manual blocked date survives no-show cleanup")
    void manualBlock_survivesNoShowCleanup() {
        LocalDateTime bookingStart = LocalDateTime.of(2027, 10, 1, 10, 0);
        LocalDateTime bookingEnd   = LocalDateTime.of(2027, 10, 5, 10, 0);

        // Manual block covering same period
        LocalDate manualStart = LocalDate.of(2027, 10, 1);
        LocalDate manualEnd   = LocalDate.of(2027, 10, 5);

        // Create manual block (booking_id = null, created directly by owner)
        Long manualBlockId = tx.execute(txStatus -> {
            Car c = carRepository.findById(carId).orElseThrow();
            User o = userRepository.findById(hostId).orElseThrow();
            BlockedDate manual = BlockedDate.builder()
                    .car(c)
                    .owner(o)
                    .startDate(manualStart)
                    .endDate(manualEnd)
                    .build();
            BlockedDate saved = blockedDateRepository.save(manual);
            blockedDateRepository.flush();
            return saved.getId();
        });

        // Create no-show booking for same period
        Long bookingId = saveBooking(BookingStatus.CHECK_IN_OPEN, bookingStart, bookingEnd);
        transitionStatus(bookingId, BookingStatus.NO_SHOW_HOST);

        // Assert: booking-linked row is gone
        assertThat(blockedDateRepository.existsByBookingId(bookingId))
                .as("Booking-linked row must be deleted after NO_SHOW_HOST")
                .isFalse();

        // Assert: manual block still exists
        assertThat(blockedDateRepository.findById(manualBlockId))
                .as("Manual blocked date (booking_id IS NULL) must not be affected by no-show")
                .isPresent();

        // Assert: effective overlap returns true because manual block still exists
        assertThat(blockedDateRepository.existsEffectiveOverlappingBlockedDates(carId, manualStart, manualEnd))
                .as("Manual block must still suppress availability")
                .isTrue();
    }

    // =========================================================================
    // TC-BD-04
    // =========================================================================

    @Test
    @DisplayName("TC-BD-04: Status-aware overlap returns false after no-show (dates re-open)")
    void afterNoShow_statusAwareOverlapReturnsFalse() {
        LocalDate blockStart = LocalDate.of(2027, 11, 10);
        LocalDate blockEnd   = LocalDate.of(2027, 11, 14);
        LocalDateTime start  = blockStart.atTime(10, 0);
        LocalDateTime end    = blockEnd.atTime(10, 0);

        Long bookingId = saveBooking(BookingStatus.CHECK_IN_OPEN, start, end);

        // Before no-show: dates are blocked
        assertThat(blockedDateRepository.existsEffectiveOverlappingBlockedDates(carId, blockStart, blockEnd))
                .as("Dates must be blocked while booking is in CHECK_IN_OPEN")
                .isTrue();

        // Transition to NO_SHOW_HOST
        transitionStatus(bookingId, BookingStatus.NO_SHOW_HOST);

        // After no-show: status-aware query should return false (row deleted by trigger)
        assertThat(blockedDateRepository.existsEffectiveOverlappingBlockedDates(carId, blockStart, blockEnd))
                .as("Dates must be free after NO_SHOW_HOST (no manual block present)")
                .isFalse();
    }

    // =========================================================================
    // TC-BD-05: Re-entry — transition back into occupying status recreates row
    // =========================================================================

    @Test
    @DisplayName("TC-BD-05: Transition PENDING_APPROVAL → ACTIVE recreates blocked row")
    void enteringOccupyingStatus_createsBlockedRow() {
        LocalDateTime start = LocalDateTime.of(2027, 12, 1, 10, 0);
        LocalDateTime end   = LocalDateTime.of(2027, 12, 5, 10, 0);

        // Start as PENDING_APPROVAL (non-occupying)
        Long bookingId = saveBooking(BookingStatus.PENDING_APPROVAL, start, end);

        assertThat(blockedDateRepository.existsByBookingId(bookingId))
                .as("PENDING_APPROVAL is not occupying — no blocked row expected")
                .isFalse();

        // Approve → ACTIVE (occupying)
        transitionStatus(bookingId, BookingStatus.ACTIVE);

        assertThat(blockedDateRepository.existsByBookingId(bookingId))
                .as("Trigger must create blocked row when status enters ACTIVE")
                .isTrue();
    }

    // =========================================================================
    // TC-BD-06: Race boundary — concurrent status transitions settle consistently
    // =========================================================================

    @Nested
    @DisplayName("TC-BD-06: Race boundary tests")
    class RaceBoundaryTests {

        @Test
        @DisplayName("TC-BD-06a: Final state after concurrent CHECK_IN_OPEN and NO_SHOW_HOST is unblocked")
        void concurrentNoShowTransition_finalStateIsUnblocked() throws Exception {
            LocalDateTime start = LocalDateTime.of(2028, 1, 10, 10, 0);
            LocalDateTime end   = LocalDateTime.of(2028, 1, 14, 10, 0);

            Long bookingId = saveBooking(BookingStatus.CHECK_IN_OPEN, start, end);

            // Confirm row exists
            assertThat(blockedDateRepository.existsByBookingId(bookingId)).isTrue();

            // Two concurrent threads: one keeps it CHECK_IN_OPEN, one sets NO_SHOW_HOST
            CountDownLatch ready  = new CountDownLatch(2);
            CountDownLatch go     = new CountDownLatch(1);
            AtomicReference<BookingStatus> finalStatus = new AtomicReference<>();

            ExecutorService executor = Executors.newFixedThreadPool(2);

            Future<?> thread1 = executor.submit(() -> {
                ready.countDown();
                try { go.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                // Thread 1: redundant CHECK_IN_OPEN update (no-op final state)
                tx.execute(txStatus -> {
                    Booking b = bookingRepository.findById(bookingId).orElseThrow();
                    if (b.getStatus() == BookingStatus.CHECK_IN_OPEN) {
                        b.setStatus(BookingStatus.CHECK_IN_OPEN);
                        bookingRepository.save(b);
                    }
                    return null;
                });
            });

            Future<?> thread2 = executor.submit(() -> {
                ready.countDown();
                try { go.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                // Thread 2: transitions to NO_SHOW_HOST
                tx.execute(txStatus -> {
                    Booking b = bookingRepository.findById(bookingId).orElseThrow();
                    b.setStatus(BookingStatus.NO_SHOW_HOST);
                    bookingRepository.save(b);
                    return null;
                });
            });

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            thread1.get(10, TimeUnit.SECONDS);
            thread2.get(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Assert: NO_SHOW committed, row deleted
            tx.execute(txStatus -> {
                Booking b = bookingRepository.findById(bookingId).orElseThrow();
                finalStatus.set(b.getStatus());
                return null;
            });

            assertThat(finalStatus.get())
                    .as("Final booking status must be NO_SHOW_HOST")
                    .isEqualTo(BookingStatus.NO_SHOW_HOST);

            assertThat(blockedDateRepository.existsByBookingId(bookingId))
                    .as("Blocked row must be gone after NO_SHOW_HOST committed")
                    .isFalse();
        }

        @Test
        @DisplayName("TC-BD-06b: Two nearby bookings — only the no-show one is unblocked")
        void nearbyBookings_onlyNoShowIsUnblocked() {
            // Booking A: CHECK_IN_OPEN — will no-show
            LocalDateTime startA = LocalDateTime.of(2028, 2, 1, 10, 0);
            LocalDateTime endA   = LocalDateTime.of(2028, 2, 5, 10, 0);

            // Booking B: ACTIVE (adjacent, stays active)
            LocalDateTime startB = LocalDateTime.of(2028, 2, 6, 10, 0);
            LocalDateTime endB   = LocalDateTime.of(2028, 2, 10, 10, 0);

            Long bookingIdA = saveBooking(BookingStatus.CHECK_IN_OPEN, startA, endA);
            Long bookingIdB = saveBooking(BookingStatus.ACTIVE, startB, endB);

            assertThat(blockedDateRepository.existsByBookingId(bookingIdA)).isTrue();
            assertThat(blockedDateRepository.existsByBookingId(bookingIdB)).isTrue();

            // Transition A to no-show
            transitionStatus(bookingIdA, BookingStatus.NO_SHOW_HOST);

            // A must be unblocked, B must still be blocked
            assertThat(blockedDateRepository.existsByBookingId(bookingIdA))
                    .as("Booking A (no-show) must be unblocked")
                    .isFalse();
            assertThat(blockedDateRepository.existsByBookingId(bookingIdB))
                    .as("Booking B (still active) must remain blocked")
                    .isTrue();

            // Effective overlap queries must agree
            assertThat(blockedDateRepository.existsEffectiveOverlappingBlockedDates(
                    carId, startA.toLocalDate(), endA.toLocalDate()))
                    .as("Dates of no-show booking must be available")
                    .isFalse();
            assertThat(blockedDateRepository.existsEffectiveOverlappingBlockedDates(
                    carId, startB.toLocalDate(), endB.toLocalDate()))
                    .as("Dates of active booking must still be blocked")
                    .isTrue();
        }
    }
}
