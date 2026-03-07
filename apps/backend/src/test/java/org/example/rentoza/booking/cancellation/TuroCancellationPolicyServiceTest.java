package org.example.rentoza.booking.cancellation;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.dto.CancellationPreviewDTO;
import org.example.rentoza.booking.dto.CancellationResultDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TuroCancellationPolicyService")
class TuroCancellationPolicyServiceTest {

    @Mock private CancellationRecordRepository cancellationRecordRepository;
    @Mock private HostCancellationStatsRepository hostCancellationStatsRepository;
    @Mock private CancellationSettlementService cancellationSettlementService;

    @InjectMocks
    private TuroCancellationPolicyService service;

    private User guest;
    private User host;
    private Booking booking;

    @BeforeEach
    void setUp() {
        guest = new User();
        guest.setId(11L);

        host = new User();
        host.setId(22L);

        Car car = new Car();
        car.setId(100L);
        car.setOwner(host);
        car.setPricePerDay(new BigDecimal("5000.00"));

        booking = new Booking();
        booking.setId(200L);
        booking.setCar(car);
        booking.setRenter(guest);
        booking.setStatus(BookingStatus.ACTIVE);
        booking.setTotalPrice(new BigDecimal("15000.00"));
        booking.setSnapshotDailyRate(new BigDecimal("5000.00"));
        booking.setStartTime(LocalDateTime.now().plusHours(48));
        booking.setEndTime(booking.getStartTime().plusDays(3));
        booking.setCreatedAt(LocalDateTime.now().minusDays(2));

    }

    @Test
    @DisplayName("guest preview over 24h returns full refund")
    void guestPreview_freeWindow_fullRefund() {
        CancellationPreviewDTO preview = service.generatePreview(booking, guest);

        assertThat(preview.canCancel()).isTrue();
        assertThat(preview.isWithinFreeWindow()).isTrue();
        assertThat(preview.penaltyAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(preview.refundToGuest()).isEqualByComparingTo(new BigDecimal("15000.00"));
    }

    @Test
    @DisplayName("guest preview under 24h short trip applies one-day penalty")
    void guestPreview_lateShortTrip_oneDayPenalty() {
        booking.setStartTime(LocalDateTime.now().plusHours(8));
        booking.setEndTime(booking.getStartTime().plusDays(1));
        booking.setTotalPrice(new BigDecimal("10000.00"));
        booking.setSnapshotDailyRate(new BigDecimal("5000.00"));
        booking.setCreatedAt(LocalDateTime.now().minusHours(2));

        CancellationPreviewDTO preview = service.generatePreview(booking, guest);

        assertThat(preview.canCancel()).isTrue();
        assertThat(preview.isWithinFreeWindow()).isFalse();
        assertThat(preview.isWithinRemorseWindow()).isFalse();
        assertThat(preview.tripDays()).isEqualTo(2);
        assertThat(preview.penaltyAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(preview.refundToGuest()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    @DisplayName("host preview is blocked when suspension is active")
    void hostPreview_blockedWhenSuspended() {
        HostCancellationStats stats = HostCancellationStats.builder()
                .hostId(host.getId())
                .suspensionEndsAt(LocalDateTime.now().plusDays(1))
                .build();
        when(hostCancellationStatsRepository.findById(host.getId())).thenReturn(Optional.of(stats));

        CancellationPreviewDTO preview = service.generatePreview(booking, host);

        assertThat(preview.canCancel()).isFalse();
        assertThat(preview.blockReason()).contains("suspended");
    }

    @Test
    @DisplayName("host cancellation applies tier 1 penalty and returns host metadata")
    void processCancellation_hostTierOnePenalty() {
                when(cancellationRecordRepository.existsByBookingId(booking.getId())).thenReturn(false);
        when(hostCancellationStatsRepository.findByIdForUpdate(host.getId())).thenReturn(Optional.empty());
        when(hostCancellationStatsRepository.save(any(HostCancellationStats.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CancellationRecord record = new CancellationRecord();
        record.setId(501L);
        when(cancellationSettlementService.beginSettlement(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(record);

        CancellationResultDTO result = service.processCancellation(
                booking,
                host,
                CancellationReason.HOST_VEHICLE_UNAVAILABLE,
                "maintenance issue");

        assertThat(result.cancelledBy()).isEqualTo(CancelledBy.HOST);
        assertThat(result.cancellationRecordId()).isEqualTo(501L);
        assertThat(result.hostPenaltyApplied()).isEqualByComparingTo(new BigDecimal("5500.00"));
        assertThat(result.hostNewTier()).isEqualTo(1);
        assertThat(result.hostSuspendedUntil()).isNull();
        verify(hostCancellationStatsRepository).save(any(HostCancellationStats.class));
    }

    @Test
    @DisplayName("host third cancellation applies suspension")
    void processCancellation_hostThirdTierSuspends() {
                when(cancellationRecordRepository.existsByBookingId(booking.getId())).thenReturn(false);
        HostCancellationStats stats = HostCancellationStats.builder()
                .hostId(host.getId())
                .cancellationsThisYear(2)
                .cancellationsLast30Days(2)
                .penaltyTier(2)
                .build();
        when(hostCancellationStatsRepository.findByIdForUpdate(host.getId())).thenReturn(Optional.of(stats));
        when(hostCancellationStatsRepository.save(any(HostCancellationStats.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CancellationRecord record = new CancellationRecord();
        record.setId(502L);
        when(cancellationSettlementService.beginSettlement(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(record);

        CancellationResultDTO result = service.processCancellation(
                booking,
                host,
                CancellationReason.HOST_VEHICLE_DAMAGE,
                "damage evidence uploaded");

        assertThat(result.hostPenaltyApplied()).isEqualByComparingTo(new BigDecimal("16500.00"));
        assertThat(result.hostNewTier()).isEqualTo(3);
        assertThat(result.hostSuspendedUntil()).isNotNull();
    }
}
