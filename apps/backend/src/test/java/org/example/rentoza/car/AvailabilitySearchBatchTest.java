package org.example.rentoza.car;

import org.example.rentoza.availability.BlockedDate;
import org.example.rentoza.availability.BlockedDateRepository;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.dto.AvailabilitySearchRequestDTO;
import org.example.rentoza.testconfig.AbstractIntegrationTest;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.transaction.support.TransactionTemplate;

import org.example.rentoza.common.GeoPoint;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * Regression test proving availability search query count no longer scales linearly
 * with the number of candidate cars (P2 N+1 fix verification).
 *
 * Strategy:
 * - Enable Hibernate statistics
 * - Create N candidate cars, some with blocking bookings/blocked dates
 * - Execute availability search
 * - Assert query count is O(1) relative to N (exactly a fixed number of queries
 *   regardless of how many candidate cars there are)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilitySearchBatchTest extends AbstractIntegrationTest {

    @Autowired private AvailabilityService availabilityService;
    @Autowired private CarRepository carRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BlockedDateRepository blockedDateRepository;
    @Autowired private EntityManagerFactory emf;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private Statistics stats;

    private Long hostId;
    private Long renterId;

    @BeforeAll
    void initStats() {
        SessionFactory sf = emf.unwrap(SessionFactory.class);
        sf.getStatistics().setStatisticsEnabled(true);
        stats = sf.getStatistics();
    }

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);

        // Clean up in correct order (bookings first, then blocked dates, then cars, then users)
        tx.execute(status -> {
            bookingRepository.deleteAll();
            blockedDateRepository.deleteAll();
            carRepository.deleteAll();
            userRepository.deleteAll();
            return null;
        });

        // Create test users
        tx.execute(status -> {
            User host = new User();
            host.setEmail("batch-host-" + System.nanoTime() + "@test.com");
            host.setFirstName("BatchHost");
            host.setLastName("Test");
            host.setPassword("pass");
            host.setAge(35);
            host = userRepository.save(host);
            hostId = host.getId();

            User renter = new User();
            renter.setEmail("batch-renter-" + System.nanoTime() + "@test.com");
            renter.setFirstName("BatchRenter");
            renter.setLastName("Test");
            renter.setPassword("pass");
            renter.setAge(25);
            renter = userRepository.save(renter);
            renterId = renter.getId();
            return null;
        });
    }

    @AfterEach
    void cleanUp() {
        tx.execute(status -> {
            bookingRepository.deleteAll();
            blockedDateRepository.deleteAll();
            carRepository.deleteAll();
            userRepository.deleteAll();
            return null;
        });
    }

    private Car createApprovedCar(String brand, String model, String city) {
        User host = userRepository.findById(hostId).orElseThrow();
        Car car = new Car();
        car.setOwner(host);
        car.setBrand(brand);
        car.setModel(model);
        car.setYear(2023);
        car.setPricePerDay(new BigDecimal("100.00"));
        car.setAvailable(true);
        car.setApprovalStatus(ApprovalStatus.APPROVED);
        car.setListingStatus(ListingStatus.APPROVED);
        car.setLocation(city);

        // P1 FIX: findAvailableWithDetailsByLocation queries locationGeoPoint.city, not legacy location
        GeoPoint geoPoint = new GeoPoint();
        geoPoint.setCity(city);
        car.setLocationGeoPoint(geoPoint);

        return carRepository.save(car);
    }

    private Booking createBlockingBooking(Car car, LocalDateTime start, LocalDateTime end) {
        User renter = userRepository.findById(renterId).orElseThrow();
        Booking booking = new Booking();
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStartTime(start);
        booking.setEndTime(end);
        booking.setStatus(BookingStatus.ACTIVE);
        booking.setTotalPrice(new BigDecimal("300.00"));
        return bookingRepository.save(booking);
    }

    private BlockedDate createBlockedDate(Car car, LocalDate start, LocalDate end) {
        User host = userRepository.findById(hostId).orElseThrow();
        BlockedDate bd = new BlockedDate();
        bd.setCar(car);
        bd.setOwner(host);
        bd.setStartDate(start);
        bd.setEndDate(end);
        return blockedDateRepository.save(bd);
    }

    // ========== TC-BATCH-01: Query count is O(1) ==========

    @Test
    @DisplayName("TC-BATCH-01: Availability search query count does not scale with candidate count")
    void queryCountDoesNotScaleWithCandidateCount() {
        final int SMALL_N = 5;
        final int LARGE_N = 20;

        LocalDateTime searchStart = LocalDateTime.now().plusDays(10).withHour(9).withMinute(0);
        LocalDateTime searchEnd = searchStart.plusDays(3);

        // --- Run with SMALL_N cars ---
        tx.execute(status -> {
            for (int i = 0; i < SMALL_N; i++) {
                Car car = createApprovedCar("BMW", "X" + i, "beograd");
                // Make half the cars booked
                if (i % 2 == 0) {
                    createBlockingBooking(car, searchStart.plusHours(1), searchEnd.minusHours(1));
                }
            }
            return null;
        });

        stats.clear();

        AvailabilitySearchRequestDTO smallRequest = AvailabilitySearchRequestDTO.builder()
                .location("beograd")
                .startTime(searchStart)
                .endTime(searchEnd)
                .page(0)
                .size(50)
                .build();
        Page<Car> smallResult = availabilityService.searchAvailableCars(smallRequest);
        long smallStmtCount = stats.getPrepareStatementCount();

        // Verify some cars were excluded
        assertThat(smallResult.getTotalElements()).isLessThan(SMALL_N);
        assertThat(smallResult.getTotalElements()).isGreaterThan(0);

        // --- Clean & rerun with LARGE_N cars ---
        tx.execute(status -> {
            bookingRepository.deleteAll();
            blockedDateRepository.deleteAll();
            carRepository.deleteAll();
            return null;
        });

        tx.execute(status -> {
            for (int i = 0; i < LARGE_N; i++) {
                Car car = createApprovedCar("Mercedes", "C" + i, "beograd");
                if (i % 2 == 0) {
                    createBlockingBooking(car, searchStart.plusHours(1), searchEnd.minusHours(1));
                }
                if (i % 5 == 0) {
                    createBlockedDate(car, searchStart.toLocalDate(), searchEnd.toLocalDate());
                }
            }
            return null;
        });

        stats.clear();

        AvailabilitySearchRequestDTO largeRequest = AvailabilitySearchRequestDTO.builder()
                .location("beograd")
                .startTime(searchStart)
                .endTime(searchEnd)
                .page(0)
                .size(50)
                .build();
        Page<Car> largeResult = availabilityService.searchAvailableCars(largeRequest);
        long largeStmtCount = stats.getPrepareStatementCount();

        // Verify large search also filtered correctly
        assertThat(largeResult.getTotalElements()).isLessThan(LARGE_N);
        assertThat(largeResult.getTotalElements()).isGreaterThan(0);

        // KEY ASSERTION: prepared statement count should be approximately the same for both sizes.
        // With batch queries, we expect a fixed number of statements (candidate fetch + 2 batch checks
        // + Hibernate.initialize calls per entity). The per-entity init statements scale with N,
        // but the availability-check statements are O(1).
        // With old N+1, the large set would produce ~2*N additional statements.
        //
        // Per-entity overhead analysis (location-string search path):
        //   findAvailableWithDetailsByLocation uses @EntityGraph({"owner","features"}) — 0 extra stmts
        //   Hibernate.initialize(car.getAddOns())  — 1 stmt per candidate car
        //   Hibernate.initialize(car.getImages())  — 1 stmt per candidate car
        //   Total: 2 stmts per candidate car
        //
        // With old N+1 pattern, there would be 2 ADDITIONAL stmts per car:
        //   existsEffectiveOverlappingBlockedDates  — 1 per car
        //   findPublicBookingsForCar                — 1 per car
        // So old N+1 delta = (LARGE_N - SMALL_N) * 2 = 30 — must NOT fit in tolerance.
        long perEntityInitOverhead = (long) (LARGE_N - SMALL_N) * 2; // addOns + images per candidate car
        assertThat(largeStmtCount)
                .as("Stmt count for %d cars (%d) should grow only by per-entity init overhead (%d), not by N+1. "
                        + "Old N+1 would add ~%d extra stmts which must exceed this tolerance.",
                        LARGE_N, largeStmtCount, perEntityInitOverhead, 2L * (LARGE_N - SMALL_N))
                .isLessThanOrEqualTo(smallStmtCount + perEntityInitOverhead + 5);
    }

    // ========== TC-BATCH-02: Batch semantics match per-car semantics ==========

    @Test
    @DisplayName("TC-BATCH-02: Blocked car excluded, available car included")
    void batchCorrectlyFiltersBlockedCars() {
        LocalDateTime searchStart = LocalDateTime.now().plusDays(10).withHour(9).withMinute(0);
        LocalDateTime searchEnd = searchStart.plusDays(3);

        tx.execute(status -> {
            // Car A: has overlapping booking → should be excluded
            Car carA = createApprovedCar("Audi", "A4", "beograd");
            createBlockingBooking(carA, searchStart.plusHours(1), searchEnd.minusHours(1));

            // Car B: has overlapping blocked date → should be excluded
            Car carB = createApprovedCar("Audi", "A6", "beograd");
            createBlockedDate(carB, searchStart.toLocalDate(), searchEnd.toLocalDate());

            // Car C: has booking OUTSIDE search range → should be included
            Car carC = createApprovedCar("Audi", "Q5", "beograd");
            createBlockingBooking(carC,
                    searchStart.minusDays(30),
                    searchStart.minusDays(27));

            // Car D: no bookings, no blocked dates → should be included
            createApprovedCar("Audi", "Q7", "beograd");
            return null;
        });

        AvailabilitySearchRequestDTO request = AvailabilitySearchRequestDTO.builder()
                .location("beograd")
                .startTime(searchStart)
                .endTime(searchEnd)
                .page(0)
                .size(50)
                .build();
        Page<Car> result = availabilityService.searchAvailableCars(request);

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent())
                .extracting(Car::getModel)
                .containsExactlyInAnyOrder("Q5", "Q7");
    }

    // ========== TC-BATCH-03: Half-open overlap semantics preserved ==========

    @Test
    @DisplayName("TC-BATCH-03: Exact boundary (booking ends when search starts) is NOT an overlap")
    void halfOpenOverlapSemantics() {
        LocalDateTime searchStart = LocalDateTime.now().plusDays(10).withHour(9).withMinute(0);
        LocalDateTime searchEnd = searchStart.plusDays(3);

        tx.execute(status -> {
            // Booking ends exactly when search starts → NO overlap (half-open interval)
            Car car = createApprovedCar("VW", "Golf", "beograd");
            createBlockingBooking(car,
                    searchStart.minusDays(3),
                    searchStart); // endTime == requestedStart → NOT overlapping
            return null;
        });

        AvailabilitySearchRequestDTO request = AvailabilitySearchRequestDTO.builder()
                .location("beograd")
                .startTime(searchStart)
                .endTime(searchEnd)
                .page(0)
                .size(50)
                .build();
        Page<Car> result = availabilityService.searchAvailableCars(request);

        assertThat(result.getTotalElements()).isEqualTo(1);
    }
}
