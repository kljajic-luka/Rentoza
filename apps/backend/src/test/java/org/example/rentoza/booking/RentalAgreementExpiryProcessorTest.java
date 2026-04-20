package org.example.rentoza.booking;

import org.example.rentoza.booking.cancellation.CancellationReason;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationSettlementService;
import org.example.rentoza.car.Car;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RentalAgreementExpiryProcessorTest {

    @Mock private RentalAgreementRepository agreementRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private CancellationSettlementService cancellationSettlementService;
    @Mock private NotificationService notificationService;

    private RentalAgreementExpiryProcessor processor;
    private RentalAgreement agreement;
    private Booking booking;

    @BeforeEach
    void setUp() {
        processor = new RentalAgreementExpiryProcessor(
                agreementRepository,
                bookingRepository,
                cancellationSettlementService,
                notificationService
        );
        ReflectionTestUtils.setField(processor, "acceptanceDeadlineMinutesAfterTripStart", 120);
        ReflectionTestUtils.setField(processor, "renterBreachPenaltyRate", new BigDecimal("0.20"));

        User owner = new User();
        owner.setId(10L);
        User renter = new User();
        renter.setId(20L);

        Car car = new Car();
        car.setOwner(owner);

        booking = new Booking();
        booking.setId(1L);
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setTotalPrice(new BigDecimal("10000.00"));
        booking.setStartTime(LocalDateTime.now().minusHours(3));

        agreement = RentalAgreement.builder()
                .id(5L)
                .bookingId(1L)
                .ownerUserId(10L)
                .renterUserId(20L)
                .status(RentalAgreementStatus.PENDING)
                .acceptanceDeadlineAt(LocalDateTime.now().minusMinutes(5))
                .build();

        when(agreementRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(agreement));
        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));
        when(agreementRepository.saveAndFlush(any(RentalAgreement.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("processOverdueAgreement uses neutral mutual-breach cancellation metadata")
    void processOverdueAgreement_mutualBreachUsesNeutralCancellationReason() {
        CancellationRecord record = CancellationRecord.builder().id(99L).refundToGuest(new BigDecimal("10000.00")).build();
        when(cancellationSettlementService.beginAndAttemptFullRefundSettlement(
                eq(booking), any(), eq(CancellationReason.SYSTEM_MUTUAL_AGREEMENT_BREACH), anyString(), anyString(), anyString()))
                .thenReturn(new CancellationSettlementService.SettlementAttemptResult(record, true));

        processor.processOverdueAgreement(5L, LocalDateTime.now());

        verify(cancellationSettlementService).beginAndAttemptFullRefundSettlement(
                eq(booking), any(), eq(CancellationReason.SYSTEM_MUTUAL_AGREEMENT_BREACH), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("processOverdueAgreement tolerates missing renter relation on renter breach")
    void processOverdueAgreement_renterBreachWithMissingRenterDoesNotThrow() {
        agreement.setOwnerAcceptedAt(java.time.Instant.now());
        agreement.setStatus(RentalAgreementStatus.OWNER_ACCEPTED);
        booking.setRenter(null);

        CancellationRecord record = CancellationRecord.builder().id(100L).refundToGuest(new BigDecimal("8000.00")).build();
        when(cancellationSettlementService.beginSettlement(
                eq(booking), any(), eq(CancellationReason.GUEST_AGREEMENT_BREACH), anyString(), any(), any(), any(), anyString(), anyString(), any(), any(LocalDateTime.class)))
                .thenReturn(record);

        processor.processOverdueAgreement(5L, LocalDateTime.now());

        verify(cancellationSettlementService).beginSettlement(
                eq(booking), any(), eq(CancellationReason.GUEST_AGREEMENT_BREACH), anyString(), any(), any(), any(), anyString(), anyString(), any(), any(LocalDateTime.class));
    }
}