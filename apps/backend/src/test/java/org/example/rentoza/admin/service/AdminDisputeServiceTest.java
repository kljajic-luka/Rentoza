package org.example.rentoza.admin.service;

import org.example.rentoza.admin.dto.CheckInDisputeResolutionDTO;
import org.example.rentoza.admin.dto.CheckInDisputeResolutionDTO.CheckInDisputeDecision;
import org.example.rentoza.admin.dto.CheckoutDisputeResolutionDTO;
import org.example.rentoza.admin.dto.CheckoutDisputeResolutionResponseDTO;
import org.example.rentoza.admin.repository.DisputeResolutionRepository;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationSettlementService;
import org.example.rentoza.booking.cancellation.RefundStatus;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaOrchestrator;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.dispute.DamageClaimStatus;
import org.example.rentoza.booking.dispute.DisputeStage;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminDisputeService")
class AdminDisputeServiceTest {

    @Mock
    private DamageClaimRepository damageClaimRepo;

    @Mock
    private DisputeResolutionRepository resolutionRepo;

    @Mock
    private AdminAuditService auditService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private BookingPaymentService paymentService;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private CheckoutSagaOrchestrator checkoutSagaOrchestrator;

        @Mock
        private CancellationSettlementService cancellationSettlementService;

    @InjectMocks
    private AdminDisputeService service;

    private DamageClaim claim;
    private Booking booking;
    private User admin;

    @BeforeEach
    void setUp() {
        booking = new Booking();
        booking.setId(10L);
        booking.setStatus(BookingStatus.CHECKOUT_DAMAGE_DISPUTE);
        booking.setSecurityDeposit(BigDecimal.valueOf(5000));
        booking.setDepositAuthorizationId("auth-123");

        claim = new DamageClaim();
        claim.setId(1L);
        claim.setClaimedAmount(BigDecimal.valueOf(3000));
        claim.setStatus(DamageClaimStatus.CHECKOUT_GUEST_DISPUTED);
        claim.setDisputeStage(DisputeStage.CHECKOUT);
        claim.setBooking(booking);

        booking.setCheckoutDamageClaim(claim);

        admin = new User();
        admin.setId(100L);
        admin.setFirstName("Admin");
        admin.setLastName("User");
    }

    // ==================== resolveCheckoutDispute ====================

    @Nested
    @DisplayName("resolveCheckoutDispute")
    class ResolveCheckoutDisputeTests {

        @BeforeEach
        void stubFindByIdWithLock() {
            when(damageClaimRepo.findByIdWithLock(1L)).thenReturn(Optional.of(claim));
        }

        @Test
        @DisplayName("APPROVE captures deposit and marks claim approved")
        void approve_capturesDepositAndApprovesClaim() {
            CheckoutDisputeResolutionDTO request = CheckoutDisputeResolutionDTO.builder()
                    .decision(CheckoutDisputeResolutionDTO.Decision.APPROVE)
                    .resolutionNotes("Damage confirmed by photos")
                    .notifyParties(false)
                    .build();

            CheckoutDisputeResolutionResponseDTO result =
                    service.resolveCheckoutDispute(1L, request, admin);

            // Verify response DTO
            assertThat(result.getBookingId()).isEqualTo(10L);
            assertThat(result.getDamageClaimId()).isEqualTo(1L);
            assertThat(result.getDecision()).isEqualTo("APPROVE");
            assertThat(result.getOriginalClaimAmountRsd()).isEqualByComparingTo("3000");
            assertThat(result.getApprovedAmountRsd()).isEqualByComparingTo("3000");
            assertThat(result.getDepositCapturedRsd()).isEqualByComparingTo("3000");
            assertThat(result.getDepositReleasedRsd()).isEqualByComparingTo("2000");
            assertThat(result.getNewBookingStatus()).isEqualTo(BookingStatus.CHECKOUT_HOST_COMPLETE.name());

            // Verify entity state
            assertThat(claim.getStatus()).isEqualTo(DamageClaimStatus.CHECKOUT_ADMIN_APPROVED);
            assertThat(claim.getApprovedAmount()).isEqualByComparingTo("3000");
            assertThat(claim.getResolvedBy()).isEqualTo(admin);
            assertThat(claim.getResolvedAt()).isNotNull();
            assertThat(claim.getResolutionNotes()).isEqualTo("Damage confirmed by photos");

            // Verify deposit captured via payment service
            verify(paymentService).chargeDamage(1L, "auth-123");
            verify(paymentService, never()).releaseDeposit(eq(10L), anyString());

            // Verify claim and booking persisted
            verify(damageClaimRepo).save(claim);
            verify(bookingRepository).save(booking);
        }

        @Test
        @DisplayName("REJECT releases full deposit back to guest")
        void reject_releasesFullDeposit() {
            CheckoutDisputeResolutionDTO request = CheckoutDisputeResolutionDTO.builder()
                    .decision(CheckoutDisputeResolutionDTO.Decision.REJECT)
                    .resolutionNotes("No evidence of damage")
                    .notifyParties(false)
                    .build();

            CheckoutDisputeResolutionResponseDTO result =
                    service.resolveCheckoutDispute(1L, request, admin);

            // Verify response DTO
            assertThat(result.getDecision()).isEqualTo("REJECT");
            assertThat(result.getApprovedAmountRsd()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.getDepositReleasedRsd()).isEqualByComparingTo("5000");
            assertThat(result.getDepositCapturedRsd()).isEqualByComparingTo(BigDecimal.ZERO);

            // Verify entity state
            assertThat(claim.getStatus()).isEqualTo(DamageClaimStatus.CHECKOUT_ADMIN_REJECTED);
            assertThat(claim.getApprovedAmount()).isEqualByComparingTo(BigDecimal.ZERO);

            // Verify deposit released via payment service
            verify(paymentService).releaseDeposit(10L, "auth-123");
            verify(paymentService, never()).chargeDamage(eq(1L), anyString());
        }

        @Test
        @DisplayName("C-5: PARTIAL with approved amount exceeding claimed amount throws IllegalArgumentException")
        void partial_approvedAmountExceedsClaimed_throws() {
            CheckoutDisputeResolutionDTO request = CheckoutDisputeResolutionDTO.builder()
                    .decision(CheckoutDisputeResolutionDTO.Decision.PARTIAL)
                    .approvedAmountRsd(BigDecimal.valueOf(4000))
                    .notifyParties(false)
                    .build();

            assertThatThrownBy(() -> service.resolveCheckoutDispute(1L, request, admin))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed claimed amount");

            // Verify no payment processing occurred
            verify(paymentService, never()).chargeDamage(eq(1L), anyString());
            verify(paymentService, never()).releaseDeposit(eq(10L), anyString());
        }

        @Test
        @DisplayName("C-5: PARTIAL with approved amount exceeding security deposit throws IllegalArgumentException")
        void partial_approvedAmountExceedsDeposit_throws() {
            // Raise claimed amount so it passes the first bound check
            claim.setClaimedAmount(BigDecimal.valueOf(10000));
            booking.setSecurityDeposit(BigDecimal.valueOf(5000));

            CheckoutDisputeResolutionDTO request = CheckoutDisputeResolutionDTO.builder()
                    .decision(CheckoutDisputeResolutionDTO.Decision.PARTIAL)
                    .approvedAmountRsd(BigDecimal.valueOf(6000))
                    .notifyParties(false)
                    .build();

            assertThatThrownBy(() -> service.resolveCheckoutDispute(1L, request, admin))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed security deposit");

            verify(paymentService, never()).chargeDamage(eq(1L), anyString());
            verify(paymentService, never()).releaseDeposit(eq(10L), anyString());
        }

        @Test
        @DisplayName("Already-resolved dispute throws IllegalStateException")
        void alreadyResolved_throws() {
            claim.setStatus(DamageClaimStatus.CHECKOUT_ADMIN_APPROVED);

            CheckoutDisputeResolutionDTO request = CheckoutDisputeResolutionDTO.builder()
                    .decision(CheckoutDisputeResolutionDTO.Decision.APPROVE)
                    .notifyParties(false)
                    .build();

            assertThatThrownBy(() -> service.resolveCheckoutDispute(1L, request, admin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be resolved in current state");
        }
    }

    // ==================== resolveCheckInDispute ====================

    @Nested
    @DisplayName("resolveCheckInDispute")
    class ResolveCheckInDisputeTests {

        @Test
        @DisplayName("C-6: CANCEL with settlement failure leaves booking pending/refund failed")
        void cancel_refundFailure_propagatesException() {
            // Reconfigure claim as a check-in dispute
            claim.setDisputeStage(DisputeStage.CHECK_IN);
            claim.setStatus(DamageClaimStatus.CHECK_IN_DISPUTE_PENDING);

            when(damageClaimRepo.findByIdWithLock(1L)).thenReturn(Optional.of(claim));
            CancellationRecord record = new CancellationRecord();
            record.setBooking(booking);
            record.setRefundStatus(RefundStatus.FAILED);
            record.setLastError("Payment gateway error");
            booking.setStatus(BookingStatus.CANCELLATION_PENDING_SETTLEMENT);
            when(cancellationSettlementService.beginAndAttemptFullRefundSettlement(eq(booking), any(), any(), anyString(), anyString(), anyString()))
                    .thenReturn(new CancellationSettlementService.SettlementAttemptResult(record, false));

            CheckInDisputeResolutionDTO resolution = CheckInDisputeResolutionDTO.builder()
                    .decision(CheckInDisputeDecision.CANCEL)
                    .notes("Cancel due to undisclosed damage")
                    .cancellationReason("Undisclosed damage")
                    .build();

            assertThatThrownBy(() -> service.resolveCheckInDispute(1L, resolution, admin))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Refund settlement pending");

                        verify(cancellationSettlementService).beginAndAttemptFullRefundSettlement(eq(booking), any(), any(), anyString(), anyString(), contains("Undisclosed damage"));
                        assertThat(booking.getStatus()).isIn(BookingStatus.CANCELLATION_PENDING_SETTLEMENT, BookingStatus.REFUND_FAILED);
        }
    }
}
