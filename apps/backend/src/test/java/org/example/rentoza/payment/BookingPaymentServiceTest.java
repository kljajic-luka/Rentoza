package org.example.rentoza.payment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.extension.TripExtensionRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.example.rentoza.payment.PaymentProvider.PaymentStatus;
import org.example.rentoza.payment.PaymentProvider.ProviderOutcome;
import org.example.rentoza.payment.PaymentProvider.ProviderResult;
import org.example.rentoza.payment.PaymentTransaction.PaymentTransactionStatus;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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

    @Mock
    private PaymentTransactionRepository txRepository;

    @Mock
    private PayoutLedgerRepository payoutLedgerRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TaxWithholdingService taxWithholdingService;

    private BookingPaymentService paymentService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lenient().when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        paymentService = new BookingPaymentService(
            paymentProvider,
            bookingRepository,
            damageClaimRepository,
            extensionRepository,
            txRepository,
            payoutLedgerRepository,
            userRepository,
            taxWithholdingService,
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
            User renter = new User();
            renter.setId(99L);
            testBooking.setRenter(renter);
            Car car = new Car();
            User owner = new User();
            owner.setId(200L);
            car.setOwner(owner);
            testBooking.setCar(car);
            testBooking.setDepositLifecycleStatus(DepositLifecycleStatus.AUTHORIZED);
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
            verify(paymentProvider, never()).releaseAuthorization(any(), any());
        }

        @Test
        @DisplayName("Should block deposit release when DISPUTED claim exists")
        void shouldBlockDepositRelease_whenDisputedClaimExists() {
            // Given
            when(damageClaimRepository.hasClaimsBlockingDepositRelease(BOOKING_ID)).thenReturn(true);

            // When/Then
            assertThatThrownBy(() -> paymentService.releaseDeposit(BOOKING_ID, "auth-456"))
                .isInstanceOf(IllegalStateException.class);

            verify(paymentProvider, never()).releaseAuthorization(any(), any());
        }

        @Test
        @DisplayName("Should allow deposit release when no active claims")
        void shouldAllowDepositRelease_whenNoActiveClaims() {
            // Given
            when(damageClaimRepository.hasClaimsBlockingDepositRelease(BOOKING_ID)).thenReturn(false);
            when(paymentProvider.releaseAuthorization(eq("auth-789"), anyString())).thenReturn(
                ProviderResult.builder()
                    .outcome(ProviderOutcome.SUCCESS)
                    .providerTransactionId("txn-001")
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
            when(paymentProvider.releaseAuthorization(eq("auth-paid"), anyString())).thenReturn(
                ProviderResult.builder()
                    .outcome(ProviderOutcome.SUCCESS)
                    .providerTransactionId("txn-paid")
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
            when(paymentProvider.releaseAuthorization(eq("auth-rejected"), anyString())).thenReturn(
                ProviderResult.builder()
                    .outcome(ProviderOutcome.SUCCESS)
                    .providerTransactionId("txn-rejected")
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
            User renter = new User();
            renter.setId(99L);
            testBooking.setRenter(renter);
            Car car = new Car();
            User owner = new User();
            owner.setId(200L);
            car.setOwner(owner);
            testBooking.setCar(car);
            when(bookingRepository.findByIdWithRelations(BOOKING_ID)).thenReturn(Optional.of(testBooking));
            when(damageClaimRepository.hasClaimsBlockingDepositRelease(BOOKING_ID)).thenReturn(false);
        }

        @Test
        @DisplayName("Should return failure result when payment provider fails")
        void shouldReturnFailure_whenPaymentProviderFails() {
            // Given
            when(paymentProvider.releaseAuthorization(eq("bad-auth"), anyString())).thenReturn(
                ProviderResult.builder()
                    .outcome(ProviderOutcome.TERMINAL_FAILURE)
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

    // ========================================================================
    // IDEMPOTENCY STATE HANDLING TESTS
    // ========================================================================

    @Nested
    @DisplayName("Deposit Capture Idempotency")
    class DepositCaptureIdempotencyTests {

        private Booking testBooking;
        private final Long BOOKING_ID = 3L;

        @BeforeEach
        void setUp() {
            testBooking = new Booking();
            testBooking.setId(BOOKING_ID);
            testBooking.setDepositCaptureAttempts(0);
            testBooking.setSecurityDeposit(java.math.BigDecimal.valueOf(50000));
            testBooking.setDepositAuthorizationId("dep-auth-123");
            testBooking.setDepositLifecycleStatus(DepositLifecycleStatus.AUTHORIZED);
            User renter = new User();
            renter.setId(99L);
            testBooking.setRenter(renter);
            when(bookingRepository.findByIdWithRelations(BOOKING_ID)).thenReturn(Optional.of(testBooking));
        }

        @Test
        @DisplayName("Should return idempotent success when SUCCEEDED tx exists")
        void shouldReturnSuccess_whenSucceededTxExists() {
            PaymentTransaction existingTx = new PaymentTransaction();
            existingTx.setStatus(PaymentTransactionStatus.SUCCEEDED);
            existingTx.setProviderReference("txn-dep-001");
            existingTx.setAmount(java.math.BigDecimal.valueOf(50000));
            existingTx.setCurrency("RSD");
            when(txRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existingTx));

            PaymentResult result = paymentService.captureSecurityDeposit(BOOKING_ID);

            assertThat(result.isSuccess()).isTrue();
            verify(paymentProvider, never()).capture(any(), any(), any());
        }

        @Test
        @DisplayName("Should return IN_PROGRESS when PROCESSING tx exists")
        void shouldReturnInProgress_whenProcessingTxExists() {
            PaymentTransaction existingTx = new PaymentTransaction();
            existingTx.setStatus(PaymentTransactionStatus.PROCESSING);
            when(txRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existingTx));

            PaymentResult result = paymentService.captureSecurityDeposit(BOOKING_ID);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("IN_PROGRESS");
            verify(paymentProvider, never()).capture(any(), any(), any());
        }

        @Test
        @DisplayName("Should reuse FAILED_RETRYABLE tx row to avoid unique-key violation")
        void shouldReuseTx_whenFailedRetryableExists() {
            PaymentTransaction existingTx = new PaymentTransaction();
            existingTx.setStatus(PaymentTransactionStatus.FAILED_RETRYABLE);
            existingTx.setAmount(java.math.BigDecimal.valueOf(50000));
            when(txRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existingTx));

            when(paymentProvider.capture(eq("dep-auth-123"), any(), any())).thenReturn(
                ProviderResult.builder()
                    .outcome(ProviderOutcome.SUCCESS)
                    .providerTransactionId("txn-dep-retry")
                    .build()
            );

            PaymentResult result = paymentService.captureSecurityDeposit(BOOKING_ID);

            // Existing row was reused (set to PROCESSING then updated)
            assertThat(existingTx.getStatus()).isNotEqualTo(PaymentTransactionStatus.FAILED_RETRYABLE);
            assertThat(result.isSuccess()).isTrue();
            verify(paymentProvider).capture(eq("dep-auth-123"), any(), any());
        }

        @Test
        @DisplayName("Should short-circuit FAILED_TERMINAL without calling provider")
        void shouldShortCircuit_whenFailedTerminalExists() {
            PaymentTransaction existingTx = new PaymentTransaction();
            existingTx.setStatus(PaymentTransactionStatus.FAILED_TERMINAL);
            existingTx.setAmount(java.math.BigDecimal.valueOf(50000));
            when(txRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existingTx));

            PaymentResult result = paymentService.captureSecurityDeposit(BOOKING_ID);

            assertThat(result.isSuccess()).isFalse();
            verify(paymentProvider, never()).capture(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Late Fee Idempotency")
    class LateFeeIdempotencyTests {

        private Booking testBooking;
        private final Long BOOKING_ID = 4L;

        @BeforeEach
        void setUp() {
            testBooking = new Booking();
            testBooking.setId(BOOKING_ID);
            testBooking.setLateFeeAmount(java.math.BigDecimal.valueOf(5000));
            User renter = new User();
            renter.setId(99L);
            testBooking.setRenter(renter);
            when(bookingRepository.findByIdWithRelations(BOOKING_ID)).thenReturn(Optional.of(testBooking));
        }

        @Test
        @DisplayName("Should return idempotent success when SUCCEEDED late fee tx exists")
        void shouldReturnSuccess_whenSucceededLateFeeExists() {
            PaymentTransaction existingTx = new PaymentTransaction();
            existingTx.setStatus(PaymentTransactionStatus.SUCCEEDED);
            existingTx.setProviderReference("txn-late-001");
            existingTx.setAmount(java.math.BigDecimal.valueOf(5000));
            existingTx.setCurrency("RSD");
            when(txRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existingTx));

            PaymentResult result = paymentService.chargeLateReturnFee(BOOKING_ID, "pm_card");

            assertThat(result.isSuccess()).isTrue();
            verify(paymentProvider, never()).charge(any(), any());
        }

        @Test
        @DisplayName("Should return IN_PROGRESS for PROCESSING late fee tx")
        void shouldReturnInProgress_whenProcessingLateFeeExists() {
            PaymentTransaction existingTx = new PaymentTransaction();
            existingTx.setStatus(PaymentTransactionStatus.PROCESSING);
            when(txRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existingTx));

            PaymentResult result = paymentService.chargeLateReturnFee(BOOKING_ID, "pm_card");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("Should short-circuit FAILED_TERMINAL late fee tx")
        void shouldShortCircuit_whenFailedTerminalLateFeeExists() {
            PaymentTransaction existingTx = new PaymentTransaction();
            existingTx.setStatus(PaymentTransactionStatus.FAILED_TERMINAL);
            existingTx.setAmount(java.math.BigDecimal.valueOf(5000));
            when(txRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existingTx));

            PaymentResult result = paymentService.chargeLateReturnFee(BOOKING_ID, "pm_card");

            assertThat(result.isSuccess()).isFalse();
            verify(paymentProvider, never()).charge(any(PaymentProvider.PaymentRequest.class), any());
        }
    }

    @Nested
    @DisplayName("Capture Booking Payment Idempotency")
    class CaptureBookingPaymentIdempotencyTests {

        private Booking testBooking;
        private final Long BOOKING_ID = 5L;

        @BeforeEach
        void setUp() {
            testBooking = new Booking();
            testBooking.setId(BOOKING_ID);
            testBooking.setCaptureAttempts(0);
            testBooking.setTotalPrice(java.math.BigDecimal.valueOf(100000));
            testBooking.setBookingAuthorizationId("auth-book-123");
            testBooking.setChargeLifecycleStatus(ChargeLifecycleStatus.AUTHORIZED);
            User renter = new User();
            renter.setId(99L);
            testBooking.setRenter(renter);
            when(bookingRepository.findByIdWithRelations(BOOKING_ID)).thenReturn(Optional.of(testBooking));
        }

        @Test
        @DisplayName("Should reuse FAILED_RETRYABLE capture tx row")
        void shouldReuseTx_whenFailedRetryableExists() {
            PaymentTransaction existingTx = new PaymentTransaction();
            existingTx.setStatus(PaymentTransactionStatus.FAILED_RETRYABLE);
            existingTx.setAmount(java.math.BigDecimal.valueOf(100000));
            when(txRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existingTx));

            when(paymentProvider.capture(eq("auth-book-123"), any(), any())).thenReturn(
                ProviderResult.builder()
                    .outcome(ProviderOutcome.SUCCESS)
                    .providerTransactionId("txn-capture-retry")
                    .build()
            );

            PaymentResult result = paymentService.captureBookingPayment(BOOKING_ID);

            assertThat(result.isSuccess()).isTrue();
            assertThat(testBooking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.CAPTURED);
        }

        @Test
        @DisplayName("Should handle ALREADY_CAPTURED as idempotent success")
        void shouldTreatAlreadyCapturedAsSuccess() {
            when(txRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

            when(paymentProvider.capture(eq("auth-book-123"), any(), any())).thenReturn(
                ProviderResult.builder()
                    .outcome(ProviderOutcome.TERMINAL_FAILURE)
                    .errorCode("ALREADY_CAPTURED")
                    .errorMessage("Already captured")
                    .build()
            );

            PaymentResult result = paymentService.captureBookingPayment(BOOKING_ID);

            assertThat(result.isSuccess()).isTrue();
            assertThat(testBooking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.CAPTURED);
        }

        @Test
        @DisplayName("Should short-circuit FAILED_TERMINAL capture tx")
        void shouldShortCircuit_whenFailedTerminalCaptureExists() {
            PaymentTransaction existingTx = new PaymentTransaction();
            existingTx.setStatus(PaymentTransactionStatus.FAILED_TERMINAL);
            existingTx.setAmount(java.math.BigDecimal.valueOf(100000));
            when(txRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existingTx));

            PaymentResult result = paymentService.captureBookingPayment(BOOKING_ID);

            assertThat(result.isSuccess()).isFalse();
            verify(paymentProvider, never()).capture(any(), any(), any());
            // Lifecycle should NOT transition to CAPTURED
            assertThat(testBooking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.AUTHORIZED);
        }
    }

    // ========================================================================
    // DAMAGE CHARGE FALLBACK IDEMPOTENCY TESTS
    // ========================================================================

    @Nested
    @DisplayName("Damage Charge Fallback (Direct Charge) Idempotency")
    class DamageChargeFallbackIdempotencyTests {

        private static final Long BOOKING_ID = 10L;
        private static final Long CLAIM_ID = 42L;
        private static final Long GUEST_ID = 99L;

        private DamageClaim testClaim;

        @BeforeEach
        void setUp() {
            Booking booking = new Booking();
            booking.setId(BOOKING_ID);

            User guest = new User();
            guest.setId(GUEST_ID);

            testClaim = DamageClaim.builder()
                    .id(CLAIM_ID)
                    .booking(booking)
                    .guest(guest)
                    .approvedAmount(BigDecimal.valueOf(15000))
                    .build();

            when(damageClaimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(testClaim));
        }

        /**
         * Helper: make the primary capture path fail so the fallback direct-charge path is entered.
         * Also set no existing tx for the primary key.
         */
        private void setupPrimaryCaptureFailure() {
            // No existing tx for primary key
            String primaryKey = PaymentIdempotencyKey.forDamageCharge(BOOKING_ID, CLAIM_ID);
            when(txRepository.findByIdempotencyKey(primaryKey)).thenReturn(Optional.empty());

            // Primary capture fails
            when(paymentProvider.capture(any(), any(), eq(primaryKey))).thenReturn(
                    ProviderResult.builder()
                            .outcome(ProviderOutcome.TERMINAL_FAILURE)
                            .errorMessage("Authorization expired")
                            .build()
            );
        }

        /**
         * Helper: make the primary tx already terminally failed (no capture call needed).
         * This simulates a retry after the primary capture was previously recorded as terminal.
         */
        private void setupPrimaryTerminallyFailed() {
            String primaryKey = PaymentIdempotencyKey.forDamageCharge(BOOKING_ID, CLAIM_ID);
            PaymentTransaction terminalPrimaryTx = new PaymentTransaction();
            terminalPrimaryTx.setStatus(PaymentTransactionStatus.FAILED_TERMINAL);
            terminalPrimaryTx.setAmount(BigDecimal.valueOf(15000));
            when(txRepository.findByIdempotencyKey(primaryKey)).thenReturn(Optional.of(terminalPrimaryTx));
        }

        @Test
        @DisplayName("Should return idempotent success when SUCCEEDED fallback tx exists")
        void shouldReturnSuccess_whenSucceededFallbackTxExists() {
            setupPrimaryCaptureFailure();

            String dcKey = PaymentIdempotencyKey.forDamageCharge(BOOKING_ID, CLAIM_ID) + "_dc";
            PaymentTransaction existingDcTx = new PaymentTransaction();
            existingDcTx.setStatus(PaymentTransactionStatus.SUCCEEDED);
            existingDcTx.setProviderReference("txn-dc-001");
            existingDcTx.setAmount(BigDecimal.valueOf(15000));
            existingDcTx.setCurrency("RSD");
            when(txRepository.findByIdempotencyKey(dcKey)).thenReturn(Optional.of(existingDcTx));

            PaymentResult result = paymentService.chargeDamage(CLAIM_ID, "auth-expired");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getTransactionId()).isEqualTo("txn-dc-001");
            // Provider should NOT be called for the direct charge
            verify(paymentProvider, never()).charge(any(PaymentProvider.PaymentRequest.class), eq(dcKey));
        }

        @Test
        @DisplayName("Should return IN_PROGRESS when PROCESSING fallback tx exists")
        void shouldReturnInProgress_whenProcessingFallbackTxExists() {
            setupPrimaryCaptureFailure();

            String dcKey = PaymentIdempotencyKey.forDamageCharge(BOOKING_ID, CLAIM_ID) + "_dc";
            PaymentTransaction existingDcTx = new PaymentTransaction();
            existingDcTx.setStatus(PaymentTransactionStatus.PROCESSING);
            when(txRepository.findByIdempotencyKey(dcKey)).thenReturn(Optional.of(existingDcTx));

            PaymentResult result = paymentService.chargeDamage(CLAIM_ID, "auth-expired");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("IN_PROGRESS");
            verify(paymentProvider, never()).charge(any(PaymentProvider.PaymentRequest.class), eq(dcKey));
        }

        @Test
        @DisplayName("Should short-circuit FAILED_TERMINAL fallback tx without calling provider")
        void shouldShortCircuit_whenFailedTerminalFallbackTxExists() {
            setupPrimaryCaptureFailure();

            String dcKey = PaymentIdempotencyKey.forDamageCharge(BOOKING_ID, CLAIM_ID) + "_dc";
            PaymentTransaction existingDcTx = new PaymentTransaction();
            existingDcTx.setStatus(PaymentTransactionStatus.FAILED_TERMINAL);
            existingDcTx.setAmount(BigDecimal.valueOf(15000));
            when(txRepository.findByIdempotencyKey(dcKey)).thenReturn(Optional.of(existingDcTx));

            PaymentResult result = paymentService.chargeDamage(CLAIM_ID, "auth-expired");

            assertThat(result.isSuccess()).isFalse();
            verify(paymentProvider, never()).charge(any(PaymentProvider.PaymentRequest.class), eq(dcKey));
        }

        @Test
        @DisplayName("Should reuse FAILED_RETRYABLE fallback tx row and retry charge")
        void shouldReuseTx_whenFailedRetryableFallbackTxExists() {
            setupPrimaryCaptureFailure();

            String dcKey = PaymentIdempotencyKey.forDamageCharge(BOOKING_ID, CLAIM_ID) + "_dc";
            PaymentTransaction existingDcTx = new PaymentTransaction();
            existingDcTx.setStatus(PaymentTransactionStatus.FAILED_RETRYABLE);
            existingDcTx.setAmount(BigDecimal.valueOf(15000));
            when(txRepository.findByIdempotencyKey(dcKey)).thenReturn(Optional.of(existingDcTx));

            when(paymentProvider.charge(any(PaymentProvider.PaymentRequest.class), eq(dcKey))).thenReturn(
                    ProviderResult.builder()
                            .outcome(ProviderOutcome.SUCCESS)
                            .providerTransactionId("txn-dc-retry")
                            .build()
            );

            PaymentResult result = paymentService.chargeDamage(CLAIM_ID, "pm_card");

            // Existing row was reused (set to PROCESSING then updated)
            assertThat(existingDcTx.getStatus()).isNotEqualTo(PaymentTransactionStatus.FAILED_RETRYABLE);
            verify(paymentProvider).charge(any(PaymentProvider.PaymentRequest.class), eq(dcKey));
        }

        // ================================================================
        // P0 FIX: Primary FAILED_TERMINAL must consult fallback _dc state
        // ================================================================

        @Test
        @DisplayName("P0: Primary FAILED_TERMINAL + fallback SUCCEEDED → return success, no provider call")
        void shouldReturnFallbackSuccess_whenPrimaryTerminallyFailed() {
            setupPrimaryTerminallyFailed();

            String dcKey = PaymentIdempotencyKey.forDamageCharge(BOOKING_ID, CLAIM_ID) + "_dc";
            PaymentTransaction succeededDcTx = new PaymentTransaction();
            succeededDcTx.setStatus(PaymentTransactionStatus.SUCCEEDED);
            succeededDcTx.setProviderReference("txn-dc-already-ok");
            succeededDcTx.setAmount(BigDecimal.valueOf(15000));
            succeededDcTx.setCurrency("RSD");
            when(txRepository.findByIdempotencyKey(dcKey)).thenReturn(Optional.of(succeededDcTx));

            PaymentResult result = paymentService.chargeDamage(CLAIM_ID, "auth-expired");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getTransactionId()).isEqualTo("txn-dc-already-ok");
            // Provider must NOT be called — neither capture nor charge
            verify(paymentProvider, never()).capture(any(), any(), any());
            verify(paymentProvider, never()).charge(any(PaymentProvider.PaymentRequest.class), any());
        }

        @Test
        @DisplayName("P0: Primary FAILED_TERMINAL + fallback FAILED_RETRYABLE → retry fallback charge")
        void shouldRetryFallback_whenPrimaryTerminalAndFallbackRetryable() {
            setupPrimaryTerminallyFailed();

            String dcKey = PaymentIdempotencyKey.forDamageCharge(BOOKING_ID, CLAIM_ID) + "_dc";
            PaymentTransaction retryableDcTx = new PaymentTransaction();
            retryableDcTx.setStatus(PaymentTransactionStatus.FAILED_RETRYABLE);
            retryableDcTx.setAmount(BigDecimal.valueOf(15000));
            when(txRepository.findByIdempotencyKey(dcKey)).thenReturn(Optional.of(retryableDcTx));

            when(paymentProvider.charge(any(PaymentProvider.PaymentRequest.class), eq(dcKey))).thenReturn(
                    ProviderResult.builder()
                            .outcome(ProviderOutcome.SUCCESS)
                            .providerTransactionId("txn-dc-retry-after-primary-terminal")
                            .build()
            );

            PaymentResult result = paymentService.chargeDamage(CLAIM_ID, "pm_card");

            assertThat(result.isSuccess()).isTrue();
            // Primary capture must NOT be called — only fallback charge
            verify(paymentProvider, never()).capture(any(), any(), any());
            verify(paymentProvider).charge(any(PaymentProvider.PaymentRequest.class), eq(dcKey));
        }

        @Test
        @DisplayName("P0: Primary FAILED_TERMINAL + no fallback tx → execute fresh fallback charge")
        void shouldExecuteFreshFallback_whenPrimaryTerminalAndNoFallbackExists() {
            setupPrimaryTerminallyFailed();

            String dcKey = PaymentIdempotencyKey.forDamageCharge(BOOKING_ID, CLAIM_ID) + "_dc";
            when(txRepository.findByIdempotencyKey(dcKey)).thenReturn(Optional.empty());

            when(paymentProvider.charge(any(PaymentProvider.PaymentRequest.class), eq(dcKey))).thenReturn(
                    ProviderResult.builder()
                            .outcome(ProviderOutcome.SUCCESS)
                            .providerTransactionId("txn-dc-fresh")
                            .build()
            );

            PaymentResult result = paymentService.chargeDamage(CLAIM_ID, "pm_card");

            assertThat(result.isSuccess()).isTrue();
            verify(paymentProvider, never()).capture(any(), any(), any());
            verify(paymentProvider).charge(any(PaymentProvider.PaymentRequest.class), eq(dcKey));
        }
    }
}
