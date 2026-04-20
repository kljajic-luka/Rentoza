package org.example.rentoza.payment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.extension.TripExtensionRepository;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression: RELEASE_FAILED and CAPTURE_FAILED lifecycle states must return
 * a failure result instead of falling through to a refund attempt on an
 * uncaptured authorization.
 *
 * <p>Production incident 2026-03-09: Booking 62 had charge_lifecycle_status =
 * RELEASE_FAILED. The cancellation scheduler kept calling processCancellationSettlement,
 * which fell through to processRefund — but you cannot refund an uncaptured auth,
 * so the provider returned UNKNOWN_TRANSACTION every 15 minutes indefinitely.
 *
 * @see BookingPaymentService#processCancellationSettlement(Long, BigDecimal, String)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Regression: RELEASE_FAILED / CAPTURE_FAILED cancellation settlement")
class CancellationSettlementLifecycleTest {

    @Mock private PaymentProvider paymentProvider;
    @Mock private BookingRepository bookingRepository;
    @Mock private DamageClaimRepository damageClaimRepository;
    @Mock private TripExtensionRepository extensionRepository;
    @Mock private PaymentTransactionRepository txRepository;
    @Mock private PayoutLedgerRepository payoutLedgerRepository;
    @Mock private UserRepository userRepository;
    @Mock private TaxWithholdingService taxWithholdingService;

    private BookingPaymentService paymentService;

    @BeforeEach
    void setUp() {
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        paymentService = new BookingPaymentService(
                paymentProvider,
                bookingRepository,
                damageClaimRepository,
                extensionRepository,
                txRepository,
                payoutLedgerRepository,
                userRepository,
                taxWithholdingService,
                new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("processCancellationSettlement returns failure for RELEASE_FAILED — no refund attempted")
    void releaseFailedLifecycle_returnsFailure_noRefund() {
        Booking booking = buildBookingWithLifecycle(62L, ChargeLifecycleStatus.RELEASE_FAILED);
        when(bookingRepository.findByIdWithRelations(62L)).thenReturn(Optional.of(booking));

        PaymentResult result = paymentService.processCancellationSettlement(
                62L, new BigDecimal("4600.00"), "Cancellation refund");

        assertThat(result.isSuccess())
                .as("RELEASE_FAILED must NOT succeed — requires manual review")
                .isFalse();

        assertThat(result.getErrorMessage())
                .as("Error message must mention the terminal state")
                .contains("RELEASE_FAILED");

        verify(paymentProvider, never()).refund(any(), any(), any(), any());
        verify(paymentProvider, never()).releaseAuthorization(any(), any());
    }

    @Test
    @DisplayName("processCancellationSettlement returns failure for CAPTURE_FAILED — no refund attempted")
    void captureFailedLifecycle_returnsFailure_noRefund() {
        Booking booking = buildBookingWithLifecycle(70L, ChargeLifecycleStatus.CAPTURE_FAILED);
        when(bookingRepository.findByIdWithRelations(70L)).thenReturn(Optional.of(booking));

        PaymentResult result = paymentService.processCancellationSettlement(
                70L, new BigDecimal("3000.00"), "Cancellation refund");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("CAPTURE_FAILED");

        verify(paymentProvider, never()).refund(any(), any(), any(), any());
    }

    private Booking buildBookingWithLifecycle(Long id, ChargeLifecycleStatus lifecycle) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setChargeLifecycleStatus(lifecycle);
        booking.setTotalPrice(new BigDecimal("5000.00"));
        return booking;
    }
}
