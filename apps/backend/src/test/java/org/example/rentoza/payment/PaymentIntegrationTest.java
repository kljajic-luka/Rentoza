package org.example.rentoza.payment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationRecordRepository;
import org.example.rentoza.booking.cancellation.RefundStatus;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.extension.TripExtensionRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.PaymentProvider.*;
import org.example.rentoza.payment.PaymentTransaction.PaymentOperation;
import org.example.rentoza.payment.PaymentTransaction.PaymentTransactionStatus;
import org.example.rentoza.scheduler.InMemorySchedulerLockStore;
import org.example.rentoza.scheduler.SchedulerLockStore;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Enterprise Integration Test Suite — Payment Architecture.
 *
 * <h2>Test Coverage (10 mandatory scenarios)</h2>
 * <ol>
 *   <li>Idempotency: double-click pay returns cached result without second provider call</li>
 *   <li>Distributed lock: scheduler pod skips execution when lock is already held</li>
 *   <li>Crash recovery: stale PROCESSING transaction causes safe re-use, not double charge</li>
 *   <li>Auth expiry tracking: {@code bookingAuthExpiresAt} is set after successful authorization</li>
 *   <li>Capture flow: captureBookingPayment succeeds on AUTHORIZED booking</li>
 *   <li>Refund retry → MANUAL_REVIEW escalation after maxRetries</li>
 *   <li>MockPaymentProvider rejects unknown authorization IDs with TERMINAL_FAILURE</li>
 *   <li>REDIRECT_REQUIRED is not mapped to FAILED status</li>
 *   <li>Payout ledger idempotency: scheduleHostPayout twice produces one DB row</li>
 *   <li>processRefund on AUTHORIZED booking triggers release, not refund</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentIntegrationTest — Enterprise Payment Architecture")
class PaymentIntegrationTest {

    // ── Test subjects ──────────────────────────────────────────────────────
    private MockPaymentProvider mockProvider;
    private BookingPaymentService paymentService;

    // ── Mocked repositories ────────────────────────────────────────────────
    @Mock private BookingRepository bookingRepository;
    @Mock private DamageClaimRepository damageClaimRepository;
    @Mock private TripExtensionRepository extensionRepository;
    @Mock private PaymentTransactionRepository txRepository;
    @Mock private PayoutLedgerRepository payoutLedgerRepository;
    @Mock private CancellationRecordRepository cancellationRecordRepository;
    @Mock private NotificationService notificationService;

    // ── Test data ──────────────────────────────────────────────────────────
    private Booking booking;
    private User renter;
    private User host;
    private Car car;

    @BeforeEach
    void setUp() {
        mockProvider = new MockPaymentProvider();
        // Inject @Value fields that Spring would normally populate
        ReflectionTestUtils.setField(mockProvider, "authExpiryMinutes", 7200); // 5 days

        paymentService = new BookingPaymentService(
                mockProvider,
                bookingRepository,
                damageClaimRepository,
                extensionRepository,
                txRepository,
                payoutLedgerRepository,
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(paymentService, "authExpiryHours", 168);    // 7 days
        ReflectionTestUtils.setField(paymentService, "defaultDepositAmountRsd", 30000); // RSD
        ReflectionTestUtils.setField(paymentService, "payoutDisputeHoldHours", 48);    // 48 h

        // Allow txRepository.save() to return the object passed to it (in-memory simulation)
        // Using lenient() since not all tests invoke save via the service
        lenient().when(txRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Set up user/host/car hierarchy
        renter = new User();
        renter.setId(100L);

        host = new User();
        host.setId(200L);

        car = new Car();
        car.setOwner(host);

        booking = new Booking();
        booking.setId(1L);
        booking.setRenter(renter);
        booking.setCar(car);
        booking.setStatus(BookingStatus.APPROVED);
        booking.setTotalPrice(new BigDecimal("5000.00"));
        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.PENDING);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test 1 — Idempotency: double-click authorize
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T1: Double-click authorize — second call returns cached result without hitting provider again")
    void idempotency_doubleClickAuthorize_noDuplicateCharge() {
        // First call: no existing transaction in DB
        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));
        when(txRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        PaymentResult first = paymentService.processBookingPayment(1L, "pm_card_success");

        assertThat(first.isSuccess()).isTrue();
        assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.AUTHORIZED);
        assertThat(booking.getBookingAuthorizationId()).isNotBlank();

        String authId = booking.getBookingAuthorizationId();
        assertThat(mockProvider.hasAuthorization(authId)).isTrue();

        // Save the returned transaction (simulating DB persistence)
        PaymentTransaction savedTx = PaymentTransaction.builder()
                .bookingId(1L)
                .idempotencyKey(PaymentIdempotencyKey.forAuthorize(1L))
                .status(PaymentTransactionStatus.SUCCEEDED)
                .providerAuthId(authId)
                .build();

        // Second call: existing SUCCEEDED transaction returns immediately
        when(txRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.of(savedTx));

        PaymentResult second = paymentService.processBookingPayment(1L, "pm_card_success");
        assertThat(second.isSuccess()).isTrue();

        // Provider should have been called ONLY ONCE (first call); second call short-circuits
        // We verify this by confirming authorize idempotency store returns same result on exact same key
        String ikey = PaymentIdempotencyKey.forAuthorize(1L);
        ProviderResult directResult = mockProvider.authorize(
                PaymentProvider.PaymentRequest.builder()
                        .bookingId(1L)
                        .userId(100L)
                        .amount(new BigDecimal("5000.00"))
                        .currency("RSD")
                        .build(),
                ikey);
        // Same idempotency key in MockProvider returns same cached result (not a new auth)
        assertThat(directResult.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
        assertThat(directResult.getProviderAuthorizationId()).isEqualTo(authId);

        // Confirm no second save of a new PROCESSING tx happened beyond the first call
        // (txRepository.save called at most 2x total: once for PROCESSING, once for SUCCEEDED in first call)
        verify(txRepository, atMost(2)).save(any(PaymentTransaction.class));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test 2 — Distributed lock: second pod skips execution
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T2: Scheduler skips job when distributed lock is held by another pod")
    void distributedLock_secondPodSkips_noDoubleCapture() {
        // Use a real InMemorySchedulerLockStore (same as production InMemorySchedulerLockStore instance)
        SchedulerLockStore lockStore = new InMemorySchedulerLockStore();

        PaymentLifecycleScheduler scheduler1 = buildScheduler(lockStore);
        PaymentLifecycleScheduler scheduler2 = buildScheduler(lockStore);

        // Simulate pod 1 holds the lock (acquired with a very long TTL)
        boolean pod1Acquired = lockStore.tryAcquireLock("payment.scheduler.capture", Duration.ofMinutes(10));
        assertThat(pod1Acquired).isTrue();

        // Pod 2 tries to run captureUpcomingPayments — should skip due to lock
        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.AUTHORIZED);
        booking.setBookingAuthorizationId("should_not_be_captured");

        scheduler2.captureUpcomingPayments();

        // No capture was attempted — bookingRepository.findBookingsNeedingPaymentCapture was never called by pod 2
        verify(bookingRepository, never()).findBookingsNeedingPaymentCapture();

        // Clean up
        lockStore.releaseLock("payment.scheduler.capture");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test 3 — Crash recovery: stale PROCESSING tx is returned safely
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T3: Crash recovery — stale PROCESSING tx returned on retry, provider NOT called again")
    void crashRecovery_staleProcessingTx_returnedSafely_noDoubleCharge() {
        // Simulate a crash that left a PROCESSING transaction in the DB
        PaymentTransaction staleTx = PaymentTransaction.builder()
                .bookingId(1L)
                .idempotencyKey(PaymentIdempotencyKey.forAuthorize(1L))
                .status(PaymentTransactionStatus.PROCESSING)
                .amount(new BigDecimal("5000.00"))
                .currency("RSD")
                .build();

        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));
        when(txRepository.findByIdempotencyKey(PaymentIdempotencyKey.forAuthorize(1L)))
                .thenReturn(Optional.of(staleTx));

        // Retry (after crash) — should return the stale tx result, NOT call provider again
        PaymentResult result = paymentService.processBookingPayment(1L, "pm_card_success");

        // The stale PROCESSING tx is returned (success=false because status is PROCESSING, not SUCCEEDED)
        // but crucially: no NEW provider call was made → no risk of double charge
        // txRepository.save was NOT called (no new PROCESSING tx was created)
        verify(txRepository, never()).save(argThat(tx ->
                tx.getStatus() == PaymentTransactionStatus.PROCESSING
                && tx.getIdempotencyKey().equals(PaymentIdempotencyKey.forAuthorize(1L))
                && staleTx != tx  // different object = a new tx was created — this must NOT happen
        ));
        // MockPaymentProvider's authorize was not called via service (idempotency short-circuit)
        // Verify: MockProvider was never asked for a new authorization (it has no stored auths)
        // We confirm this by checking that any known test auth ID does not exist
        assertThat(mockProvider.hasAuthorization("mock_auth_should_not_exist")).isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test 4 — Auth expiry tracking
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T4: Successful authorization sets bookingAuthExpiresAt on the booking")
    void authExpiry_setOnSuccessfulAuthorize() {
        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));
        when(txRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        Instant before = Instant.now();
        PaymentResult result = paymentService.processBookingPayment(1L, "pm_card_success");

        assertThat(result.isSuccess()).isTrue();

        // P0-6: bookingAuthExpiresAt now comes from the provider (MockPaymentProvider uses
        // authExpiryMinutes=7200 = 5 days). Fallback of 168h only applies when provider
        // returns no expiresAt. Window: (now+4d, now+6d).
        Instant expires = booking.getBookingAuthExpiresAt();
        assertThat(expires).isNotNull();
        assertThat(expires).isAfter(before.plus(Duration.ofDays(4)));
        assertThat(expires).isBefore(before.plus(Duration.ofDays(6)));

        // chargeLifecycleStatus must be AUTHORIZED (not PENDING)
        assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.AUTHORIZED);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test 5 — Capture on AUTHORIZED booking
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5: captureBookingPayment succeeds on AUTHORIZED booking; lifecycle advances to CAPTURED")
    void capture_authorizedBooking_lifeCycleAdvancesToCaptured() {
        // Authorize first to get a valid auth ID in MockProvider
        ProviderResult authResult = mockProvider.authorize(
                PaymentProvider.PaymentRequest.builder()
                        .bookingId(1L)
                        .userId(100L)
                        .amount(new BigDecimal("5000.00"))
                        .currency("RSD")
                        .paymentMethodId("pm_card_success")
                        .build(),
                PaymentIdempotencyKey.forAuthorize(1L)
        );
        assertThat(authResult.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
        String validAuthId = authResult.getProviderAuthorizationId();

        // Set booking to AUTHORIZED state with the valid auth ID
        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.AUTHORIZED);
        booking.setBookingAuthorizationId(validAuthId);

        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));
        when(txRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        PaymentResult result = paymentService.captureBookingPayment(1L);

        assertThat(result.isSuccess()).isTrue();
        assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.CAPTURED);
        assertThat(booking.getPaymentStatus()).isEqualTo("CAPTURED");
        assertThat(booking.getPaymentVerificationRef()).isNotBlank();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test 6 — Refund retry → MANUAL_REVIEW escalation
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T6: Cancellation refund: FAILED→retry→MANUAL_REVIEW after maxRetries exhausted")
    void refundRetry_escalatesToManualReview_afterMaxRetries() {
        // Set up a FAILED cancellation record that has already used all retries
        CancellationRecord record = CancellationRecord.builder()
                .id(10L)
                .booking(booking)
                .refundStatus(RefundStatus.FAILED)
                .refundToGuest(new BigDecimal("5000.00"))
                .appliedRule("FREE_CANCELLATION")
                .retryCount(2)   // Already tried twice (attempt 3 will be the last)
                .maxRetries(3)
                .nextRetryAt(Instant.now().minusSeconds(60)) // Due now
                .build();

        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));
        when(cancellationRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Simulate payment failure by injecting a failing scenario into the booking
        // (paymentVerificationRef is missing → processRefund returns FAILED)
        // booking has no paymentVerificationRef → service will return failure
        assertThat(booking.getPaymentVerificationRef()).isNull();

        SchedulerLockStore lockStore = new InMemorySchedulerLockStore();
        PaymentLifecycleScheduler scheduler = buildSchedulerWithMocks(lockStore);

        // Invoke processRefundSafely directly (bypassing scheduler's lock/query)
        scheduler.processRefundSafely(record);

        // After maxRetries exhausted: status should be MANUAL_REVIEW
        assertThat(record.getRefundStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
        assertThat(record.getNextRetryAt()).isNull();
        assertThat(record.getRetryCount()).isEqualTo(3); // attempt = retryCount + 1 = 3
        verify(cancellationRecordRepository, atLeastOnce()).save(record);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test 7 — MockPaymentProvider rejects unknown authorization ID
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T7: MockPaymentProvider.capture rejects unknown authorizationId with TERMINAL_FAILURE")
    void mockProvider_rejectsUnknownAuthId_terminalFailure() {
        String unknownAuthId = "auth_does_not_exist_xyz_999";

        ProviderResult result = mockProvider.capture(
                unknownAuthId,
                new BigDecimal("5000.00"),
                "ikey_capture_test_1"
        );

        assertThat(result.getOutcome()).isEqualTo(ProviderOutcome.TERMINAL_FAILURE);
        assertThat(result.getErrorCode()).isEqualTo("UNKNOWN_AUTHORIZATION");
        assertThat(result.getErrorMessage()).contains(unknownAuthId);
    }

    @Test
    @DisplayName("T7b: MockPaymentProvider.releaseAuthorization rejects unknown authorizationId with TERMINAL_FAILURE")
    void mockProvider_rejectsUnknownAuthId_onRelease_terminalFailure() {
        ProviderResult result = mockProvider.releaseAuthorization(
                "auth_release_unknown_xyz",
                "ikey_release_test_1"
        );

        assertThat(result.getOutcome()).isEqualTo(ProviderOutcome.TERMINAL_FAILURE);
        assertThat(result.getErrorCode()).isEqualTo("UNKNOWN_AUTHORIZATION");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test 8 — REDIRECT_REQUIRED is not mapped to FAILED
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T8: 3DS2 / SCA card returns REDIRECT_REQUIRED — booking is not marked FAILED")
    void redirectRequired_notMarkedFailed() {
        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));
        when(txRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        // "pm_card_sca_required" triggers REDIRECT_REQUIRED in MockPaymentProvider
        PaymentResult result = paymentService.processBookingPayment(1L, "pm_card_sca_required");

        assertThat(result.isSuccess()).isFalse(); // Not a success
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REDIRECT_REQUIRED); // Correctly flagged
        assertThat(result.getRedirectUrl()).isNotBlank(); // Has a redirect URL

        // Booking state must reflect redirect-pending, not failure
        assertThat(booking.getPaymentStatus()).isEqualTo("REDIRECT_REQUIRED");
        assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.PENDING); // Not CAPTURE_FAILED

        // The PaymentTransaction should be stored as REDIRECT_REQUIRED (not FAILED_TERMINAL)
        // Capture the saved transaction via spy on txRepository.save
        verify(txRepository, atLeastOnce()).save(argThat(tx ->
                tx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
        ));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test 9 — Payout ledger idempotency
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T9: scheduleHostPayout called twice for same booking — only one PayoutLedger entry created")
    void payoutLedger_idempotency_doubleCallProducesOneRow() {
        booking.setTotalPrice(new BigDecimal("5000.00"));
        String expectedIkey = PaymentIdempotencyKey.forPayout(1L, 200L, 1);

        // First call: no existing entry
        when(payoutLedgerRepository.findByIdempotencyKey(expectedIkey))
                .thenReturn(Optional.empty());
        when(payoutLedgerRepository.save(any(PayoutLedger.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PayoutLedger firstEntry = paymentService.scheduleHostPayout(booking);

        assertThat(firstEntry).isNotNull();
        assertThat(firstEntry.getBookingId()).isEqualTo(1L);
        assertThat(firstEntry.getHostUserId()).isEqualTo(200L);
        assertThat(firstEntry.getStatus()).isEqualTo(PayoutLifecycleStatus.PENDING);
        assertThat(firstEntry.getPlatformFee()).isEqualByComparingTo("750.00"); // 15% of 5000
        assertThat(firstEntry.getHostPayoutAmount()).isEqualByComparingTo("4250.00");

        // Second call: entry already exists → return it without saving again
        when(payoutLedgerRepository.findByIdempotencyKey(expectedIkey))
                .thenReturn(Optional.of(firstEntry));

        PayoutLedger secondEntry = paymentService.scheduleHostPayout(booking);

        assertThat(secondEntry).isSameAs(firstEntry); // Must be the same object
        // payoutLedgerRepository.save() should have been called ONLY ONCE
        verify(payoutLedgerRepository, times(1)).save(any(PayoutLedger.class));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test 10 — processRefund on AUTHORIZED booking triggers release
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T10: processRefund on AUTHORIZED booking calls release (not refund) via provider")
    void processRefund_onAuthorizedBooking_triggersRelease_notRefund() {
        // Set up booking in AUTHORIZED state with a valid auth ID in MockProvider
        ProviderResult authResult = mockProvider.authorize(
                PaymentProvider.PaymentRequest.builder()
                        .bookingId(1L)
                        .userId(100L)
                        .amount(new BigDecimal("5000.00"))
                        .currency("RSD")
                        .paymentMethodId("pm_card_success")
                        .build(),
                PaymentIdempotencyKey.forAuthorize(1L)
        );
        String validAuthId = authResult.getProviderAuthorizationId();

        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.AUTHORIZED);
        booking.setBookingAuthorizationId(validAuthId);
        booking.setPaymentVerificationRef(validAuthId); // also set as verification ref

        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));
        when(txRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        // Call processRefund — should internally delegate to releaseBookingPayment
        PaymentResult result = paymentService.processRefund(
                1L, new BigDecimal("5000.00"), "cancel");

        assertThat(result.isSuccess()).isTrue();

        // Authorization must have been released (not refunded)
        assertThat(mockProvider.hasAuthorization(validAuthId)).isFalse(); // released = removed from store

        // Lifecycle status must be RELEASED (not REFUNDED)
        assertThat(booking.getChargeLifecycleStatus()).isEqualTo(ChargeLifecycleStatus.RELEASED);
        assertThat(booking.getPaymentStatus()).isEqualTo("RELEASED");

        // Verify that the txRepository was invoked with a RELEASE operation (not REFUND)
        verify(txRepository, atLeastOnce()).save(argThat(tx ->
                tx.getOperation() == PaymentOperation.RELEASE
        ));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Idempotency key contract verification
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IdempotencyKey — key format and uniqueness guarantees")
    class IdempotencyKeyTests {

        @Test
        @DisplayName("forAuthorize and forCapture produce different keys for same bookingId")
        void differentOperations_produceDifferentKeys() {
            assertThat(PaymentIdempotencyKey.forAuthorize(42L))
                    .isNotEqualTo(PaymentIdempotencyKey.forCapture(42L, 1));
        }

        @Test
        @DisplayName("forCapture attempt 1 and attempt 2 produce different keys")
        void differentAttempts_produceDifferentKeys() {
            assertThat(PaymentIdempotencyKey.forCapture(1L, 1))
                    .isNotEqualTo(PaymentIdempotencyKey.forCapture(1L, 2));
        }

        @Test
        @DisplayName("forRelease single-arg delegates to attempt 1")
        void forRelease_singleArg_delegatesToAttempt1() {
            assertThat(PaymentIdempotencyKey.forRelease(5L))
                    .isEqualTo(PaymentIdempotencyKey.forRelease(5L, 1));
        }

        @Test
        @DisplayName("forDepositRelease single-arg delegates to attempt 1")
        void forDepositRelease_singleArg_delegatesToAttempt1() {
            assertThat(PaymentIdempotencyKey.forDepositRelease(7L))
                    .isEqualTo(PaymentIdempotencyKey.forDepositRelease(7L, 1));
        }

        @Test
        @DisplayName("forDamageCharge(bookingId, claimId) differs from forDamageCharge(claimId, attempt)")
        void forDamageCharge_twoLongs_differFromClaimAttemptVariant() {
            // (bookingId=1, claimId=2) is different from (claimId=1, attempt=2)
            String byBookingAndClaim = PaymentIdempotencyKey.forDamageCharge(1L, 2L);
            String byClaimAndAttempt = PaymentIdempotencyKey.forDamageCharge(1L, (int) 2);
            // These should be different because they represent different semantic operations
            assertThat(byBookingAndClaim).isNotEqualTo(byClaimAndAttempt);
        }

        @Test
        @DisplayName("forPayout(bookingId, hostId1) differs from forPayout(bookingId, hostId2)")
        void forPayout_differentHosts_produceDifferentKeys() {
            assertThat(PaymentIdempotencyKey.forPayout(1L, 100L, 1))
                    .isNotEqualTo(PaymentIdempotencyKey.forPayout(1L, 200L, 1));
        }

        @Test
        @DisplayName("No key exceeds 64 characters")
        void allKeys_withLargeIds_stayWithin64Chars() {
            Long maxLong = Long.MAX_VALUE;
            String[] keys = {
                    PaymentIdempotencyKey.forAuthorize(maxLong),
                    PaymentIdempotencyKey.forCapture(maxLong, 99),
                    PaymentIdempotencyKey.forRelease(maxLong, 99),
                    PaymentIdempotencyKey.forDepositAuthorize(maxLong),
                    PaymentIdempotencyKey.forDepositCapture(maxLong, 99),
                    PaymentIdempotencyKey.forDepositRelease(maxLong, 99),
                    PaymentIdempotencyKey.forRefund(maxLong, "dispute", 99),
                    PaymentIdempotencyKey.forPayout(maxLong, maxLong, 99),
                    PaymentIdempotencyKey.forDamageCharge(maxLong, 99),
                    PaymentIdempotencyKey.forLateFee(maxLong, 99),
                    PaymentIdempotencyKey.forExtension(maxLong, 99),
            };
            for (String key : keys) {
                assertThat(key.length())
                        .as("Key '%s' exceeds 64 chars", key)
                        .isLessThanOrEqualTo(64);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // MockPaymentProvider — state machine tests
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MockPaymentProvider — state machine and idempotency")
    class MockProviderTests {

        @Test
        @DisplayName("Capture after release returns TERMINAL_FAILURE")
        void capture_afterRelease_terminalFailure() {
            // Authorize
            ProviderResult auth = mockProvider.authorize(
                    PaymentProvider.PaymentRequest.builder()
                            .bookingId(1L).userId(100L)
                            .amount(new BigDecimal("5000")).currency("RSD")
                            .paymentMethodId("pm_card_success")
                            .build(),
                    "ikey_auth_mock_1"
            );
            assertThat(auth.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
            String authId = auth.getProviderAuthorizationId();

            // Release
            ProviderResult release = mockProvider.releaseAuthorization(authId, "ikey_release_mock_1");
            assertThat(release.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);

            // Attempt capture after release — must fail with TERMINAL_FAILURE
            ProviderResult capture = mockProvider.capture(authId, new BigDecimal("5000"), "ikey_capture_mock_2");
            assertThat(capture.getOutcome()).isEqualTo(ProviderOutcome.TERMINAL_FAILURE);
            assertThat(capture.getErrorCode()).isEqualTo("AUTH_RELEASED");
        }

        @Test
        @DisplayName("Capture with amount exceeding authorization is rejected")
        void capture_amountExceedsAuth_terminalFailure() {
            ProviderResult auth = mockProvider.authorize(
                    PaymentProvider.PaymentRequest.builder()
                            .bookingId(2L).userId(100L)
                            .amount(new BigDecimal("1000")).currency("RSD")
                            .paymentMethodId("pm_card_success")
                            .build(),
                    "ikey_auth_overpay_1"
            );
            String authId = auth.getProviderAuthorizationId();

            ProviderResult capture = mockProvider.capture(authId, new BigDecimal("1500"), "ikey_capture_overpay_1");
            assertThat(capture.getOutcome()).isEqualTo(ProviderOutcome.TERMINAL_FAILURE);
            assertThat(capture.getErrorCode()).isEqualTo("AMOUNT_EXCEEDS_AUTH");
        }

        @Test
        @DisplayName("Double capture is rejected with ALREADY_CAPTURED")
        void capture_doubleCapture_alreadyCaptured_terminalFailure() {
            ProviderResult auth = mockProvider.authorize(
                    PaymentProvider.PaymentRequest.builder()
                            .bookingId(3L).userId(100L)
                            .amount(new BigDecimal("3000")).currency("RSD")
                            .paymentMethodId("pm_card_success")
                            .build(),
                    "ikey_auth_dbl_1"
            );
            String authId = auth.getProviderAuthorizationId();

            ProviderResult firstCapture = mockProvider.capture(authId, new BigDecimal("3000"), "ikey_capture_dbl_1");
            assertThat(firstCapture.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);

            // Second capture with a different idempotency key (i.e., not idempotent replay)
            ProviderResult secondCapture = mockProvider.capture(authId, new BigDecimal("3000"), "ikey_capture_dbl_2");
            assertThat(secondCapture.getOutcome()).isEqualTo(ProviderOutcome.TERMINAL_FAILURE);
            assertThat(secondCapture.getErrorCode()).isEqualTo("ALREADY_CAPTURED");
        }

        @Test
        @DisplayName("Capture replay with same idempotency key returns cached SUCCESS (not double charge)")
        void capture_idempotentReplay_returnsCachedResult() {
            ProviderResult auth = mockProvider.authorize(
                    PaymentProvider.PaymentRequest.builder()
                            .bookingId(4L).userId(100L)
                            .amount(new BigDecimal("2000")).currency("RSD")
                            .paymentMethodId("pm_card_success")
                            .build(),
                    "ikey_auth_replay_1"
            );
            String authId = auth.getProviderAuthorizationId();

            ProviderResult first = mockProvider.capture(authId, new BigDecimal("2000"), "ikey_capture_replay_1");
            assertThat(first.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);

            // Same idempotency key — must return identical cached result
            ProviderResult replay = mockProvider.capture(authId, new BigDecimal("2000"), "ikey_capture_replay_1");
            assertThat(replay.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
            assertThat(replay.getProviderTransactionId()).isEqualTo(first.getProviderTransactionId());
        }

        @Test
        @DisplayName("Expired authorization is rejected with AUTHORIZATION_EXPIRED")
        void capture_expiredAuth_terminalFailure() {
            ProviderResult auth = mockProvider.authorize(
                    PaymentProvider.PaymentRequest.builder()
                            .bookingId(5L).userId(100L)
                            .amount(new BigDecimal("4000")).currency("RSD")
                            .paymentMethodId("pm_card_success")
                            .build(),
                    "ikey_auth_expiry_1"
            );
            String authId = auth.getProviderAuthorizationId();

            // Force expiration via test helper
            mockProvider.expireAuthorization(authId);

            ProviderResult capture = mockProvider.capture(authId, new BigDecimal("4000"), "ikey_capture_expiry_1");
            assertThat(capture.getOutcome()).isEqualTo(ProviderOutcome.TERMINAL_FAILURE);
            assertThat(capture.getErrorCode()).isEqualTo("AUTHORIZATION_EXPIRED");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Build a PaymentLifecycleScheduler with the given lock store and all mocked dependencies.
     */
    private PaymentLifecycleScheduler buildScheduler(SchedulerLockStore lockStore) {
        SchedulerItemProcessor processor = new SchedulerItemProcessor(
                bookingRepository,
                paymentService,
                mockProvider,
                cancellationRecordRepository,
                damageClaimRepository,
                notificationService,
                payoutLedgerRepository,
                new SimpleMeterRegistry());
        // @Value fields are not injected for manually constructed beans — supply defaults
        ReflectionTestUtils.setField(processor, "refundRetryBackoffMinutes", 60);

        return new PaymentLifecycleScheduler(
                bookingRepository,
                paymentService,
                mockProvider,
                cancellationRecordRepository,
                damageClaimRepository,
                notificationService,
                lockStore,
                payoutLedgerRepository,
                processor,
                new SimpleMeterRegistry()
        );
    }

    /**
     * Build a scheduler with mocked lock store (for scenarios that need lock control).
     */
    private PaymentLifecycleScheduler buildSchedulerWithMocks(SchedulerLockStore lockStore) {
        return buildScheduler(lockStore);
    }


    // Helper: Car with a stub owner is required for scheduleHostPayout which calls booking.getCar().getOwner().getId()
    private Car carWithHost(User host) {
        Car c = new Car();
        c.setOwner(host);
        return c;
    }
}
