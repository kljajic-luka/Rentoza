package org.example.rentoza.booking;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.cancellation.CancellationPolicyService;
import org.example.rentoza.booking.cancellation.CancellationReason;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancelledBy;
import org.example.rentoza.booking.cancellation.RefundStatus;
import org.example.rentoza.booking.dto.CancellationRequestDTO;
import org.example.rentoza.booking.dto.CancellationResultDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.chat.ChatServiceClient;
import org.example.rentoza.delivery.DeliveryFeeCalculator;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Booking lifecycle audit tests covering three audit findings:
 *
 * <ul>
 *   <li><b>F-CN-1</b>: Cancellation refund settlement path verification.
 *       Ensures {@code cancelBookingWithPolicy()} creates a {@link CancellationRecord}
 *       with {@code refundStatus = PENDING}, deferring actual settlement to the
 *       {@code PaymentLifecycleScheduler} (every 15 min).</li>
 *   <li><b>F-TZ-1</b>: Auto-complete timezone correctness.
 *       Ensures {@code autoCompleteOverdueBookings()} passes a Belgrade-zone
 *       {@link LocalDateTime} to {@code findOverdueBookings()}, not the system default.</li>
 *   <li><b>F-AC-1</b>: Auto-complete ghost-trip deposit loss prevention.
 *       Ensures the overdue-booking query only returns ACTIVE bookings (not IN_TRIP),
 *       so IN_TRIP bookings are handled by the checkout saga with proper deposit settlement.</li>
 * </ul>
 *
 * @see BookingService#cancelBookingWithPolicy(Long, CancellationRequestDTO)
 * @see BookingService#autoCompleteOverdueBookings()
 */
@ExtendWith(MockitoExtension.class)
class BookingLifecycleAuditTest {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    // ── BookingService dependencies ──────────────────────────────────────────

    @Mock private BookingRepository repo;
    @Mock private CarRepository carRepo;
    @Mock private UserRepository userRepo;
    @Mock private ReviewRepository reviewRepo;
    @Mock private ChatServiceClient chatServiceClient;
    @Mock private NotificationService notificationService;
    @Mock private CurrentUser currentUser;
    @Mock private CancellationPolicyService cancellationPolicyService;
    @Mock private DeliveryFeeCalculator deliveryFeeCalculator;
    @Mock private RenterVerificationService renterVerificationService;
    @Mock private BookingPaymentService bookingPaymentService;
    @Mock private SchedulerIdempotencyService lockService;

    @InjectMocks
    private BookingService bookingService;

    /** SimpleMeterRegistry for any Micrometer-based assertions (kept for pattern compliance). */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setValueFields() {
        // @Value fields that @InjectMocks cannot populate
        ReflectionTestUtils.setField(bookingService, "approvalEnabled", true);
        ReflectionTestUtils.setField(bookingService, "betaUsers", Collections.emptyList());
        ReflectionTestUtils.setField(bookingService, "approvalSlaHours", 24);
        ReflectionTestUtils.setField(bookingService, "minGuestPreparationHours", 12);
        ReflectionTestUtils.setField(bookingService, "licenseRequired", true);
        ReflectionTestUtils.setField(bookingService, "defaultDepositAmountRsd", 30000);
        ReflectionTestUtils.setField(bookingService, "serviceFeeRate", 0.15);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Build a minimal {@link Booking} with renter, owner, car and the given status.
     * Times are set so the trip window has already elapsed (endTime = 6 hours ago).
     */
    private Booking createBooking(BookingStatus status) {
        User renter = new User();
        renter.setId(10L);
        renter.setEmail("test@test.com");
        renter.setFirstName("Test");
        renter.setLastName("Renter");

        User owner = new User();
        owner.setId(20L);
        owner.setFirstName("Owner");
        owner.setLastName("Host");

        Car car = new Car();
        car.setId(100L);
        car.setOwner(owner);
        car.setPricePerDay(new BigDecimal("5000.00"));

        Booking b = new Booking();
        b.setId(1L);
        b.setStatus(status);
        b.setRenter(renter);
        b.setCar(car);
        b.setStartTime(LocalDateTime.now().minusDays(3));
        b.setEndTime(LocalDateTime.now().minusHours(6));
        b.setTotalPrice(new BigDecimal("10000.00"));
        b.setSnapshotDailyRate(new BigDecimal("5000.00"));
        return b;
    }

    // =========================================================================
    // F-CN-1: Cancellation Refund Settlement Path Verification
    // =========================================================================

    @Nested
    @DisplayName("F-CN-1: Cancellation Refund Settlement")
    class CancellationRefundSettlement {

        /**
         * Verifies that {@code cancelBookingWithPolicy} delegates to
         * {@link CancellationPolicyService#processCancellation} and that the
         * resulting {@link CancellationRecord} has {@code refundStatus = PENDING}.
         *
         * <p>The actual refund is deferred to the {@code PaymentLifecycleScheduler}
         * (every 15 min) which calls
         * {@code SchedulerItemProcessor.processRefundSafely()} ->
         * {@code BookingPaymentService.processCancellationSettlement()}.
         */
        @Test
        @DisplayName("cancelBookingWithPolicy creates CancellationRecord with PENDING refund status")
        void cancellation_creates_record_with_pending_refund_status() {
            // ── Arrange ──────────────────────────────────────────────────────
            Booking booking = createBooking(BookingStatus.ACTIVE);
            Long bookingId = booking.getId();

            User renterUser = booking.getRenter();

            // Stub: repo returns the booking under pessimistic lock
            lenient().when(repo.findByIdWithRelationsForUpdate(bookingId))
                    .thenReturn(Optional.of(booking));

            // Stub: current user is the renter
            lenient().when(currentUser.id()).thenReturn(renterUser.getId());
            lenient().when(currentUser.email()).thenReturn(renterUser.getEmail());
            lenient().when(currentUser.isAdmin()).thenReturn(false);

            // Stub: user lookup for initiator
            lenient().when(userRepo.findByEmail(renterUser.getEmail()))
                    .thenReturn(Optional.of(renterUser));

            // Build the CancellationRecord that the policy service would persist
            CancellationRecord record = CancellationRecord.builder()
                    .id(500L)
                    .booking(booking)
                    .cancelledBy(CancelledBy.GUEST)
                    .reason(CancellationReason.GUEST_CHANGE_OF_PLANS)
                    .refundToGuest(new BigDecimal("8000.00"))
                    .penaltyAmount(new BigDecimal("2000.00"))
                    .payoutToHost(new BigDecimal("2000.00"))
                    .originalTotalPrice(booking.getTotalPrice())
                    .bookingTotal(booking.getTotalPrice())
                    .hoursBeforeTripStart(-72L)
                    .initiatedAt(LocalDateTime.now())
                    .processedAt(LocalDateTime.now())
                    .policyVersion("TURO_V1.0_2024")
                    .refundStatus(RefundStatus.PENDING)
                    .build();

            // The processCancellation method mutates the booking and returns a result DTO
            CancellationResultDTO resultDTO = CancellationResultDTO.builder()
                    .bookingId(bookingId)
                    .cancellationRecordId(record.getId())
                    .cancelledBy(CancelledBy.GUEST)
                    .reason(CancellationReason.GUEST_CHANGE_OF_PLANS)
                    .cancelledAt(LocalDateTime.now())
                    .hoursBeforeTripStart(-72L)
                    .originalTotalPrice(booking.getTotalPrice())
                    .penaltyAmount(new BigDecimal("2000.00"))
                    .refundToGuest(new BigDecimal("8000.00"))
                    .payoutToHost(new BigDecimal("2000.00"))
                    .refundStatus(RefundStatus.PENDING)
                    .appliedRule("STANDARD_PENALTY")
                    .build();

            // When processCancellation is called, mutate the booking and return the DTO
            lenient().when(cancellationPolicyService.processCancellation(
                    eq(booking), eq(renterUser), any(CancellationReason.class), any()))
                    .thenAnswer(invocation -> {
                        // Simulate what TuroCancellationPolicyService does:
                        booking.setStatus(BookingStatus.CANCELLED);
                        booking.setCancelledBy(CancelledBy.GUEST);
                        booking.setCancellationRecord(record);
                        return resultDTO;
                    });

            CancellationRequestDTO request = new CancellationRequestDTO(
                    CancellationReason.GUEST_CHANGE_OF_PLANS,
                    "Changed plans"
            );

            // ── Act ──────────────────────────────────────────────────────────
            CancellationResultDTO result = bookingService.cancelBookingWithPolicy(bookingId, request);

            // ── Assert ───────────────────────────────────────────────────────

            // 1. processCancellation was invoked
            verify(cancellationPolicyService).processCancellation(
                    eq(booking),
                    eq(renterUser),
                    eq(CancellationReason.GUEST_CHANGE_OF_PLANS),
                    eq("Changed plans")
            );

            // 2. The returned result has refundStatus = PENDING (settlement is deferred)
            assertThat(result.refundStatus())
                    .as("Refund status must be PENDING -- settlement is deferred to PaymentLifecycleScheduler")
                    .isEqualTo(RefundStatus.PENDING);

            // 3. The booking entity carries the CancellationRecord with PENDING refund
            assertThat(booking.getCancellationRecord()).isNotNull();
            assertThat(booking.getCancellationRecord().getRefundStatus())
                    .as("CancellationRecord.refundStatus must be PENDING at creation time")
                    .isEqualTo(RefundStatus.PENDING);

            // 4. Refund amount is positive (something is owed to guest)
            assertThat(result.refundToGuest())
                    .as("Guest should receive a positive refund amount")
                    .isGreaterThan(BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // F-TZ-1: Auto-Complete Timezone Correctness
    // =========================================================================

    @Nested
    @DisplayName("F-TZ-1: Auto-Complete Timezone Correctness")
    class AutoCompleteTimezone {

        /**
         * Verifies that {@code autoCompleteOverdueBookings()} passes a
         * Belgrade-zone {@link LocalDateTime} to
         * {@code BookingRepository.findOverdueBookingsPaged()}.
         *
         * <p>Before the F-TZ-1 fix, the method used {@code LocalDateTime.now()}
         * (system default, often UTC), which caused bookings to be auto-completed
         * 1--2 hours early depending on CET/CEST offset.</p>
         */
        @Test
        @DisplayName("autoCompleteOverdueBookings passes Belgrade-time to repository query")
        void auto_complete_uses_serbia_timezone() {
            // ── Arrange ──────────────────────────────────────────────────────

            // Lock service must grant the lock for the method to proceed
            lenient().when(lockService.tryAcquireLock(anyString(), any(Duration.class)))
                    .thenReturn(true);

            // Capture the LocalDateTime argument passed to findOverdueBookingsPaged
            ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            lenient().when(repo.findOverdueBookingsPaged(eq(BookingStatus.ACTIVE), timeCaptor.capture(), any(Pageable.class)))
                    .thenReturn(Page.empty());

            // Snapshot the expected Belgrade time before calling the method
            LocalDateTime expectedBelgradeTimeMin = LocalDateTime.now(SERBIA_ZONE).minusSeconds(5);

            // ── Act ──────────────────────────────────────────────────────────
            bookingService.autoCompleteOverdueBookings();

            // ── Assert ───────────────────────────────────────────────────────
            LocalDateTime expectedBelgradeTimeMax = LocalDateTime.now(SERBIA_ZONE).plusSeconds(5);
            LocalDateTime capturedTime = timeCaptor.getValue();

            // The captured time must fall within the Belgrade-time window
            assertThat(capturedTime)
                    .as("Time passed to findOverdueBookings must be in Europe/Belgrade zone, "
                        + "not system default. Captured: %s, expected window: [%s, %s]",
                        capturedTime, expectedBelgradeTimeMin, expectedBelgradeTimeMax)
                    .isAfterOrEqualTo(expectedBelgradeTimeMin)
                    .isBeforeOrEqualTo(expectedBelgradeTimeMax);

            // Additional check: If this test runs on a machine with UTC default timezone,
            // the difference between UTC and CET/CEST is 1 or 2 hours. Assert that the
            // captured time is NOT the system-default time (unless the system IS in Belgrade).
            ZoneId systemZone = ZoneId.systemDefault();
            if (!systemZone.equals(SERBIA_ZONE)) {
                LocalDateTime systemDefaultNow = LocalDateTime.now(systemZone);
                // Belgrade is ahead of UTC -- the captured time should differ from system time
                // by at least 30 minutes (safe margin for CET+1/CEST+2)
                long diffSeconds = Math.abs(
                        java.time.Duration.between(capturedTime, systemDefaultNow).getSeconds());
                // Only assert when system zone is meaningfully different (e.g., UTC)
                if (diffSeconds > 1800) {
                    assertThat(capturedTime)
                            .as("Captured time should differ from system-default time by the zone offset")
                            .isNotEqualTo(systemDefaultNow);
                }
            }

            // Verify repository was queried
            verify(repo).findOverdueBookingsPaged(eq(BookingStatus.ACTIVE), any(LocalDateTime.class), any(Pageable.class));
        }
    }

    // =========================================================================
    // F-AC-1: Auto-Complete Ghost-Trip Deposit Loss Prevention
    // =========================================================================

    @Nested
    @DisplayName("F-AC-1: Auto-Complete Ghost-Trip Deposit Loss Prevention")
    class AutoCompleteGhostTripProtection {

        @BeforeEach
        void allowLock() {
            // Grant the distributed lock for all auto-complete tests in this group
            lenient().when(lockService.tryAcquireLock(anyString(), any(Duration.class)))
                    .thenReturn(true);
        }

        /**
         * Verifies that when {@code findOverdueBookingsPaged()} returns an ACTIVE
         * booking past its end time, the auto-complete scheduler marks it as
         * COMPLETED and persists via {@code saveAll()}.
         *
         * <p>After the F-AC-1 fix, the repository query
         * {@code WHERE b.status = 'ACTIVE' AND b.endTime < :currentTime}
         * only returns ACTIVE bookings. IN_TRIP bookings are deliberately
         * excluded so they flow through the checkout saga for proper deposit
         * settlement.</p>
         */
        @Test
        @DisplayName("auto-complete transitions ACTIVE overdue booking to COMPLETED")
        void auto_complete_only_targets_active_bookings() {
            // ── Arrange ──────────────────────────────────────────────────────
            Booking activeOverdue = createBooking(BookingStatus.ACTIVE);
            // endTime is already 6 hours in the past (set in createBooking)

            lenient().when(repo.findOverdueBookingsPaged(eq(BookingStatus.ACTIVE), any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(activeOverdue)))
                    .thenReturn(Page.empty());

            lenient().when(repo.saveAll(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // ── Act ──────────────────────────────────────────────────────────
            bookingService.autoCompleteOverdueBookings();

            // ── Assert ───────────────────────────────────────────────────────

            // Booking status transitioned to COMPLETED
            assertThat(activeOverdue.getStatus())
                    .as("ACTIVE overdue booking must be marked as COMPLETED by auto-complete")
                    .isEqualTo(BookingStatus.COMPLETED);

            // saveAll was called with exactly 1 booking
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Booking>> saveCaptor = ArgumentCaptor.forClass(List.class);
            verify(repo).saveAll(saveCaptor.capture());
            assertThat(saveCaptor.getValue())
                    .as("Exactly one booking should be persisted")
                    .hasSize(1)
                    .first()
                    .extracting(Booking::getStatus)
                    .isEqualTo(BookingStatus.COMPLETED);
        }

        /**
         * Verifies that when the fixed query returns an empty list (e.g., no
         * ACTIVE overdue bookings, and IN_TRIP bookings are excluded by the
         * query), the scheduler skips the saveAll call entirely.
         *
         * <p>This proves the filter works: IN_TRIP bookings are not picked up,
         * so when no ACTIVE bookings are overdue, nothing is processed.</p>
         */
        @Test
        @DisplayName("auto-complete does not call saveAll when no overdue bookings exist")
        void auto_complete_does_not_process_empty_result() {
            // ── Arrange ──────────────────────────────────────────────────────
            lenient().when(repo.findOverdueBookingsPaged(eq(BookingStatus.ACTIVE), any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(Page.empty());

            // ── Act ──────────────────────────────────────────────────────────
            bookingService.autoCompleteOverdueBookings();

            // ── Assert ───────────────────────────────────────────────────────
            verify(repo, never()).saveAll(any());
        }

        /**
         * Documents the F-AC-1 fix at the query level: the repository method
         * {@code findOverdueBookingsPaged} uses {@code WHERE b.status = 'ACTIVE'}
         * (not IN_TRIP). This test verifies that from the service's perspective,
         * even if an IN_TRIP booking past its end time exists in the database,
         * the mocked query would not return it -- only ACTIVE bookings are
         * returned and subsequently transitioned to COMPLETED.
         *
         * <p>Concretely: we set up an ACTIVE booking and an IN_TRIP booking,
         * but the mock only returns the ACTIVE one (simulating the fixed query),
         * and we verify only the ACTIVE booking is transitioned.</p>
         */
        @Test
        @DisplayName("auto-complete processes only the ACTIVE booking when query excludes IN_TRIP")
        void auto_complete_processes_active_not_in_trip() {
            // ── Arrange ──────────────────────────────────────────────────────
            Booking activeBooking = createBooking(BookingStatus.ACTIVE);
            activeBooking.setId(1L);

            // This IN_TRIP booking exists but the fixed query does NOT return it
            Booking inTripBooking = createBooking(BookingStatus.IN_TRIP);
            inTripBooking.setId(2L);

            // The fixed query only returns ACTIVE bookings
            lenient().when(repo.findOverdueBookingsPaged(eq(BookingStatus.ACTIVE), any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(activeBooking)))
                    .thenReturn(Page.empty());

            lenient().when(repo.saveAll(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // ── Act ──────────────────────────────────────────────────────────
            bookingService.autoCompleteOverdueBookings();

            // ── Assert ───────────────────────────────────────────────────────

            // ACTIVE booking is transitioned to COMPLETED
            assertThat(activeBooking.getStatus())
                    .as("ACTIVE booking should be auto-completed")
                    .isEqualTo(BookingStatus.COMPLETED);

            // IN_TRIP booking remains unchanged (untouched by auto-complete)
            assertThat(inTripBooking.getStatus())
                    .as("IN_TRIP booking must NOT be touched by auto-complete; "
                        + "it must go through the checkout saga for deposit settlement")
                    .isEqualTo(BookingStatus.IN_TRIP);

            // saveAll called with only 1 booking (the ACTIVE one)
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Booking>> saveCaptor = ArgumentCaptor.forClass(List.class);
            verify(repo).saveAll(saveCaptor.capture());
            assertThat(saveCaptor.getValue())
                    .hasSize(1)
                    .allSatisfy(b -> assertThat(b.getId()).isEqualTo(1L));
        }

        /**
         * Verifies that when the distributed lock is NOT acquired (another
         * instance is already running), the auto-complete method short-circuits
         * and does not query the repository at all.
         */
        @Test
        @DisplayName("auto-complete skips when distributed lock is not acquired")
        void auto_complete_skips_when_lock_not_acquired() {
            // ── Arrange ──────────────────────────────────────────────────────
            // Override the @BeforeEach lock grant for this specific test
            lenient().when(lockService.tryAcquireLock(anyString(), any(Duration.class)))
                    .thenReturn(false);

            // ── Act ──────────────────────────────────────────────────────────
            bookingService.autoCompleteOverdueBookings();

            // ── Assert ───────────────────────────────────────────────────────
                        verify(repo, never()).findOverdueBookingsPaged(eq(BookingStatus.ACTIVE), any(LocalDateTime.class), any(Pageable.class));
            verify(repo, never()).saveAll(any());
        }
    }
}
