package org.example.rentoza.owner;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.owner.dto.OwnerPayoutsDTO;
import org.example.rentoza.payment.PayoutLedger;
import org.example.rentoza.payment.PayoutLedgerRepository;
import org.example.rentoza.payment.PayoutLifecycleStatus;
import org.example.rentoza.user.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OwnerService.getOwnerPayouts().
 *
 * Covers:
 * - Empty ledger (no payouts for host)
 * - Single payout with full booking enrichment
 * - Multiple payouts with batch fetch
 * - Missing booking graceful degradation
 * - DTO field mapping from PayoutLedger entity
 */
@ExtendWith(MockitoExtension.class)
class OwnerServicePayoutsTest {

    @Mock
    private PayoutLedgerRepository payoutLedgerRepo;

    @Mock
    private BookingRepository bookingRepo;

    private OwnerService ownerService;

    private static final Long HOST_ID = 42L;

    @BeforeEach
    void setUp() {
        // OwnerService uses constructor injection via @RequiredArgsConstructor.
        // We inject only the mocks needed for getOwnerPayouts; others can be null
        // since getOwnerPayouts doesn't touch them.
        ownerService = new OwnerService(
                null,  // userRepo — not used by getOwnerPayouts
                null,  // carRepo
                bookingRepo,
                null,  // reviewRepo
                null,  // cancellationStatsRepo
                payoutLedgerRepo
        );
    }

    // ───────── helpers ─────────

    private PayoutLedger buildLedger(Long bookingId, PayoutLifecycleStatus status,
                                      BigDecimal tripAmount, BigDecimal platformFee, BigDecimal hostPayout) {
        PayoutLedger ledger = new PayoutLedger();
        ReflectionTestUtils.setField(ledger, "bookingId", bookingId);
        ReflectionTestUtils.setField(ledger, "hostUserId", HOST_ID);
        ReflectionTestUtils.setField(ledger, "status", status);
        ReflectionTestUtils.setField(ledger, "tripAmount", tripAmount);
        ReflectionTestUtils.setField(ledger, "platformFee", platformFee);
        ReflectionTestUtils.setField(ledger, "hostPayoutAmount", hostPayout);
        ReflectionTestUtils.setField(ledger, "attemptCount", 0);
        ReflectionTestUtils.setField(ledger, "maxAttempts", 3);
        return ledger;
    }

    private Booking buildBooking(Long id, String carBrand, String carModel,
                                  String renterFirst, String renterLast) {
        Car car = new Car();
        ReflectionTestUtils.setField(car, "brand", carBrand);
        ReflectionTestUtils.setField(car, "model", carModel);

        User renter = new User();
        ReflectionTestUtils.setField(renter, "firstName", renterFirst);
        ReflectionTestUtils.setField(renter, "lastName", renterLast);

        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", id);
        ReflectionTestUtils.setField(booking, "car", car);
        ReflectionTestUtils.setField(booking, "renter", renter);
        ReflectionTestUtils.setField(booking, "startTime", LocalDateTime.of(2025, 7, 1, 10, 0));
        ReflectionTestUtils.setField(booking, "endTime", LocalDateTime.of(2025, 7, 3, 10, 0));
        ReflectionTestUtils.setField(booking, "status", BookingStatus.COMPLETED);
        return booking;
    }

    // ───────── tests ─────────

    @Nested
    @DisplayName("Empty payouts")
    class EmptyPayouts {

        @Test
        @DisplayName("returns empty list when no ledger entries exist for host")
        void returnsEmptyWhenNoLedgers() {
            when(payoutLedgerRepo.findByHostUserId(HOST_ID)).thenReturn(List.of());

            OwnerPayoutsDTO result = ownerService.getOwnerPayouts(HOST_ID);

            assertThat(result.getPayouts()).isEmpty();
            verify(bookingRepo, never()).findAllById(anyList());
        }
    }

    @Nested
    @DisplayName("Single payout")
    class SinglePayout {

        @Test
        @DisplayName("maps PayoutLedger fields to DTO correctly")
        void mapsFieldsCorrectly() {
            PayoutLedger ledger = buildLedger(100L, PayoutLifecycleStatus.ELIGIBLE,
                    new BigDecimal("10000"), new BigDecimal("1500"), new BigDecimal("8500"));
            Instant eligibleAt = Instant.parse("2025-07-05T12:00:00Z");
            ReflectionTestUtils.setField(ledger, "eligibleAt", eligibleAt);

            Booking booking = buildBooking(100L, "BMW", "X5", "Marko", "Petrovic");

            when(payoutLedgerRepo.findByHostUserId(HOST_ID)).thenReturn(List.of(ledger));
            when(bookingRepo.findAllById(List.of(100L))).thenReturn(List.of(booking));

            OwnerPayoutsDTO result = ownerService.getOwnerPayouts(HOST_ID);

            assertThat(result.getPayouts()).hasSize(1);
            OwnerPayoutsDTO.BookingPayoutStatusDTO dto = result.getPayouts().get(0);
            assertThat(dto.getBookingId()).isEqualTo(100L);
            assertThat(dto.getCarBrand()).isEqualTo("BMW");
            assertThat(dto.getCarModel()).isEqualTo("X5");
            assertThat(dto.getGuestName()).isEqualTo("Marko Petrovic");
            assertThat(dto.getPayoutStatus()).isEqualTo(PayoutLifecycleStatus.ELIGIBLE);
            assertThat(dto.getTripAmount()).isEqualByComparingTo("10000");
            assertThat(dto.getPlatformFee()).isEqualByComparingTo("1500");
            assertThat(dto.getHostPayoutAmount()).isEqualByComparingTo("8500");
            assertThat(dto.getEligibleAt()).isEqualTo(eligibleAt.toString());
            assertThat(dto.getAttemptCount()).isZero();
            assertThat(dto.getMaxAttempts()).isEqualTo(3);
        }

        @Test
        @DisplayName("populates trip start/end times from booking")
        void populatesTripTimes() {
            PayoutLedger ledger = buildLedger(200L, PayoutLifecycleStatus.COMPLETED,
                    new BigDecimal("5000"), new BigDecimal("750"), new BigDecimal("4250"));
            Booking booking = buildBooking(200L, "Fiat", "500", "Ana", "Jovanovic");

            when(payoutLedgerRepo.findByHostUserId(HOST_ID)).thenReturn(List.of(ledger));
            when(bookingRepo.findAllById(List.of(200L))).thenReturn(List.of(booking));

            OwnerPayoutsDTO result = ownerService.getOwnerPayouts(HOST_ID);

            OwnerPayoutsDTO.BookingPayoutStatusDTO dto = result.getPayouts().get(0);
            assertThat(dto.getTripStartTime()).contains("2025-07-01");
            assertThat(dto.getTripEndTime()).contains("2025-07-03");
        }
    }

    @Nested
    @DisplayName("Missing booking enrichment")
    class MissingBooking {

        @Test
        @DisplayName("uses empty strings when booking not found")
        void gracefulDegradationWhenBookingMissing() {
            PayoutLedger ledger = buildLedger(999L, PayoutLifecycleStatus.PENDING,
                    new BigDecimal("7000"), new BigDecimal("1050"), new BigDecimal("5950"));

            when(payoutLedgerRepo.findByHostUserId(HOST_ID)).thenReturn(List.of(ledger));
            when(bookingRepo.findAllById(List.of(999L))).thenReturn(List.of()); // no booking found

            OwnerPayoutsDTO result = ownerService.getOwnerPayouts(HOST_ID);

            assertThat(result.getPayouts()).hasSize(1);
            OwnerPayoutsDTO.BookingPayoutStatusDTO dto = result.getPayouts().get(0);
            assertThat(dto.getBookingId()).isEqualTo(999L);
            assertThat(dto.getCarBrand()).isEmpty();
            assertThat(dto.getCarModel()).isEmpty();
            assertThat(dto.getGuestName()).isEmpty();
            assertThat(dto.getTripStartTime()).isEmpty();
            assertThat(dto.getTripEndTime()).isEmpty();
            // Financial fields still populated from ledger
            assertThat(dto.getTripAmount()).isEqualByComparingTo("7000");
        }
    }

    @Nested
    @DisplayName("Multiple payouts")
    class MultiplePayouts {

        @Test
        @DisplayName("batch-fetches bookings and maps all ledger entries")
        void batchFetchesAndMapsAll() {
            PayoutLedger ledger1 = buildLedger(10L, PayoutLifecycleStatus.COMPLETED,
                    new BigDecimal("8000"), new BigDecimal("1200"), new BigDecimal("6800"));
            PayoutLedger ledger2 = buildLedger(20L, PayoutLifecycleStatus.FAILED,
                    new BigDecimal("12000"), new BigDecimal("1800"), new BigDecimal("10200"));
            ReflectionTestUtils.setField(ledger2, "lastError", "Bank timeout");
            ReflectionTestUtils.setField(ledger2, "attemptCount", 2);

            Booking booking1 = buildBooking(10L, "VW", "Golf", "Petar", "Nikolic");
            Booking booking2 = buildBooking(20L, "Audi", "A4", "Jelena", "Markovic");

            when(payoutLedgerRepo.findByHostUserId(HOST_ID)).thenReturn(List.of(ledger1, ledger2));
            when(bookingRepo.findAllById(List.of(10L, 20L))).thenReturn(List.of(booking1, booking2));

            OwnerPayoutsDTO result = ownerService.getOwnerPayouts(HOST_ID);

            assertThat(result.getPayouts()).hasSize(2);

            // Verify failed payout carries error info
            OwnerPayoutsDTO.BookingPayoutStatusDTO failedDto = result.getPayouts().stream()
                    .filter(d -> d.getPayoutStatus() == PayoutLifecycleStatus.FAILED)
                    .findFirst()
                    .orElseThrow();
            assertThat(failedDto.getLastError()).isEqualTo("Bank timeout");
            assertThat(failedDto.getAttemptCount()).isEqualTo(2);
            assertThat(failedDto.getCarBrand()).isEqualTo("Audi");
        }
    }

    @Nested
    @DisplayName("DTO fromLedger factory")
    class FromLedgerFactory {

        @Test
        @DisplayName("handles null eligibleAt and nextRetryAt gracefully")
        void handlesNullTimestamps() {
            PayoutLedger ledger = buildLedger(300L, PayoutLifecycleStatus.PENDING,
                    new BigDecimal("6000"), new BigDecimal("900"), new BigDecimal("5100"));
            // eligibleAt and nextRetryAt are null by default

            OwnerPayoutsDTO.BookingPayoutStatusDTO dto =
                    OwnerPayoutsDTO.BookingPayoutStatusDTO.fromLedger(
                            ledger, "Toyota", "Yaris", "Guest Name", "2025-08-01T10:00", "2025-08-03T10:00");

            assertThat(dto.getEligibleAt()).isNull();
            assertThat(dto.getNextRetryAt()).isNull();
            assertThat(dto.getCarBrand()).isEqualTo("Toyota");
        }

        @Test
        @DisplayName("formats retry timestamps as ISO-8601 strings")
        void formatsRetryTimestamps() {
            PayoutLedger ledger = buildLedger(400L, PayoutLifecycleStatus.FAILED,
                    new BigDecimal("9000"), new BigDecimal("1350"), new BigDecimal("7650"));
            Instant nextRetry = Instant.parse("2025-08-10T14:00:00Z");
            ReflectionTestUtils.setField(ledger, "nextRetryAt", nextRetry);
            ReflectionTestUtils.setField(ledger, "lastError", "Connection refused");

            OwnerPayoutsDTO.BookingPayoutStatusDTO dto =
                    OwnerPayoutsDTO.BookingPayoutStatusDTO.fromLedger(
                            ledger, "", "", "", "", "");

            assertThat(dto.getNextRetryAt()).isEqualTo("2025-08-10T14:00:00Z");
            assertThat(dto.getLastError()).isEqualTo("Connection refused");
        }
    }
}
