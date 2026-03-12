package org.example.rentoza.booking;

import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationReason;
import org.example.rentoza.booking.cancellation.CancellationSettlementService;
import org.example.rentoza.booking.dto.AgreementSummaryDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RentalAgreementWorkflowServiceTest {

    @Mock private RentalAgreementRepository agreementRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private CancellationSettlementService cancellationSettlementService;
    @Mock private NotificationService notificationService;
    @Mock private FeatureFlags featureFlags;
    @Mock private SchedulerIdempotencyService lockService;
    @Mock private RentalAgreementService rentalAgreementService;
    @Mock private RentalAgreementBackfillProcessor rentalAgreementBackfillProcessor;
    @Mock private RentalAgreementExpiryProcessor rentalAgreementExpiryProcessor;

    private RentalAgreementWorkflowService service;
    private Booking booking;
    private RentalAgreement agreement;

    @BeforeEach
    void setUp() {
        service = new RentalAgreementWorkflowService(
                agreementRepository,
                bookingRepository,
                cancellationSettlementService,
                notificationService,
                featureFlags,
                lockService,
                rentalAgreementService,
                rentalAgreementBackfillProcessor,
                rentalAgreementExpiryProcessor
        );

        ReflectionTestUtils.setField(service, "acceptanceDeadlineMinutesAfterTripStart", 120);
        ReflectionTestUtils.setField(service, "renterBreachPenaltyRate", new BigDecimal("0.20"));

        User owner = new User();
        owner.setId(10L);
        owner.setFirstName("Host");

        User renter = new User();
        renter.setId(20L);
        renter.setFirstName("Guest");

        Car car = new Car();
        car.setId(100L);
        car.setOwner(owner);

        booking = new Booking();
        booking.setId(1L);
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStatus(BookingStatus.CHECK_IN_OPEN);
        booking.setStartTime(LocalDateTime.now().plusHours(1));
        booking.setTotalPrice(new BigDecimal("10000.00"));

        agreement = RentalAgreement.builder()
                .id(5L)
                .bookingId(1L)
                .ownerUserId(10L)
                .renterUserId(20L)
                .status(RentalAgreementStatus.PENDING)
                .acceptanceDeadlineAt(LocalDateTime.now().minusMinutes(5))
                .build();

        lenient().when(featureFlags.isRentalAgreementCheckinEnforced()).thenReturn(true);
        lenient().when(lockService.tryAcquireLock(anyString(), any(Duration.class))).thenReturn(true);
        lenient().when(agreementRepository.save(any(RentalAgreement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(agreementRepository.saveAndFlush(any(RentalAgreement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(agreementRepository.findByIdForUpdate(agreement.getId())).thenReturn(Optional.of(agreement));
        lenient().when(agreementRepository.findIdsByStatusInAndAcceptanceDeadlineAtIsNull(anyList())).thenReturn(List.of());
        lenient().when(rentalAgreementService.shouldHaveAgreement(any(Booking.class))).thenReturn(true);
    }

    @Test
    @DisplayName("buildSummary marks agreement acceptance as primary CTA for current actor")
    void buildSummary_marksAcceptanceAsPrimaryAction() {
        AgreementSummaryDTO summary = service.buildSummary(booking, agreement, 20L);

        assertThat(summary.workflowStatus()).isEqualTo("AGREEMENT_PENDING_BOTH");
        assertThat(summary.currentActorNeedsAcceptance()).isTrue();
        assertThat(summary.currentActorCanProceedToCheckIn()).isFalse();
        assertThat(summary.recommendedPrimaryAction()).isEqualTo("ACCEPT_RENTAL_AGREEMENT");
    }

    @Test
    @DisplayName("buildSummary allows host prep after owner acceptance while renter is still pending")
    void buildSummary_allowsHostPrepAfterOwnerAcceptance() {
        agreement.setOwnerAcceptedAt(Instant.now());
        agreement.setStatus(RentalAgreementStatus.OWNER_ACCEPTED);

        AgreementSummaryDTO summary = service.buildSummary(booking, agreement, 10L);

        assertThat(summary.workflowStatus()).isEqualTo("AGREEMENT_PENDING_RENTER");
        assertThat(summary.currentActorNeedsAcceptance()).isFalse();
        assertThat(summary.currentActorCanProceedToCheckIn()).isTrue();
        assertThat(summary.recommendedPrimaryAction()).isEqualTo("OPEN_CHECK_IN");
    }

    @Test
    @DisplayName("buildSummary keeps eligible missing agreements in pending workflow")
    void buildSummary_marksPendingWhenAgreementMissingForEligibleBooking() {
        AgreementSummaryDTO summary = service.buildSummary(booking, null, 20L);

        assertThat(summary.legacyBooking()).isFalse();
        assertThat(summary.workflowStatus()).isEqualTo("AGREEMENT_PENDING_BOTH");
        assertThat(summary.currentActorCanProceedToCheckIn()).isFalse();
    }

    @Test
    @DisplayName("buildSummary keeps proceed semantics open when enforcement is disabled")
    void buildSummary_allowsProceedWhenEnforcementDisabled() {
        when(featureFlags.isRentalAgreementCheckinEnforced()).thenReturn(false);

        AgreementSummaryDTO summary = service.buildSummary(booking, null, 20L);

        assertThat(summary.currentActorCanProceedToCheckIn()).isTrue();
        assertThat(summary.recommendedPrimaryAction()).isEqualTo("OPEN_CHECK_IN");
    }

    @Test
    @DisplayName("sendPendingAgreementReminders backfills null deadline before evaluating workflow")
    void sendPendingAgreementReminders_backfillsNullDeadline() {
        when(agreementRepository.findIdsByStatusInAndAcceptanceDeadlineAtIsNull(anyList())).thenReturn(List.of(agreement.getId()));
        when(agreementRepository.findPendingForReminderWindow(anyList(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(agreement));
        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));

        service.sendPendingAgreementReminders();

        verify(rentalAgreementBackfillProcessor).backfillWorkflowDefaults(agreement.getId());
        }

        @Test
        @DisplayName("expireOverdueAgreements continues when the backfill pre-pass fails on one row")
        void expireOverdueAgreements_continuesWhenBackfillFails() {
        when(agreementRepository.findIdsByStatusInAndAcceptanceDeadlineAtIsNull(anyList()))
            .thenReturn(List.of(agreement.getId(), 77L));
        when(agreementRepository.findPendingIdsForDeadlineResolution(anyList(), any(LocalDateTime.class)))
            .thenReturn(List.of(agreement.getId()));
        org.mockito.Mockito.doThrow(new IllegalStateException("lock timeout"))
            .when(rentalAgreementBackfillProcessor).backfillWorkflowDefaults(agreement.getId());

        service.expireOverdueAgreements();

        verify(rentalAgreementBackfillProcessor).backfillWorkflowDefaults(agreement.getId());
        verify(rentalAgreementBackfillProcessor).backfillWorkflowDefaults(77L);
        verify(rentalAgreementExpiryProcessor).processOverdueAgreement(eq(agreement.getId()), any(LocalDateTime.class));
        verify(lockService).releaseLock("rental-agreement.expiry");
    }

    @Test
    @DisplayName("expireOverdueAgreements applies full refund when owner misses acceptance deadline")
    void expireOverdueAgreements_ownerBreachTriggersFullRefund() {
        agreement.setRenterAcceptedAt(Instant.now());
        agreement.setStatus(RentalAgreementStatus.RENTER_ACCEPTED);

        when(agreementRepository.findPendingIdsForDeadlineResolution(anyList(), any(LocalDateTime.class))).thenReturn(List.of(agreement.getId()));

        service.expireOverdueAgreements();

        verify(rentalAgreementExpiryProcessor).processOverdueAgreement(eq(agreement.getId()), any(LocalDateTime.class));
        verify(lockService).releaseLock("rental-agreement.expiry");
    }

    @Test
    @DisplayName("expireOverdueAgreements applies configured renter penalty on breach")
    void expireOverdueAgreements_renterBreachTriggersPenaltySettlement() {
        when(agreementRepository.findPendingIdsForDeadlineResolution(anyList(), any(LocalDateTime.class))).thenReturn(List.of(agreement.getId()));

        service.expireOverdueAgreements();

        verify(rentalAgreementExpiryProcessor).processOverdueAgreement(eq(agreement.getId()), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("expireOverdueAgreements keeps full refund policy neutral when both parties miss the deadline")
    void expireOverdueAgreements_bothPartiesKeepNeutralFullRefundPolicy() {
        when(agreementRepository.findPendingIdsForDeadlineResolution(anyList(), any(LocalDateTime.class))).thenReturn(List.of(agreement.getId()));

        service.expireOverdueAgreements();

        verify(rentalAgreementExpiryProcessor).processOverdueAgreement(eq(agreement.getId()), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("expireOverdueAgreements does not throw when renter relation is missing on renter breach notifications")
    void expireOverdueAgreements_renterNotificationHandlesMissingRenter() {
        when(agreementRepository.findPendingIdsForDeadlineResolution(anyList(), any(LocalDateTime.class))).thenReturn(List.of(agreement.getId()));

        service.expireOverdueAgreements();

        verify(rentalAgreementExpiryProcessor).processOverdueAgreement(eq(agreement.getId()), any(LocalDateTime.class));
    }

        @Test
        @DisplayName("expireOverdueAgreements skips expiry if the locked row is already fully accepted")
        void expireOverdueAgreements_skipsExpiryIfLockedRowIsAlreadyAccepted() {
        when(agreementRepository.findPendingIdsForDeadlineResolution(anyList(), any(LocalDateTime.class))).thenReturn(List.of(agreement.getId()));

        service.expireOverdueAgreements();

        verify(rentalAgreementExpiryProcessor).processOverdueAgreement(eq(agreement.getId()), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("expireOverdueAgreements logs and continues when one agreement fails")
    void expireOverdueAgreements_continuesWhenProcessorFails() {
        when(agreementRepository.findPendingIdsForDeadlineResolution(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of(agreement.getId(), 99L));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(rentalAgreementExpiryProcessor).processOverdueAgreement(eq(agreement.getId()), any(LocalDateTime.class));

        service.expireOverdueAgreements();

        verify(rentalAgreementExpiryProcessor).processOverdueAgreement(eq(agreement.getId()), any(LocalDateTime.class));
        verify(rentalAgreementExpiryProcessor).processOverdueAgreement(eq(99L), any(LocalDateTime.class));
        verify(lockService).releaseLock("rental-agreement.expiry");
    }
}