//package org.example.rentoza.booking.cancellation;
//
//import org.example.rentoza.booking.Booking;
//import org.example.rentoza.booking.BookingStatus;
//import org.example.rentoza.booking.dto.CancellationPreviewDTO;
//import org.example.rentoza.booking.dto.CancellationResultDTO;
//import org.example.rentoza.car.Car;
//import org.example.rentoza.user.User;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Nested;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.junit.jupiter.params.ParameterizedTest;
//import org.junit.jupiter.params.provider.CsvSource;
//import org.junit.jupiter.params.provider.ValueSource;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.math.BigDecimal;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.time.ZoneId;
//import java.util.Optional;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.anyLong;
//import static org.mockito.Mockito.*;
//
///**
// * Unit tests for TuroCancellationPolicyService.
// *
// * <h2>Cancellation Policy Rules</h2>
// * <ul>
// *   <li>Guest >24h before: 100% refund</li>
// *   <li>Guest <24h, remorse window (<1h since booking): 100% refund</li>
// *   <li>Guest <24h, short trip (≤2 days): penalty = 1 day rate</li>
// *   <li>Guest <24h, long trip (>2 days): penalty = 50%</li>
// *   <li>Host Tier 1: 5,500 RSD penalty</li>
// *   <li>Host Tier 2: 11,000 RSD penalty</li>
// *   <li>Host Tier 3+: 16,500 RSD + 7-day suspension</li>
// * </ul>
// */
//@ExtendWith(MockitoExtension.class)
//class TuroCancellationPolicyServiceTest {
//
//    private static final ZoneId BELGRADE_ZONE = ZoneId.of("Europe/Belgrade");
//
//    @Mock
//    private CancellationRecordRepository cancellationRecordRepository;
//
//    @Mock
//    private HostCancellationStatsRepository hostCancellationStatsRepository;
//
//    @InjectMocks
//    private TuroCancellationPolicyService cancellationPolicyService;
//
//    private User guest;
//    private User host;
//    private Car car;
//    private Booking booking;
//
//    @BeforeEach
//    void setUp() {
//        // Create test guest
//        guest = new User();
//        guest.setId(1L);
//        guest.setEmail("guest@test.com");
//        guest.setFirstName("Test");
//        guest.setLastName("Guest");
//
//        // Create test host
//        host = new User();
//        host.setId(2L);
//        host.setEmail("host@test.com");
//        host.setFirstName("Test");
//        host.setLastName("Host");
//
//        // Create test car
//        car = new Car();
//        car.setId(1L);
//        car.setOwner(host);
//        car.setPricePerDay(BigDecimal.valueOf(5000)); // 5000 RSD/day
//
//        // Create test booking
//        booking = new Booking();
//        booking.setId(100L);
//        booking.setCar(car);
//        booking.setRenter(guest);
//        booking.setStatus(BookingStatus.ACTIVE);
//        booking.setTotalPrice(BigDecimal.valueOf(15000)); // 3 days at 5000/day
//        booking.setSnapshotDailyRate(BigDecimal.valueOf(5000));
//    }
//
//    // ========================================================================
//    // GUEST CANCELLATION TESTS
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Guest Cancellation - Free Window (>24h)")
//    class GuestFreeCancellationTests {
//
//        @Test
//        @DisplayName("Should give 100% refund when cancelling >24h before trip")
//        void shouldGive100PercentRefundWhenCancellingMoreThan24HoursBeforeTrip() {
//            // Given: Trip starts in 48 hours
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(48);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(3));
//            booking.setCreatedAt(LocalDateTime.now().minusDays(1));
//
//            // When: Generate cancellation preview
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then: Full refund
//            assertThat(preview.allowed()).isTrue();
//            assertThat(preview.isWithinFreeWindow()).isTrue();
//            assertThat(preview.refundToGuest()).isEqualByComparingTo(BigDecimal.valueOf(15000));
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.ZERO);
//        }
//
//        @ParameterizedTest(name = "Hours until trip: {0}")
//        @ValueSource(ints = {25, 36, 48, 72, 168})
//        @DisplayName("Should give 100% refund for various times >24h before trip")
//        void shouldGive100PercentRefundForVariousTimesOverThreshold(int hoursUntilTrip) {
//            // Given: Trip starts in X hours
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(hoursUntilTrip);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(3));
//            booking.setCreatedAt(LocalDateTime.now().minusDays(1));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then
//            assertThat(preview.isWithinFreeWindow()).isTrue();
//            assertThat(preview.refundToGuest()).isEqualByComparingTo(booking.getTotalPrice());
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.ZERO);
//        }
//
//        @Test
//        @DisplayName("Should give 100% refund at exactly 24 hours before trip")
//        void shouldGive100PercentRefundAtExactly24HoursBeforeTrip() {
//            // Given: Trip starts in exactly 24 hours
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(24);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(2));
//            booking.setCreatedAt(LocalDateTime.now().minusDays(1));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then: Edge case - exactly 24h is within free window
//            assertThat(preview.isWithinFreeWindow()).isTrue();
//            assertThat(preview.refundToGuest()).isEqualByComparingTo(booking.getTotalPrice());
//        }
//    }
//
//    @Nested
//    @DisplayName("Guest Cancellation - Remorse Window (<1h since booking)")
//    class GuestRemorseWindowTests {
//
//        @Test
//        @DisplayName("Should give 100% refund within remorse window even if <24h before trip")
//        void shouldGive100PercentRefundWithinRemorseWindowEvenIfLessThan24HoursBeforeTrip() {
//            // Given: Trip starts in 6 hours, but booking was created 30 minutes ago
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(6);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(2));
//            booking.setCreatedAt(LocalDateTime.now().minusMinutes(30)); // Within 1h remorse
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then: Remorse window applies
//            assertThat(preview.allowed()).isTrue();
//            assertThat(preview.isWithinRemorseWindow()).isTrue();
//            assertThat(preview.refundToGuest()).isEqualByComparingTo(booking.getTotalPrice());
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.ZERO);
//        }
//
//        @ParameterizedTest(name = "Minutes since booking: {0}")
//        @ValueSource(ints = {5, 15, 30, 45, 59})
//        @DisplayName("Should apply remorse window for various times <1h since booking")
//        void shouldApplyRemorseWindowForVariousTimesUnder1Hour(int minutesSinceBooking) {
//            // Given: Trip in 6 hours, booking created X minutes ago
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(6);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(2));
//            booking.setCreatedAt(LocalDateTime.now().minusMinutes(minutesSinceBooking));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then
//            assertThat(preview.isWithinRemorseWindow()).isTrue();
//            assertThat(preview.refundToGuest()).isEqualByComparingTo(booking.getTotalPrice());
//        }
//
//        @Test
//        @DisplayName("Should NOT apply remorse window after 1 hour since booking")
//        void shouldNotApplyRemorseWindowAfter1HourSinceBooking() {
//            // Given: Trip in 6 hours, booking created 2 hours ago
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(6);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(2)); // 2-day trip (short)
//            booking.setCreatedAt(LocalDateTime.now().minusHours(2)); // Past remorse window
//            booking.setTotalPrice(BigDecimal.valueOf(10000)); // 2 days at 5000
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then: No remorse, apply penalty rules
//            assertThat(preview.isWithinFreeWindow()).isFalse();
//            assertThat(preview.isWithinRemorseWindow()).isFalse();
//            // Short trip <24h: penalty = 1 day rate
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
//        }
//    }
//
//    @Nested
//    @DisplayName("Guest Cancellation - Late (<24h) Short Trip")
//    class GuestLateCancellationShortTripTests {
//
//        @Test
//        @DisplayName("Should charge 1-day penalty for short trip (<24h before)")
//        void shouldCharge1DayPenaltyForShortTripLateCancellation() {
//            // Given: 2-day trip starting in 12 hours, booked 2 days ago
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(12);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(2));
//            booking.setCreatedAt(LocalDateTime.now().minusDays(2));
//            booking.setTotalPrice(BigDecimal.valueOf(10000)); // 2 days
//            booking.setSnapshotDailyRate(BigDecimal.valueOf(5000));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then: 1-day penalty (5000), refund = 10000 - 5000 = 5000
//            assertThat(preview.isWithinFreeWindow()).isFalse();
//            assertThat(preview.tripDays()).isEqualTo(2);
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
//            assertThat(preview.refundToGuest()).isEqualByComparingTo(BigDecimal.valueOf(5000));
//        }
//
//        @Test
//        @DisplayName("Should charge 1-day penalty for 1-day trip (minimum)")
//        void shouldCharge1DayPenaltyFor1DayTrip() {
//            // Given: 1-day trip starting in 6 hours
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(6);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(1));
//            booking.setCreatedAt(LocalDateTime.now().minusDays(1));
//            booking.setTotalPrice(BigDecimal.valueOf(5000)); // 1 day
//            booking.setSnapshotDailyRate(BigDecimal.valueOf(5000));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then: Full penalty (no refund) for 1-day trip
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
//            assertThat(preview.refundToGuest()).isEqualByComparingTo(BigDecimal.ZERO);
//        }
//    }
//
//    @Nested
//    @DisplayName("Guest Cancellation - Late (<24h) Long Trip")
//    class GuestLateCancellationLongTripTests {
//
//        @Test
//        @DisplayName("Should charge 50% penalty for long trip (>2 days)")
//        void shouldCharge50PercentPenaltyForLongTrip() {
//            // Given: 5-day trip starting in 12 hours
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(12);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(5));
//            booking.setCreatedAt(LocalDateTime.now().minusDays(3));
//            booking.setTotalPrice(BigDecimal.valueOf(25000)); // 5 days at 5000
//            booking.setSnapshotDailyRate(BigDecimal.valueOf(5000));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then: 50% penalty
//            assertThat(preview.tripDays()).isGreaterThan(2);
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.valueOf(12500)); // 50%
//            assertThat(preview.refundToGuest()).isEqualByComparingTo(BigDecimal.valueOf(12500)); // 50%
//        }
//
//        @Test
//        @DisplayName("Should charge 50% penalty for exactly 3-day trip (boundary)")
//        void shouldCharge50PercentPenaltyForExactly3DayTrip() {
//            // Given: 3-day trip (just over threshold)
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(6);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(3));
//            booking.setCreatedAt(LocalDateTime.now().minusDays(2));
//            booking.setTotalPrice(BigDecimal.valueOf(15000)); // 3 days
//            booking.setSnapshotDailyRate(BigDecimal.valueOf(5000));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then
//            assertThat(preview.tripDays()).isEqualTo(3);
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.valueOf(7500)); // 50%
//            assertThat(preview.refundToGuest()).isEqualByComparingTo(BigDecimal.valueOf(7500)); // 50%
//        }
//    }
//
//    // ========================================================================
//    // HOST CANCELLATION TESTS
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Host Cancellation - Penalty Tiers")
//    class HostCancellationPenaltyTests {
//
//        @Test
//        @DisplayName("Should charge Tier 1 penalty (5,500 RSD) for first cancellation")
//        void shouldChargeTier1PenaltyForFirstCancellation() {
//            // Given: Host has no previous cancellations this year
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusDays(5);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(3));
//
//            when(hostCancellationStatsRepository.findById(host.getId()))
//                    .thenReturn(Optional.empty());
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, host);
//
//            // Then: Tier 1 penalty
//            assertThat(preview.allowed()).isTrue();
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.valueOf(5500));
//            assertThat(preview.refundToGuest()).isEqualByComparingTo(booking.getTotalPrice());
//        }
//
//        @Test
//        @DisplayName("Should charge Tier 2 penalty (11,000 RSD) for second cancellation")
//        void shouldChargeTier2PenaltyForSecondCancellation() {
//            // Given: Host has 1 cancellation this year
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusDays(5);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(3));
//
//            HostCancellationStats stats = new HostCancellationStats();
//            stats.setHostId(host.getId());
//            stats.setCancellationsThisYear(1);
//            when(hostCancellationStatsRepository.findById(host.getId()))
//                    .thenReturn(Optional.of(stats));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, host);
//
//            // Then: Tier 2 penalty
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.valueOf(11000));
//        }
//
//        @Test
//        @DisplayName("Should charge Tier 3 penalty (16,500 RSD) for third+ cancellation")
//        void shouldChargeTier3PenaltyForThirdCancellation() {
//            // Given: Host has 2+ cancellations this year
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusDays(5);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(3));
//
//            HostCancellationStats stats = new HostCancellationStats();
//            stats.setHostId(host.getId());
//            stats.setCancellationsThisYear(2);
//            when(hostCancellationStatsRepository.findById(host.getId()))
//                    .thenReturn(Optional.of(stats));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, host);
//
//            // Then: Tier 3 penalty
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.valueOf(16500));
//        }
//    }
//
//    @Nested
//    @DisplayName("Host Cancellation - Suspension")
//    class HostCancellationSuspensionTests {
//
//        @Test
//        @DisplayName("Should block cancellation when host is suspended")
//        void shouldBlockCancellationWhenHostIsSuspended() {
//            // Given: Host is suspended
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusDays(5);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(3));
//
//            HostCancellationStats stats = new HostCancellationStats();
//            stats.setHostId(host.getId());
//            stats.setSuspensionEndsAt(LocalDateTime.now().plusDays(5));
//            when(hostCancellationStatsRepository.findById(host.getId()))
//                    .thenReturn(Optional.of(stats));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, host);
//
//            // Then: Blocked
//            assertThat(preview.allowed()).isFalse();
//            assertThat(preview.blockReason()).contains("suspended");
//        }
//
//        @Test
//        @DisplayName("Should allow cancellation after suspension ends")
//        void shouldAllowCancellationAfterSuspensionEnds() {
//            // Given: Host's suspension has ended
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusDays(5);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(3));
//
//            HostCancellationStats stats = new HostCancellationStats();
//            stats.setHostId(host.getId());
//            stats.setSuspensionEndsAt(LocalDateTime.now().minusDays(1)); // Past
//            stats.setCancellationsThisYear(3);
//            when(hostCancellationStatsRepository.findById(host.getId()))
//                    .thenReturn(Optional.of(stats));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, host);
//
//            // Then: Allowed (suspension ended)
//            assertThat(preview.allowed()).isTrue();
//        }
//    }
//
//    // ========================================================================
//    // EDGE CASES & BOUNDARY TESTS
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Edge Cases")
//    class EdgeCaseTests {
//
//        @Test
//        @DisplayName("Should handle booking created exactly 1 hour ago (edge of remorse)")
//        void shouldHandleBookingCreatedExactly1HourAgo() {
//            // Given: Created exactly at remorse boundary
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(6);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(2));
//            booking.setCreatedAt(LocalDateTime.now().minusHours(1)); // Exactly 1h
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then: At boundary, remorse should not apply (past 1h)
//            assertThat(preview.isWithinRemorseWindow()).isFalse();
//        }
//
//        @Test
//        @DisplayName("Should handle zero-value booking gracefully")
//        void shouldHandleZeroValueBooking() {
//            // Given: Free booking (promo)
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(6);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(2));
//            booking.setCreatedAt(LocalDateTime.now().minusDays(1));
//            booking.setTotalPrice(BigDecimal.ZERO);
//            booking.setSnapshotDailyRate(BigDecimal.ZERO);
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then: No error, zero refund
//            assertThat(preview.allowed()).isTrue();
//            assertThat(preview.refundToGuest()).isEqualByComparingTo(BigDecimal.ZERO);
//            assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.ZERO);
//        }
//
//        @Test
//        @DisplayName("Should use policy version constant")
//        void shouldUsePolicyVersionConstant() {
//            // When
//            String version = cancellationPolicyService.getPolicyVersion();
//
//            // Then
//            assertThat(version).isEqualTo("TURO_V1.0_2024");
//        }
//    }
//
//    @Nested
//    @DisplayName("Refund Calculation Matrix")
//    class RefundCalculationMatrixTests {
//
//        @ParameterizedTest(name = "hoursBeforeTrip={0}, tripDays={1}, minutesSinceBooking={2} → penalty={3}")
//        @CsvSource({
//            // hoursBeforeTrip, tripDays, minutesSinceBooking, expectedPenaltyPercent
//            "48, 3, 120, 0",       // >24h before = free
//            "24, 3, 120, 0",       // =24h before = free
//            "23, 3, 120, 50",      // <24h, long trip = 50%
//            "12, 5, 120, 50",      // <24h, long trip = 50%
//            "6, 2, 120, 20",       // <24h, short trip = 1 day (≈20% of 2-day trip)
//            "6, 1, 120, 100",      // <24h, 1-day trip = 100% (1 day penalty = full trip)
//            "6, 3, 30, 0",         // <24h but remorse window = free
//            "12, 5, 45, 0",        // <24h but remorse window = free
//        })
//        void shouldCalculateCorrectPenaltyBasedOnRules(
//                int hoursBeforeTrip, int tripDays, int minutesSinceBooking, int expectedPenaltyPercent) {
//            // Given
//            LocalDateTime tripStart = LocalDateTime.now(BELGRADE_ZONE).plusHours(hoursBeforeTrip);
//            booking.setStartTime(tripStart);
//            booking.setEndTime(tripStart.plusDays(tripDays));
//            booking.setCreatedAt(LocalDateTime.now().minusMinutes(minutesSinceBooking));
//            booking.setTotalPrice(BigDecimal.valueOf(tripDays * 5000L));
//            booking.setSnapshotDailyRate(BigDecimal.valueOf(5000));
//
//            // When
//            CancellationPreviewDTO preview = cancellationPolicyService.generatePreview(booking, guest);
//
//            // Then
//            BigDecimal expectedPenalty = booking.getTotalPrice()
//                    .multiply(BigDecimal.valueOf(expectedPenaltyPercent))
//                    .divide(BigDecimal.valueOf(100));
//
//            // For short trips, penalty is capped at 1-day rate
//            if (hoursBeforeTrip < 24 && tripDays <= 2 && minutesSinceBooking > 60) {
//                expectedPenalty = BigDecimal.valueOf(5000); // 1 day rate
//            }
//
//            assertThat(preview.penaltyAmount())
//                    .as("Penalty for %dh before, %d days, %dmin since booking",
//                            hoursBeforeTrip, tripDays, minutesSinceBooking)
//                    .isEqualByComparingTo(expectedPenalty);
//        }
//    }
//}
