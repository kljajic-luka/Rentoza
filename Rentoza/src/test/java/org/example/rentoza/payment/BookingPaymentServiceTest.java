package org.example.rentoza.payment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.extension.TripExtensionRepository;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.example.rentoza.payment.PaymentProvider.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BookingPaymentService.
 * 
 * BUG-007: Verifies deposit release is blocked when pending damage claims exist.
 */
@ExtendWith(MockitoExtension.class)
class BookingPaymentServiceTest {

    @Mock
    private PaymentProvider paymentProvider;
    
    @Mock
    private BookingRepository bookingRepository;
    
    @Mock
    private DamageClaimRepository damageClaimRepository;
    
    @Mock
    private TripExtensionRepository extensionRepository;

    private BookingPaymentService paymentService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        paymentService = new BookingPaymentService(
            paymentProvider,
            bookingRepository,
            damageClaimRepository,
            extensionRepository,
            meterRegistry
        );
    }

    @Nested
    @DisplayName("BUG-007: Deposit Release Blocking")
    class DepositReleaseBlockingTests {

        private Booking testBooking;
        private final Long BOOKING_ID = 1L;

        @BeforeEach
        void setUp() {
            testBooking = new Booking();
            testBooking.setId(BOOKING_ID);
            when(bookingRepository.findByIdWithRelations(BOOKING_ID)).thenReturn(Optional.of(testBooking));
        }

        @Test
        @DisplayName("Should block deposit release when PENDING claim exists")
        void shouldBlockDepositRelease_whenPendingClaimExists() {
            // Given
            when(damageClaimRepository.hasClaimsBlockingDepositRelease(BOOKING_ID)).thenReturn(true);

            // When/Then
            assertThatThrownBy(() -> paymentService.releaseDeposit(BOOKING_ID, "auth-123"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Depozit ne može biti vraćen")
                .hasMessageContaining("nerešene prijave štete");

            // Verify payment provider was never called
            verify(paymentProvider, never()).releaseAuthorization(any());
        }

        @Test
        @DisplayName("Should block deposit release when DISPUTED claim exists")
        void shouldBlockDepositRelease_whenDisputedClaimExists() {
            // Given
            when(damageClaimRepository.hasClaimsBlockingDepositRelease(BOOKING_ID)).thenReturn(true);

            // When/Then
            assertThatThrownBy(() -> paymentService.releaseDeposit(BOOKING_ID, "auth-456"))
                .isInstanceOf(IllegalStateException.class);

            verify(paymentProvider, never()).releaseAuthorization(any());
        }

        @Test
        @DisplayName("Should allow deposit release when no active claims")
        void shouldAllowDepositRelease_whenNoActiveClaims() {
            // Given
            when(damageClaimRepository.hasClaimsBlockingDepositRelease(BOOKING_ID)).thenReturn(false);
            when(paymentProvider.releaseAuthorization("auth-789")).thenReturn(
                PaymentResult.builder()
                    .success(true)
                    .status(PaymentStatus.REFUNDED)
                    .transactionId("txn-001")
                    .build()
            );

            // When
            PaymentResult result = paymentService.releaseDeposit(BOOKING_ID, "auth-789");

            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(testBooking.getPaymentStatus()).isEqualTo("DEPOSIT_RELEASED");
            verify(bookingRepository).save(testBooking);
        }

        @Test
        @DisplayName("Should allow deposit release when all claims are PAID")
        void shouldAllowDepositRelease_whenAllClaimsPaid() {
            // Given: hasClaimsBlockingDepositRelease returns false when only PAID claims exist
            when(damageClaimRepository.hasClaimsBlockingDepositRelease(BOOKING_ID)).thenReturn(false);
            when(paymentProvider.releaseAuthorization("auth-paid")).thenReturn(
                PaymentResult.builder()
                    .success(true)
                    .status(PaymentStatus.REFUNDED)
                    .transactionId("txn-paid")
                    .build()
            );

            // When
            PaymentResult result = paymentService.releaseDeposit(BOOKING_ID, "auth-paid");

            // Then
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should allow deposit release when all claims are ADMIN_REJECTED")
        void shouldAllowDepositRelease_whenAllClaimsRejected() {
            // Given: hasClaimsBlockingDepositRelease returns false when only ADMIN_REJECTED claims exist
            when(damageClaimRepository.hasClaimsBlockingDepositRelease(BOOKING_ID)).thenReturn(false);
            when(paymentProvider.releaseAuthorization("auth-rejected")).thenReturn(
                PaymentResult.builder()
                    .success(true)
                    .status(PaymentStatus.REFUNDED)
                    .transactionId("txn-rejected")
                    .build()
            );

            // When
            PaymentResult result = paymentService.releaseDeposit(BOOKING_ID, "auth-rejected");

            // Then
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Error message includes Serbian text for user-facing display")
        void errorMessage_includesSerbianText() {
            // Given
            when(damageClaimRepository.hasClaimsBlockingDepositRelease(BOOKING_ID)).thenReturn(true);

            // When/Then
            assertThatThrownBy(() -> paymentService.releaseDeposit(BOOKING_ID, "auth-123"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Molimo sačekajte razrešenje");
        }
    }

    @Nested
    @DisplayName("Deposit Release - Payment Provider Failures")
    class DepositReleasePaymentFailures {

        private Booking testBooking;
        private final Long BOOKING_ID = 2L;

        @BeforeEach
        void setUp() {
            testBooking = new Booking();
            testBooking.setId(BOOKING_ID);
            testBooking.setPaymentStatus(null); // Ensure clean state
            when(bookingRepository.findByIdWithRelations(BOOKING_ID)).thenReturn(Optional.of(testBooking));
            when(damageClaimRepository.hasClaimsBlockingDepositRelease(BOOKING_ID)).thenReturn(false);
        }

        @Test
        @DisplayName("Should return failure result when payment provider fails")
        void shouldReturnFailure_whenPaymentProviderFails() {
            // Given
            when(paymentProvider.releaseAuthorization("bad-auth")).thenReturn(
                PaymentResult.builder()
                    .success(false)
                    .status(PaymentStatus.FAILED)
                    .errorMessage("Invalid authorization")
                    .build()
            );

            // When
            PaymentResult result = paymentService.releaseDeposit(BOOKING_ID, "bad-auth");

            // Then
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).isEqualTo("Invalid authorization");
            // Booking should NOT be updated on failure - payment status should remain null
            assertThat(testBooking.getPaymentStatus()).isNull();
            verify(bookingRepository, never()).save(any());
        }
    }
}
