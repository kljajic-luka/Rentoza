package org.example.rentoza.booking.cancellation;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.dto.CancellationResultDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TuroCancellationPolicyPendingApprovalFairnessTest {

    @Mock private CancellationRecordRepository cancellationRecordRepository;
    @Mock private HostCancellationStatsRepository hostCancellationStatsRepository;

    private TuroCancellationPolicyService service;

    @BeforeEach
    void setUp() {
        service = new TuroCancellationPolicyService(cancellationRecordRepository, hostCancellationStatsRepository);
    }

    @Test
    void guestCancellationWhilePendingApproval_hasFullRefundAndZeroPenalty() {
        User guest = new User();
        guest.setId(1L);

        User owner = new User();
        owner.setId(2L);

        Car car = new Car();
        car.setId(10L);
        car.setOwner(owner);

        Booking booking = new Booking();
        booking.setId(100L);
        booking.setCar(car);
        booking.setRenter(guest);
        booking.setStatus(BookingStatus.PENDING_APPROVAL);
        booking.setStartTime(LocalDateTime.now().plusHours(2));
        booking.setEndTime(LocalDateTime.now().plusDays(2));
        booking.setCreatedAt(LocalDateTime.now().minusDays(2));
        booking.setTotalPrice(new BigDecimal("15000.00"));
        booking.setSnapshotDailyRate(new BigDecimal("5000.00"));

        when(cancellationRecordRepository.existsByBookingId(100L)).thenReturn(false);
        when(cancellationRecordRepository.save(any(CancellationRecord.class)))
                .thenAnswer(invocation -> {
                    CancellationRecord record = invocation.getArgument(0);
                    record.setId(999L);
                    return record;
                });

        CancellationResultDTO result = service.processCancellation(
                booking,
                guest,
            CancellationReason.GUEST_CHANGE_OF_PLANS,
                "Need to cancel"
        );

        assertThat(result.penaltyAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.refundToGuest()).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(result.payoutToHost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.appliedRule()).contains("pending approval");
    }
}
