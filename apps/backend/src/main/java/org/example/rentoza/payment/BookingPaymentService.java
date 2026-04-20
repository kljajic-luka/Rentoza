package org.example.rentoza.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.dispute.DamageClaim;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.extension.TripExtension;
import org.example.rentoza.booking.extension.TripExtensionRepository;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.payment.PaymentProvider.*;
import org.example.rentoza.payment.PaymentTransaction.PaymentOperation;
import org.example.rentoza.payment.PaymentTransaction.PaymentTransactionStatus;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

/**
 * Service for orchestrating booking-related payments.
 *
 * <h2>Payment Flows</h2>
 * <ul>
 *   <li><b>Booking:</b> Authorize → Capture on trip confirmation</li>
 *   <li><b>Security Deposit:</b> Authorized at check-in, released/captured at checkout</li>
 *   <li><b>Damage Charge:</b> Charged against deposit or separate charge</li>
 *   <li><b>Late Fee / Extension:</b> Additional charge flows</li>
 *   <li><b>Payout:</b> Host payout after dispute window via PayoutLedger</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * Every provider call uses a deterministic {@link PaymentIdempotencyKey}.
 * This guarantees safe retries: repeated calls with the same booking state
 * return the cached provider result rather than charging the guest twice.
 *
 * <h2>Ledger</h2>
 * Every provider interaction is persisted in {@link PaymentTransaction}
 * before the call is made (PROCESSING state) and updated on completion.
 * This provides a complete audit trail and supports crash recovery.
 */
@Service
@Slf4j
public class BookingPaymentService {

    private static final String DEFAULT_CURRENCY = "RSD";

    private final PaymentProvider paymentProvider;
    private final BookingRepository bookingRepository;
    private final DamageClaimRepository damageClaimRepository;
    private final TripExtensionRepository extensionRepository;
    private final PaymentTransactionRepository txRepository;
    private final PayoutLedgerRepository payoutLedgerRepository;
    private final UserRepository userRepository;
    private final TaxWithholdingService taxWithholdingService;

    // Metrics
    private final Counter paymentSuccessCounter;
    private final Counter paymentFailedCounter;
    private final Counter depositAuthorizedCounter;
    private final Counter depositReleasedCounter;
    private final Counter payoutManualReviewCounter;

    @Value("${app.payment.deposit.amount-rsd:30000}")
    private int defaultDepositAmountRsd = 30000;

    // AUDIT-H2-FIX: Read rates from config to prevent silent divergence when rates change.
    @Value("${app.payment.service-fee-rate:0.15}")
    private BigDecimal platformFeeRate;

    @Value("${app.payment.payout.pdv-rate:0.20}")
    private BigDecimal pdvRate;

    @Value("${app.payment.auth.expiry-hours:168}")  // 7 days
    private int authExpiryHours = 168;

    @Value("${app.payment.payout.dispute-hold-hours:48}")
    private int payoutDisputeHoldHours = 48;

    public BookingPaymentService(
            PaymentProvider paymentProvider,
            BookingRepository bookingRepository,
            DamageClaimRepository damageClaimRepository,
            TripExtensionRepository extensionRepository,
            PaymentTransactionRepository txRepository,
            PayoutLedgerRepository payoutLedgerRepository,
            UserRepository userRepository,
            TaxWithholdingService taxWithholdingService,
            MeterRegistry meterRegistry) {
        this.paymentProvider = paymentProvider;
        this.bookingRepository = bookingRepository;
        this.damageClaimRepository = damageClaimRepository;
        this.extensionRepository = extensionRepository;
        this.txRepository = txRepository;
        this.payoutLedgerRepository = payoutLedgerRepository;
        this.userRepository = userRepository;
        this.taxWithholdingService = taxWithholdingService;

        this.paymentSuccessCounter = Counter.builder("payment.success")
                .description("Successful payments")
                .register(meterRegistry);
        this.paymentFailedCounter = Counter.builder("payment.failed")
                .description("Failed payments")
                .register(meterRegistry);
        this.depositAuthorizedCounter = Counter.builder("payment.deposit.authorized")
                .description("Deposits authorized")
                .register(meterRegistry);
        this.depositReleasedCounter = Counter.builder("payment.deposit.released")
                .description("Deposits released")
                .register(meterRegistry);
        this.payoutManualReviewCounter = Counter.builder("payment.alert.payout.manual_review")
                .tag("severity", "high")
                .description("Payout escalations to MANUAL_REVIEW")
                .register(meterRegistry);
    }

    // ========== BOOKING PAYMENT ==========

    /**
     * Authorize initial booking payment (hold, not capture).
     *
     * <p>Idempotent: repeated calls with the same bookingId return the cached
     * provider result rather than issuing a new authorization.
     */
    @Transactional
    public PaymentResult processBookingPayment(Long bookingId, String paymentMethodId) {
        Booking booking = getBooking(bookingId);

        String ikey = PaymentIdempotencyKey.forAuthorize(bookingId);

        // P1-FIX: Handle ALL terminal/retryable states — not just SUCCEEDED.
        // FAILED_RETRYABLE rows must reuse the existing row (to avoid unique-key
        // violation) and be re-attempted with a fresh provider call.
        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction ex = existing.get();
            if (ex.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                log.info("[Payment] Idempotent hit for booking {} authorize — returning cached tx {}", bookingId, ex.getId());
                return toLegacyResult(ex);
            }
            if (ex.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                    || ex.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION) {
                log.info("[Payment] Booking {} authorize in redirect/pending — returning current state", bookingId);
                return toLegacyResult(ex);
            }
            if (ex.getStatus() == PaymentTransactionStatus.PROCESSING) {
                log.warn("[Payment] Authorize for booking {} already in progress", bookingId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Authorization already in progress; wait and retry")
                        .status(PaymentStatus.FAILED).build();
            }
            if (ex.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                log.warn("[Payment] Terminal authorize failure for booking {} — not retrying with same key", bookingId);
                return toLegacyResult(ex);
            }
            // FAILED_RETRYABLE: reuse row to avoid unique-key constraint violation
            ex.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(ex);
        } else {
            tx = createTx(bookingId, booking.getRenter().getId(),
                    PaymentOperation.AUTHORIZE, ikey, booking.getTotalPrice());
        }

        PaymentRequest request = PaymentRequest.builder()
                .bookingId(bookingId)
                .userId(booking.getRenter().getId())
                .amount(booking.getTotalPrice())
                .currency(DEFAULT_CURRENCY)
                .description("Rezervacija #" + bookingId)
                .type(PaymentType.BOOKING_PAYMENT)
                .paymentMethodId(paymentMethodId)
                .build();

        ProviderResult result = paymentProvider.authorize(request, ikey);

        updateTx(tx, result);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            booking.setPaymentStatus("AUTHORIZED");
            transitionCharge(booking, ChargeLifecycleStatus.AUTHORIZED, "processBookingPayment");
            booking.setPaymentVerificationRef(result.getProviderAuthorizationId());
            booking.setBookingAuthorizationId(result.getProviderAuthorizationId());
            // P0-6: Use provider-supplied expiry if available; fall back to local constant.
            booking.setBookingAuthExpiresAt(result.getExpiresAt() != null
                    ? result.getExpiresAt()
                    : Instant.now().plusSeconds(authExpiryHours * 3600L));
            bookingRepository.save(booking);
            paymentSuccessCounter.increment();
            log.info("[Payment] Booking {} authorized: {}", bookingId, result.getProviderAuthorizationId());
        } else if (result.getOutcome() == ProviderOutcome.REDIRECT_REQUIRED) {
            booking.setPaymentStatus("REDIRECT_REQUIRED");
            bookingRepository.save(booking);
            log.info("[Payment] Booking {} requires 3DS redirect: {}", bookingId, result.getRedirectUrl());
        } else if (result.getOutcome() == ProviderOutcome.PENDING) {
            booking.setPaymentStatus("PENDING_CONFIRMATION");
            bookingRepository.save(booking);
            log.info("[Payment] Booking {} authorization PENDING async confirmation (authId={})",
                    bookingId, result.getProviderAuthorizationId());
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Booking {} authorization failed: {} ({})",
                    bookingId, result.getErrorMessage(), result.getErrorCode());
        }

        return toLegacyResult(result);
    }

    /**
     * Reauthorize a booking whose prior authorization has expired or is near expiry.
     *
     * <p>Uses a dedicated {@link PaymentIdempotencyKey#forReauth} key (not {@code forAuthorize})
     * so each reauth attempt gets its own provider slot and is not masked by the original
     * authorization entry that caused the {@code REAUTH_REQUIRED} state.
     *
     * <p>The attempt counter is stored in {@code booking.captureAttempts} — repurposed here
     * because reauth happens before any capture and the field is guaranteed 0 at this stage.
     * A dedicated {@code reauth_attempts} column can be added in a future migration if needed.
     */
    @Transactional
    public PaymentResult reauthorizeBookingPayment(Long bookingId, String paymentMethodId) {
        Booking booking = getBooking(bookingId);
        ChargeLifecycleStatus lifecycle = booking.getChargeLifecycleStatus();
        if (lifecycle != ChargeLifecycleStatus.REAUTH_REQUIRED) {
            log.warn("[Payment] Booking {} is not in REAUTH_REQUIRED state (current: {})", bookingId, lifecycle);
            return PaymentResult.builder()
                    .success(false).errorCode("INVALID_STATE")
                    .errorMessage("Booking is not in REAUTH_REQUIRED state")
                    .status(PaymentStatus.FAILED).build();
        }

        // Use attempt counter to distinguish each reauth attempt from the original authorize.
        int attempt = booking.getCaptureAttempts() + 1;
        String ikey = PaymentIdempotencyKey.forReauth(bookingId, attempt);

        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction ex = existing.get();
            if (ex.getStatus() == PaymentTransactionStatus.SUCCEEDED) return toSuccess(ex);
            if (ex.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                    || ex.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION) return toLegacyResult(ex);
            if (ex.getStatus() == PaymentTransactionStatus.PROCESSING) {
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Reauthorization already in progress").status(PaymentStatus.FAILED).build();
            }
            if (ex.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) return toLegacyResult(ex);
            // FAILED_RETRYABLE: reuse row
            ex.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(ex);
        } else {
            tx = createTx(bookingId, booking.getRenter().getId(),
                    PaymentOperation.AUTHORIZE, ikey, booking.getTotalPrice());
        }

        ProviderResult result = paymentProvider.authorize(
                PaymentRequest.builder()
                        .bookingId(bookingId)
                        .userId(booking.getRenter().getId())
                        .amount(booking.getTotalPrice())
                        .currency(DEFAULT_CURRENCY)
                        .description("Reautorizacija rezervacije #" + bookingId)
                        .type(PaymentType.BOOKING_PAYMENT)
                        .paymentMethodId(paymentMethodId)
                        .build(),
                ikey);
        updateTx(tx, result);
        booking.setCaptureAttempts(attempt);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            booking.setPaymentStatus("AUTHORIZED");
            transitionCharge(booking, ChargeLifecycleStatus.AUTHORIZED, "reauthorizeBookingPayment");
            booking.setPaymentVerificationRef(result.getProviderAuthorizationId());
            booking.setBookingAuthorizationId(result.getProviderAuthorizationId());
            booking.setBookingAuthExpiresAt(result.getExpiresAt() != null
                    ? result.getExpiresAt()
                    : Instant.now().plusSeconds(authExpiryHours * 3600L));
            paymentSuccessCounter.increment();
            log.info("[Payment] Booking {} reauthorized: {}", bookingId, result.getProviderAuthorizationId());
        } else if (result.getOutcome() == ProviderOutcome.REDIRECT_REQUIRED) {
            booking.setPaymentStatus("REDIRECT_REQUIRED");
            log.info("[Payment] Booking {} reauth requires 3DS redirect: {}", bookingId, result.getRedirectUrl());
        } else if (result.getOutcome() == ProviderOutcome.PENDING) {
            booking.setPaymentStatus("PENDING_CONFIRMATION");
            log.info("[Payment] Booking {} reauth PENDING async confirmation (authId={})",
                    bookingId, result.getProviderAuthorizationId());
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Booking {} reauth failed: {} ({})",
                    bookingId, result.getErrorMessage(), result.getErrorCode());
        }
        bookingRepository.save(booking);
        return toLegacyResult(result);
    }

    /**
     * Capture at handshake confirmation — runs in a new, isolated transaction.
     *
     * <p>Using {@link Propagation#REQUIRES_NEW} ensures that a capture failure cannot
     * mark the caller’s {@link Propagation#REQUIRED} transaction as rollback-only,
     * which would silently roll back the IN_TRIP status transition.
     *
     * <p>Callers MUST catch all exceptions from this method. The scheduler
     * ({@link PaymentLifecycleScheduler}) acts as the fallback for any capture that
     * does not complete here.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentResult captureBookingPaymentNow(Long bookingId) {
        return captureBookingPayment(bookingId);
    }

    /**
     * Authorize the security deposit at check-in window opening — isolated transaction.
     *
     * <p><b>P0-3 fix:</b> Deposit authorization is no longer taken at booking creation
     * (a 7-day hold can expire before a long-lead-time trip starts). It is now placed
     * T-Xh before the trip start when the check-in window opens, keeping the hold
     * within the card-authorization lifetime window.
     *
     * <p>Uses {@link Propagation#REQUIRES_NEW} so that a deposit auth failure cannot
     * roll back the check-in window transition in the caller's transaction.
     * Callers MUST catch all exceptions and handle gracefully
     * (log + notify; do NOT block check-in window opening on deposit auth failure).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentResult authorizeDepositAtCheckIn(Long bookingId, String paymentMethodId) {
        Booking booking = getBooking(bookingId);
        // Idempotent guard — scheduler may fire the check-in window opening more than once
        if (booking.getDepositLifecycleStatus() == DepositLifecycleStatus.AUTHORIZED
                && booking.getDepositAuthorizationId() != null) {
            log.info("[Payment] Deposit already AUTHORIZED for booking {} — skipping re-auth at check-in", bookingId);
            return PaymentResult.builder().success(true).status(PaymentStatus.SUCCESS)
                    .authorizationId(booking.getDepositAuthorizationId()).build();
        }
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            log.warn("[Payment] Cannot authorize deposit at check-in for booking {} — no stored payment method", bookingId);
            return PaymentResult.builder().success(false).errorCode("NO_PAYMENT_METHOD")
                    .errorMessage("No stored payment method available for deposit authorization")
                    .status(PaymentStatus.FAILED).build();
        }
        return authorizeDeposit(bookingId, paymentMethodId);
    }

    /**
     * Capture a previously authorized booking payment.
     * Called on CHECK_IN_COMPLETE (trip confirmed active).
     */
    @Transactional
    public PaymentResult captureBookingPayment(Long bookingId) {
        Booking booking = getBooking(bookingId);

        ChargeLifecycleStatus lifecycle = booking.getChargeLifecycleStatus();
        if (lifecycle != ChargeLifecycleStatus.AUTHORIZED && lifecycle != ChargeLifecycleStatus.CAPTURE_FAILED) {
            log.warn("[Payment] Booking {} capture blocked: invalid lifecycle {}", bookingId, lifecycle);
            return PaymentResult.builder()
                    .success(false)
                    .errorCode("INVALID_STATE")
                    .errorMessage("Booking is not in a capturable payment state")
                    .status(PaymentStatus.FAILED)
                    .build();
        }

        String authorizationId = booking.getBookingAuthorizationId();
        if (authorizationId == null || authorizationId.isBlank()) {
            return PaymentResult.builder()
                    .success(false)
                    .errorMessage("No booking authorization to capture")
                    .status(PaymentStatus.FAILED)
                    .build();
        }

        int attempt = booking.getCaptureAttempts() + 1;
        String ikey = PaymentIdempotencyKey.forCapture(bookingId, attempt);

        // P1-7: Handle all terminal idempotency states, not just SUCCEEDED.
        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction existingTx = existing.get();
            if (existingTx.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                log.info("[Payment] Idempotent hit for booking {} capture attempt {}", bookingId, attempt);
                return toSuccess(existingTx);
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.PROCESSING) {
                log.warn("[Payment] Capture for booking {} attempt {} already in progress", bookingId, attempt);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Capture already in progress; wait and retry").status(PaymentStatus.FAILED).build();
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                log.warn("[Payment] Capture for booking {} attempt {} terminally failed — not retrying", bookingId, attempt);
                return toLegacyResult(existingTx);
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                    || existingTx.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION) {
                log.warn("[Payment] Capture for booking {} attempt {} awaiting async confirmation", bookingId, attempt);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Capture awaiting async confirmation; wait and retry").status(PaymentStatus.FAILED).build();
            }
            // FAILED_RETRYABLE: reuse existing row to avoid unique-constraint violation.
            existingTx.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(existingTx);
        } else {
            tx = createTx(bookingId, booking.getRenter().getId(),
                    PaymentOperation.CAPTURE, ikey, booking.getTotalPrice());
        }

        ProviderResult result = paymentProvider.capture(authorizationId, booking.getTotalPrice(), ikey);

        updateTx(tx, result);
        booking.setCaptureAttempts(attempt);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            booking.setPaymentStatus("CAPTURED");
            transitionCharge(booking, ChargeLifecycleStatus.CAPTURED, "captureBookingPayment");
            booking.setPaymentVerificationRef(result.getProviderTransactionId());
            bookingRepository.save(booking);
            paymentSuccessCounter.increment();
            log.info("[Payment] Booking {} payment captured: {}", bookingId, result.getProviderTransactionId());
        } else if ("ALREADY_CAPTURED".equals(result.getErrorCode())) {
            // P1-9: Provider rejected as duplicate but the capture DID succeed previously.
            // Treat as idempotent success rather than failure.
            booking.setPaymentStatus("CAPTURED");
            transitionCharge(booking, ChargeLifecycleStatus.CAPTURED, "captureBookingPayment.ALREADY_CAPTURED");
            bookingRepository.save(booking);
            paymentSuccessCounter.increment();
            log.info("[Payment] Booking {} already captured (ALREADY_CAPTURED) — treating as success", bookingId);
            return PaymentResult.builder().success(true).status(PaymentStatus.SUCCESS)
                    .amount(booking.getTotalPrice()).build();
        } else if (result.getOutcome() == ProviderOutcome.PENDING) {
            // Async capture confirmation pending — do NOT mark as CAPTURE_FAILED.
            // Webhook (PAYMENT_CONFIRMED) will finalize.
            booking.setPaymentStatus("PENDING_CONFIRMATION");
            bookingRepository.save(booking);
            log.info("[Payment] Booking {} capture PENDING async confirmation", bookingId);
        } else {
            transitionCharge(booking, ChargeLifecycleStatus.CAPTURE_FAILED, "captureBookingPayment");
            bookingRepository.save(booking);
            paymentFailedCounter.increment();
            log.warn("[Payment] Booking {} capture failed: {} ({})",
                    bookingId, result.getErrorMessage(), result.getErrorCode());
        }

        return toLegacyResult(result);
    }

    /**
     * Release a previously authorized booking payment hold.
     * Called when host declines or booking expires.
     */
    @Transactional
    public PaymentResult releaseBookingPayment(Long bookingId) {
        Booking booking = getBooking(bookingId);

        String authorizationId = booking.getBookingAuthorizationId();
        if (authorizationId == null || authorizationId.isBlank()) {
            log.warn("[Payment] No booking authorization to release for booking {}", bookingId);
            return PaymentResult.builder()
                    .success(true)
                    .status(PaymentStatus.CANCELLED)
                    .build();
        }

        String ikey = PaymentIdempotencyKey.forRelease(bookingId);

        // P0-3: Handle all settled idempotency states to avoid unique-key collision.
        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction ex = existing.get();
            if (ex.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                log.info("[Payment] Idempotent hit for booking {} release", bookingId);
                return toSuccess(ex);
            }
            if (ex.getStatus() == PaymentTransactionStatus.PROCESSING) {
                log.warn("[Payment] Release already in-progress for booking {}", bookingId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Release already in progress; wait and retry")
                        .status(PaymentStatus.FAILED).build();
            }
            if (ex.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                log.warn("[Payment] Terminal release failure for booking {} — not retrying", bookingId);
                return toLegacyResult(ex);
            }
            // FAILED_RETRYABLE: reuse existing row to avoid unique-key collision on new insert
            ex.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(ex);
        } else {
            tx = createTx(bookingId, booking.getRenter().getId(),
                    PaymentOperation.RELEASE, ikey, booking.getTotalPrice());
        }

        ProviderResult result = paymentProvider.releaseAuthorization(authorizationId, ikey);

        updateTx(tx, result);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            booking.setPaymentStatus("RELEASED");
            transitionCharge(booking, ChargeLifecycleStatus.RELEASED, "releaseBookingPayment");
            bookingRepository.save(booking);
            log.info("[Payment] Booking {} payment hold released: {}", bookingId, authorizationId);
        } else {
            transitionCharge(booking, ChargeLifecycleStatus.RELEASE_FAILED, "releaseBookingPayment");
            bookingRepository.save(booking);
            log.warn("[Payment] Failed to release booking {} payment hold: {}",
                    bookingId, result.getErrorMessage());
        }

        return toLegacyResult(result);
    }

    // ========== SECURITY DEPOSIT ==========

    /**
     * Authorize security deposit at check-in.
     * Amount is held but not captured until checkout.
     */
    @Transactional
    public PaymentResult authorizeDeposit(Long bookingId, String paymentMethodId) {
        Booking booking = getBooking(bookingId);

        // M1+TASK2: Prefer the per-tier effective deposit snapshot (which reflects the
        // protection-tier-adjusted amount at booking creation), falling back to
        // securityDeposit, then the platform default.
        BigDecimal depositAmount = booking.getEffectiveDepositSnapshot() != null
                ? booking.getEffectiveDepositSnapshot()
                : booking.getSecurityDeposit() != null
                        ? booking.getSecurityDeposit()
                        : BigDecimal.valueOf(defaultDepositAmountRsd);
        String ikey = PaymentIdempotencyKey.forDepositAuthorize(bookingId);

        // P0-3: Handle all settled states to avoid unique-key collision on FAILED_RETRYABLE row.
        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction ex = existing.get();
            if (ex.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                log.info("[Payment] Idempotent hit for booking {} deposit authorize", bookingId);
                return toSuccess(ex);
            }
            if (ex.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                return toLegacyResult(ex);
            }
            ex.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(ex);
        } else {
            tx = createTx(bookingId, booking.getRenter().getId(),
                    PaymentOperation.AUTHORIZE, ikey, depositAmount);
        }

        PaymentRequest request = PaymentRequest.builder()
                .bookingId(bookingId)
                .userId(booking.getRenter().getId())
                .amount(depositAmount)
                .currency(DEFAULT_CURRENCY)
                .description("Depozit za rezervaciju #" + bookingId)
                .type(PaymentType.SECURITY_DEPOSIT)
                .paymentMethodId(paymentMethodId)
                .build();

        ProviderResult result = paymentProvider.authorize(request, ikey);

        updateTx(tx, result);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            booking.setPaymentStatus("DEPOSIT_AUTHORIZED");
            transitionDeposit(booking, DepositLifecycleStatus.AUTHORIZED, "authorizeDeposit");
            booking.setDepositAuthorizationId(result.getProviderAuthorizationId());
            // M2: Use provider-supplied expiry if available; fall back to local constant.
            // Matches the pattern already used in processBookingPayment().
            booking.setDepositAuthExpiresAt(result.getExpiresAt() != null
                    ? result.getExpiresAt()
                    : Instant.now().plusSeconds(authExpiryHours * 3600L));
            bookingRepository.save(booking);
            depositAuthorizedCounter.increment();
            log.info("[Payment] Deposit authorized for booking {}: {}", bookingId, result.getProviderAuthorizationId());
        } else if (result.getOutcome() == ProviderOutcome.REDIRECT_REQUIRED) {
            booking.setPaymentStatus("DEPOSIT_REDIRECT");
            bookingRepository.save(booking);
        } else {
            log.warn("[Payment] Deposit authorization failed for booking {}: {}", bookingId, result.getErrorMessage());
        }

        return toLegacyResult(result);
    }

    /**
     * AUDIT-C2-FIX: Reauthorize an expired security deposit hold using the stored payment method.
     */
    @Transactional
    public PaymentResult reauthorizeDeposit(Long bookingId) {
        Booking booking = getBooking(bookingId);

        if (booking.getStoredPaymentMethodId() == null || booking.getStoredPaymentMethodId().isBlank()) {
            log.warn("[Payment] Deposit reauth terminal failure for booking {} - no stored payment method", bookingId);
            return PaymentResult.builder()
                    .success(false)
                    .errorCode("NO_STORED_PAYMENT_METHOD")
                    .errorMessage("no stored payment method for deposit reauth")
                    .status(PaymentStatus.FAILED)
                    .build();
        }

        BigDecimal depositAmount = booking.getEffectiveDepositSnapshot() != null
                ? booking.getEffectiveDepositSnapshot()
                : booking.getSecurityDeposit() != null
                        ? booking.getSecurityDeposit()
                        : BigDecimal.valueOf(defaultDepositAmountRsd);

        int attempt = booking.getDepositCaptureAttempts() + 1;
        String ikey = PaymentIdempotencyKey.forDepositReauth(bookingId, attempt);

        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction ex = existing.get();
            if (ex.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                return toSuccess(ex);
            }
            if (ex.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                    || ex.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION) {
                return toLegacyResult(ex);
            }
            if (ex.getStatus() == PaymentTransactionStatus.PROCESSING) {
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Deposit reauthorization already in progress")
                        .status(PaymentStatus.FAILED).build();
            }
            if (ex.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                return toLegacyResult(ex);
            }
            ex.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(ex);
        } else {
            tx = createTx(bookingId, booking.getRenter().getId(),
                    PaymentOperation.AUTHORIZE, ikey, depositAmount);
        }

        PaymentRequest request = PaymentRequest.builder()
                .bookingId(bookingId)
                .userId(booking.getRenter().getId())
                .amount(depositAmount)
                .currency(DEFAULT_CURRENCY)
                .description("Reautorizacija depozita za rezervaciju #" + bookingId)
                .type(PaymentType.SECURITY_DEPOSIT)
                .paymentMethodId(booking.getStoredPaymentMethodId())
                .build();

        ProviderResult result = paymentProvider.authorize(request, ikey);

        updateTx(tx, result);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            booking.setDepositAuthorizationId(result.getProviderAuthorizationId());
            booking.setDepositAuthExpiresAt(result.getExpiresAt() != null
                    ? result.getExpiresAt()
                    : Instant.now().plusSeconds(authExpiryHours * 3600L));
            transitionDeposit(booking, DepositLifecycleStatus.AUTHORIZED, "reauthorizeDeposit");
            bookingRepository.save(booking);
            log.info("[Payment] Deposit reauthorized for booking {}: {}", bookingId,
                    result.getProviderAuthorizationId());
        } else if (result.getOutcome() == ProviderOutcome.REDIRECT_REQUIRED) {
            log.warn("[Payment] Deposit reauth requires redirect for booking {}", bookingId);
        } else {
            log.warn("[Payment] Deposit reauth failed for booking {}: {} ({})", bookingId,
                    result.getErrorMessage(), result.getErrorCode());
        }

        return toLegacyResult(result);
    }

    /**
     * Release security deposit (no damage).
     *
     * <p>Deposit MUST NOT be released if there are pending damage claims.
     */
    @Transactional
    public PaymentResult releaseDeposit(Long bookingId, String authorizationId) {
        Booking booking = getBooking(bookingId);

        if (damageClaimRepository.hasClaimsBlockingDepositRelease(bookingId)) {
            log.warn("[Payment] Deposit release BLOCKED for booking {} - pending damage claims", bookingId);
            throw new IllegalStateException(
                "Depozit ne može biti vraćen dok postoje nerešene prijave štete. " +
                "Molimo sačekajte razrešenje svih prijava."
            );
        }

        String ikey = PaymentIdempotencyKey.forDepositRelease(bookingId);

        // P1-FIX: Handle ALL states — retryable rows must be reused, not re-inserted,
        // to avoid unique-key constraint violations on repeated release attempts.
        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction ex = existing.get();
            if (ex.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                log.info("[Payment] Idempotent hit for booking {} deposit release", bookingId);
                return toSuccess(ex);
            }
            if (ex.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                log.warn("[Payment] Terminal deposit release failure for booking {} — not retrying", bookingId);
                return toLegacyResult(ex);
            }
            if (ex.getStatus() == PaymentTransactionStatus.PROCESSING) {
                log.warn("[Payment] Deposit release in progress for booking {}", bookingId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Deposit release already in progress; wait and retry")
                        .status(PaymentStatus.FAILED).build();
            }
            // H6-FIX: Handle REDIRECT_REQUIRED / PENDING_CONFIRMATION — same pattern
            // as captureBookingPayment, captureSecurityDeposit, and other idempotency guards.
            if (ex.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                    || ex.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION) {
                log.warn("[Payment] Deposit release for booking {} awaiting async confirmation", bookingId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Deposit release awaiting async confirmation; wait and retry")
                        .status(PaymentStatus.FAILED).build();
            }
            // FAILED_RETRYABLE: reuse row to prevent unique-key violation on new insert
            ex.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(ex);
        } else {
            BigDecimal depositAmount = booking.getSecurityDeposit() != null
                    ? booking.getSecurityDeposit()
                    : BigDecimal.valueOf(defaultDepositAmountRsd);
            tx = createTx(bookingId, booking.getRenter().getId(),
                    PaymentOperation.RELEASE, ikey, depositAmount);
        }

        ProviderResult result = paymentProvider.releaseAuthorization(authorizationId, ikey);

        updateTx(tx, result);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            booking.setPaymentStatus("DEPOSIT_RELEASED");
            transitionDeposit(booking, DepositLifecycleStatus.RELEASED, "releaseDeposit");
            bookingRepository.save(booking);
            depositReleasedCounter.increment();
            log.info("[Payment] Deposit released for booking {}", bookingId);
        }

        return toLegacyResult(result);
    }

    /**
     * Capture the security deposit (convert authorization hold to actual charge).
     * Used for ghost trips and penalty scenarios.
     */
    @Transactional
    public PaymentResult captureSecurityDeposit(Long bookingId) {
        Booking booking = getBooking(bookingId);

        String depositAuthId = booking.getDepositAuthorizationId();
        if (depositAuthId == null || depositAuthId.isBlank()) {
            return PaymentResult.builder()
                    .success(false)
                    .errorMessage("No deposit authorization to capture")
                    .status(PaymentStatus.FAILED)
                    .build();
        }

        BigDecimal depositAmount = booking.getSecurityDeposit() != null
                ? booking.getSecurityDeposit()
                : BigDecimal.valueOf(defaultDepositAmountRsd);

        int attempt = booking.getDepositCaptureAttempts() + 1;
        String ikey = PaymentIdempotencyKey.forDepositCapture(bookingId, attempt);

        // P1-7: Handle all terminal idempotency states, not just SUCCEEDED.
        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction existingTx = existing.get();
            if (existingTx.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                log.info("[Payment] Idempotent hit for booking {} deposit capture attempt {}", bookingId, attempt);
                return toSuccess(existingTx);
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.PROCESSING) {
                log.warn("[Payment] Deposit capture for booking {} attempt {} already in progress", bookingId, attempt);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Deposit capture already in progress; wait and retry").status(PaymentStatus.FAILED).build();
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                log.warn("[Payment] Deposit capture for booking {} attempt {} terminally failed — not retrying", bookingId, attempt);
                return toLegacyResult(existingTx);
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                    || existingTx.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION) {
                log.warn("[Payment] Deposit capture for booking {} attempt {} awaiting async confirmation", bookingId, attempt);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Deposit capture awaiting async confirmation; wait and retry").status(PaymentStatus.FAILED).build();
            }
            // FAILED_RETRYABLE: reuse existing row to avoid unique-constraint violation.
            existingTx.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(existingTx);
        } else {
            tx = createTx(bookingId, booking.getRenter().getId(),
                    PaymentOperation.CAPTURE, ikey, depositAmount);
        }

        ProviderResult result = paymentProvider.capture(depositAuthId, depositAmount, ikey);

        updateTx(tx, result);
        booking.setDepositCaptureAttempts(attempt);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            booking.setPaymentStatus("DEPOSIT_CAPTURED");
            transitionDeposit(booking, DepositLifecycleStatus.CAPTURED, "captureSecurityDeposit");
            booking.setSecurityDepositReleased(false);
            booking.setSecurityDepositResolvedAt(Instant.now());
            bookingRepository.save(booking);
            paymentSuccessCounter.increment();
            log.info("[Payment] Security deposit {} RSD captured for booking {}: {}",
                    depositAmount, bookingId, result.getProviderTransactionId());
        } else {
            bookingRepository.save(booking);
            paymentFailedCounter.increment();
            log.warn("[Payment] Security deposit capture failed for booking {}: {}",
                    bookingId, result.getErrorMessage());
        }

        return toLegacyResult(result);
    }

    // ========== DAMAGE CHARGES ==========

    /**
     * Charge damage to guest.
     * Captures from deposit auth if available, otherwise direct charge.
     */
    @Transactional
    public PaymentResult chargeDamage(Long claimId, String authorizationIdOrPaymentMethod) {
        DamageClaim claim = damageClaimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Prijava štete nije pronađena"));

        BigDecimal amount = claim.getApprovedAmount();
        Long bookingId = claim.getBooking().getId();
        String ikey = PaymentIdempotencyKey.forDamageCharge(bookingId, claimId);

        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction existingTx = existing.get();
            if (existingTx.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                log.info("[Payment] Idempotent hit for damage claim {}", claimId);
                return toSuccess(existingTx);
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.PROCESSING) {
                log.warn("[Payment] Damage charge for claim {} already in progress", claimId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Damage charge already in progress; wait and retry").status(PaymentStatus.FAILED).build();
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                // Primary capture terminally failed — consult/execute the fallback direct charge.
                // The primary CAPTURE can fail terminally (e.g. expired auth) while the fallback
                // CHARGE may have already succeeded or be retryable. We must NOT short-circuit here.
                log.info("[Payment] Primary capture for claim {} terminally failed — consulting fallback", claimId);
                return executeDamageFallback(bookingId, claimId, claim, amount, authorizationIdOrPaymentMethod);
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                    || existingTx.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION) {
                log.warn("[Payment] Damage charge for claim {} awaiting async confirmation", claimId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Damage charge awaiting async confirmation; wait and retry").status(PaymentStatus.FAILED).build();
            }
            // FAILED_RETRYABLE: reuse existing row to avoid unique-constraint violation.
            existingTx.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(existingTx);
        } else {
            tx = createTx(bookingId, claim.getGuest().getId(),
                    PaymentOperation.CAPTURE, ikey, amount);
        }

        // Try to capture from deposit first
        ProviderResult result = paymentProvider.capture(authorizationIdOrPaymentMethod, amount, ikey);

        if (result.getOutcome() != ProviderOutcome.SUCCESS) {
            // P1-8 FIX: Record the failed capture, then delegate to the fallback direct-charge path.
            updateTx(tx, result);
            return executeDamageFallback(bookingId, claimId, claim, amount, authorizationIdOrPaymentMethod);
        }

        updateTx(tx, result);
        finalizeDamageCharge(claim, claimId, bookingId, result);
        return toLegacyResult(result);
    }

    /**
     * Execute or consult the fallback direct-charge path for damage claims.
     *
     * <p>Called when the primary deposit-capture either failed at runtime or was
     * previously recorded as terminally failed. The fallback uses a separate
     * {@code _dc}-suffixed idempotency key and its own transaction row, so it
     * has independent SUCCEEDED / PROCESSING / FAILED_TERMINAL / FAILED_RETRYABLE
     * state that must be consulted before hitting the provider.
     *
     * <p><b>R3-FIX:</b> Resolves {@code storedPaymentMethodId} from the booking
     * instead of reusing the deposit authorization ID passed by the caller.
     * Authorization IDs are hold references, not valid charge tokens — real
     * gateways reject them. See identical pattern in CheckoutSagaOrchestrator.
     */
    private PaymentResult executeDamageFallback(Long bookingId, Long claimId,
                                                DamageClaim claim, BigDecimal amount,
                                                String ignoredAuthId) {
        // R3-FIX: resolve the guest's stored payment method for the direct charge.
        // The caller passes the deposit authorization ID, which is a hold reference
        // and NOT a valid charge token. storedPaymentMethodId is the tokenized card
        // persisted at booking creation.
        String paymentMethodId = claim.getBooking().getStoredPaymentMethodId();
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            log.error("[Payment] DAMAGE FALLBACK SKIPPED for claim {} on booking {} — "
                    + "storedPaymentMethodId is missing. Cannot direct-charge without a "
                    + "valid payment instrument.", claimId, bookingId);
            return PaymentResult.builder()
                    .success(false)
                    .errorCode("NO_PAYMENT_INSTRUMENT")
                    .errorMessage("Naknada za stetu nije naplacena — nije pronadjen sacuvan nacin placanja "
                            + "za rezervaciju " + bookingId + ". Potrebno rucno resavanje.")
                    .status(PaymentStatus.FAILED)
                    .build();
        }

        String chargeKey = PaymentIdempotencyKey.forDamageCharge(bookingId, claimId) + "_dc";

        Optional<PaymentTransaction> existingDc = txRepository.findByIdempotencyKey(chargeKey);
        final PaymentTransaction tx2;
        if (existingDc.isPresent()) {
            PaymentTransaction dcTx = existingDc.get();
            if (dcTx.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                log.info("[Payment] Idempotent hit for damage fallback charge (claim {})", claimId);
                return toSuccess(dcTx);
            }
            if (dcTx.getStatus() == PaymentTransactionStatus.PROCESSING) {
                log.warn("[Payment] Damage fallback charge for claim {} already in progress", claimId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Damage fallback charge already in progress; wait and retry")
                        .status(PaymentStatus.FAILED).build();
            }
            if (dcTx.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                log.warn("[Payment] Damage fallback charge for claim {} terminally failed — not retrying", claimId);
                return toLegacyResult(dcTx);
            }
            if (dcTx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                    || dcTx.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION) {
                log.warn("[Payment] Damage fallback charge for claim {} awaiting async confirmation", claimId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Damage fallback charge awaiting async confirmation; wait and retry")
                        .status(PaymentStatus.FAILED).build();
            }
            // FAILED_RETRYABLE: reuse existing row to avoid unique-constraint violation.
            dcTx.setStatus(PaymentTransactionStatus.PROCESSING);
            tx2 = txRepository.save(dcTx);
        } else {
            tx2 = createTx(bookingId, claim.getGuest().getId(),
                    PaymentOperation.CHARGE, chargeKey, amount);
        }

        PaymentRequest req = PaymentRequest.builder()
                .bookingId(bookingId)
                .userId(claim.getGuest().getId())
                .amount(amount)
                .currency(DEFAULT_CURRENCY)
                .description("Naknada za štetu - Rezervacija #" + bookingId)
                .type(PaymentType.DAMAGE_CHARGE)
                .paymentMethodId(paymentMethodId)
                .build();
        ProviderResult result = paymentProvider.charge(req, chargeKey);
        updateTx(tx2, result);

        finalizeDamageCharge(claim, claimId, bookingId, result);
        return toLegacyResult(result);
    }

    /** Shared finalization for both primary and fallback damage charge outcomes. */
    private void finalizeDamageCharge(DamageClaim claim, Long claimId, Long bookingId, ProviderResult result) {
        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            claim.markPaid(result.getProviderTransactionId());
            damageClaimRepository.save(claim);
            paymentSuccessCounter.increment();
            log.info("[Payment] Damage charge processed for claim {}: {}", claimId, result.getProviderTransactionId());
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Damage charge failed for claim {}: {}", claimId, result.getErrorMessage());
        }
    }

    // ========== LATE FEES ==========

    @Transactional
    public PaymentResult chargeLateReturnFee(Long bookingId, String paymentMethodId) {
        Booking booking = getBooking(bookingId);

        if (booking.getLateFeeAmount() == null || booking.getLateFeeAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return PaymentResult.builder()
                    .success(true)
                    .amount(BigDecimal.ZERO)
                    .status(PaymentStatus.CAPTURED)
                    .build();
        }

        String ikey = PaymentIdempotencyKey.forLateFee(bookingId);

        // P1-7: Handle all terminal idempotency states to avoid unique-key collision.
        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction existingTx = existing.get();
            if (existingTx.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                log.info("[Payment] Idempotent hit for booking {} late fee", bookingId);
                return toSuccess(existingTx);
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.PROCESSING) {
                log.warn("[Payment] Late fee charge for booking {} already in progress", bookingId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Late fee charge already in progress; wait and retry").status(PaymentStatus.FAILED).build();
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                log.warn("[Payment] Late fee charge for booking {} terminally failed — not retrying", bookingId);
                return toLegacyResult(existingTx);
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                    || existingTx.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION) {
                log.warn("[Payment] Late fee charge for booking {} awaiting async confirmation", bookingId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Late fee charge awaiting async confirmation; wait and retry").status(PaymentStatus.FAILED).build();
            }
            // FAILED_RETRYABLE: reuse existing row to avoid unique-constraint violation.
            existingTx.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(existingTx);
        } else {
            tx = createTx(bookingId, booking.getRenter().getId(),
                    PaymentOperation.CHARGE, ikey, booking.getLateFeeAmount());
        }

        PaymentRequest request = PaymentRequest.builder()
                .bookingId(bookingId)
                .userId(booking.getRenter().getId())
                .amount(booking.getLateFeeAmount())
                .currency(DEFAULT_CURRENCY)
                .description("Naknada za kašnjenje - Rezervacija #" + bookingId)
                .type(PaymentType.LATE_FEE)
                .paymentMethodId(paymentMethodId)
                .build();

        ProviderResult result = paymentProvider.charge(request, ikey);
        updateTx(tx, result);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            paymentSuccessCounter.increment();
            log.info("[Payment] Late fee charged for booking {}: {}", bookingId, result.getProviderTransactionId());
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Late fee charge failed for booking {}: {}", bookingId, result.getErrorMessage());
        }

        return toLegacyResult(result);
    }

    // ========== EXTENSION PAYMENT ==========

    @Transactional
    public PaymentResult chargeExtension(Long extensionId, String paymentMethodId) {
        TripExtension extension = extensionRepository.findById(extensionId)
                .orElseThrow(() -> new ResourceNotFoundException("Produženje nije pronađeno"));

        Long bookingId = extension.getBooking().getId();
        String ikey = PaymentIdempotencyKey.forExtension(bookingId, extensionId);

        // P1-7: Handle all terminal idempotency states to avoid unique-key collision.
        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction existingTx = existing.get();
            if (existingTx.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                log.info("[Payment] Idempotent hit for extension {}", extensionId);
                return toSuccess(existingTx);
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.PROCESSING) {
                log.warn("[Payment] Extension charge for extension {} already in progress", extensionId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Extension charge already in progress; wait and retry").status(PaymentStatus.FAILED).build();
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                log.warn("[Payment] Extension charge for extension {} terminally failed — not retrying", extensionId);
                return toLegacyResult(existingTx);
            }
            if (existingTx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED
                    || existingTx.getStatus() == PaymentTransactionStatus.PENDING_CONFIRMATION) {
                log.warn("[Payment] Extension charge for extension {} awaiting async confirmation", extensionId);
                return PaymentResult.builder().success(false).errorCode("IN_PROGRESS")
                        .errorMessage("Extension charge awaiting async confirmation; wait and retry").status(PaymentStatus.FAILED).build();
            }
            // FAILED_RETRYABLE: reuse existing row to avoid unique-constraint violation.
            existingTx.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(existingTx);
        } else {
            tx = createTx(bookingId, extension.getBooking().getRenter().getId(),
                    PaymentOperation.CHARGE, ikey, extension.getAdditionalCost());
        }

        PaymentRequest request = PaymentRequest.builder()
                .bookingId(bookingId)
                .userId(extension.getBooking().getRenter().getId())
                .amount(extension.getAdditionalCost())
                .currency(DEFAULT_CURRENCY)
                .description("Produženje rezervacije #" + bookingId + " (" + extension.getAdditionalDays() + " dana)")
                .type(PaymentType.EXTENSION_PAYMENT)
                .paymentMethodId(paymentMethodId)
                .build();

        ProviderResult result = paymentProvider.charge(request, ikey);
        updateTx(tx, result);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            paymentSuccessCounter.increment();
            log.info("[Payment] Extension charged for booking {}: {}", bookingId, result.getProviderTransactionId());
        } else {
            paymentFailedCounter.increment();
            log.warn("[Payment] Extension charge failed for booking {}: {}", bookingId, result.getErrorMessage());
        }

        return toLegacyResult(result);
    }

    // ========== REFUNDS ==========

    /**
     * Process refund for cancellation or other reasons.
     *
     * <p>M6: Delegates to the 4-arg overload with {@code attempt = 1}.
     * Previously duplicated all refund logic with a hardcoded attempt number,
     * meaning the idempotency key never varied on retry and lifecycle guards
     * (AUTHORIZED→release, RELEASED/REFUNDED early return) were inconsistent
     * between the two overloads.
     */
    @Transactional
    public PaymentResult processRefund(Long bookingId, BigDecimal amount, String reason) {
        return processRefund(bookingId, amount, reason, 1);
    }

    /** Process refund by attempt number (for scheduler retry logic). */
    @Transactional
    public PaymentResult processRefund(Long bookingId, BigDecimal amount, String reason, int attempt) {
        Booking booking = getBooking(bookingId);

        // P0-5 FIX: Guard against calling a provider refund on an AUTHORIZED booking
        // (where paymentVerificationRef is an auth ID, not a captured txn ID).
        // Delegate to processCancellationSettlement which handles AUTHORIZED→release.
        ChargeLifecycleStatus lifecycle = booking.getChargeLifecycleStatus();
        if (lifecycle == ChargeLifecycleStatus.AUTHORIZED || lifecycle == ChargeLifecycleStatus.REAUTH_REQUIRED) {
            log.info("[Payment] Booking {} in {} state — delegating 4-arg refund to cancellation settlement",
                    bookingId, lifecycle);
            return processCancellationSettlement(bookingId, amount, reason);
        }
        if (lifecycle == ChargeLifecycleStatus.RELEASED || lifecycle == ChargeLifecycleStatus.REFUNDED) {
            log.info("[Payment] Booking {} already settled ({}) — returning early", bookingId, lifecycle);
            return PaymentResult.builder().success(true).status(PaymentStatus.SUCCESS).build();
        }

        String paymentRef = booking.getPaymentVerificationRef();
        if (paymentRef == null) {
            return PaymentResult.builder().success(false).errorMessage("Nema uplate za povraćaj")
                    .status(PaymentStatus.FAILED).build();
        }

        // M5: Cumulative refund guard — same as 3-arg overload.
        BigDecimal alreadyRefunded = txRepository.sumSucceededRefundAmounts(bookingId);
        BigDecimal maxRefundable = booking.getTotalPrice().subtract(alreadyRefunded);
        if (amount.compareTo(maxRefundable) > 0) {
            log.warn("[Payment] REJECTED: Refund {} exceeds refundable {} (captured={}, alreadyRefunded={}) for booking {} attempt {}",
                    amount, maxRefundable, booking.getTotalPrice(), alreadyRefunded, bookingId, attempt);
            return PaymentResult.builder()
                    .success(false).errorCode("REFUND_EXCEEDS_CAPTURED")
                    .errorMessage(String.format("Iznos povraćaja (%s RSD) premašuje preostali iznos (%s RSD)", amount, maxRefundable))
                    .status(PaymentStatus.FAILED).build();
        }

        String ikey = PaymentIdempotencyKey.forRefund(bookingId, reason, attempt);

        // P0-3: Handle all settled states to avoid unique-key collision on FAILED_RETRYABLE row.
        Optional<PaymentTransaction> existing = txRepository.findByIdempotencyKey(ikey);
        final PaymentTransaction tx;
        if (existing.isPresent()) {
            PaymentTransaction ex = existing.get();
            if (ex.getStatus() == PaymentTransactionStatus.SUCCEEDED) {
                return toSuccess(ex);
            }
            if (ex.getStatus() == PaymentTransactionStatus.FAILED_TERMINAL) {
                log.warn("[Payment] Terminal refund failure for booking {} attempt {} — not retrying", bookingId, attempt);
                return toLegacyResult(ex);
            }
            ex.setStatus(PaymentTransactionStatus.PROCESSING);
            tx = txRepository.save(ex);
        } else {
            tx = createTx(bookingId, booking.getRenter().getId(),
                    PaymentOperation.REFUND, ikey, amount);
        }

        ProviderResult result = paymentProvider.refund(paymentRef, amount, reason, ikey);
        updateTx(tx, result);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            booking.setPaymentStatus("REFUNDED");
            transitionCharge(booking, ChargeLifecycleStatus.REFUNDED, "processRefund.withAttempt");
            bookingRepository.save(booking);
            paymentSuccessCounter.increment();
        } else {
            paymentFailedCounter.increment();
        }

        return toLegacyResult(result);
    }

    @Transactional
    public PaymentResult processFullRefund(Long bookingId, String reason) {
        Booking booking = getBooking(bookingId);
        return processRefund(bookingId, booking.getTotalPrice(), reason);
    }

    /**
     * Process cancellation settlement — dispatches to release or refund
     * based on typed {@link ChargeLifecycleStatus}.
     */
    @Transactional
    public PaymentResult processCancellationSettlement(Long bookingId, BigDecimal refundAmount, String reason) {
        Booking booking = getBooking(bookingId);
        ChargeLifecycleStatus lifecycle = booking.getChargeLifecycleStatus();

        if (lifecycle == ChargeLifecycleStatus.RELEASED || lifecycle == ChargeLifecycleStatus.REFUNDED) {
            log.info("[Payment] Booking {} already settled ({}), skipping", bookingId, lifecycle);
            return PaymentResult.builder().success(true).status(PaymentStatus.SUCCESS).build();
        }

        if (lifecycle == ChargeLifecycleStatus.AUTHORIZED || lifecycle == ChargeLifecycleStatus.REAUTH_REQUIRED) {
            PaymentResult releaseResult = releaseBookingPayment(bookingId);
            String depositAuthId = booking.getDepositAuthorizationId();
            if (depositAuthId != null && !depositAuthId.isBlank()) {
                try { releaseDeposit(bookingId, depositAuthId); }
                catch (Exception e) { log.warn("[Payment] Failed to release deposit for {}: {}", bookingId, e.getMessage()); }
            }
            return releaseResult;
        }

        if (lifecycle == ChargeLifecycleStatus.CAPTURED) {
            if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
                return processRefund(bookingId, refundAmount, reason);
            }
            log.info("[Payment] No refund for booking {} (zero refund per policy)", bookingId);
            return PaymentResult.builder().success(true).amount(BigDecimal.ZERO).status(PaymentStatus.SUCCESS).build();
        }

        if (lifecycle == ChargeLifecycleStatus.RELEASE_FAILED || lifecycle == ChargeLifecycleStatus.CAPTURE_FAILED) {
            log.error("[Payment] Booking {} in terminal failure state {} — cannot settle via automated path. "
                    + "Escalating to manual review.", bookingId, lifecycle);
            return PaymentResult.builder()
                    .success(false)
                    .status(PaymentStatus.FAILED)
                    .errorMessage("Booking in " + lifecycle + " state — requires manual review")
                    .build();
        }

        log.warn("[Payment] Unexpected lifecycle {} for booking {} cancellation settlement", lifecycle, bookingId);
        return processRefund(bookingId, refundAmount, reason);
    }

    // ========== HOST PAYOUT (ledger-backed) ==========

    /**
     * Create a PayoutLedger entry for a completed booking.
     *
     * <p>Does NOT immediately trigger a provider transfer — the payout scheduler
     * will pick up ELIGIBLE entries after the dispute window closes and call
     * {@link #executeHostPayout(Long)} on each row.
     *
     * <p>Idempotent: calling twice for the same booking is a no-op.
     */
    @Transactional
    public PayoutLedger scheduleHostPayout(Booking booking) {
        String ikey = PaymentIdempotencyKey.forPayout(booking.getId(),
                booking.getCar().getOwner().getId(), 1);

        return payoutLedgerRepository.findByIdempotencyKey(ikey).orElseGet(() -> {
            BigDecimal trip = booking.getTotalAmount();
            BigDecimal fee = trip.multiply(platformFeeRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal pdv = fee.multiply(pdvRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal hostAmt = trip.subtract(fee);

            PayoutLedger ledger = PayoutLedger.builder()
                    .bookingId(booking.getId())
                    .hostUserId(booking.getCar().getOwner().getId())
                    .tripAmount(trip)
                    .platformFeeRate(platformFeeRate)
                    .platformFee(fee)
                    .platformFeePdv(pdv)
                    .hostPayoutAmount(hostAmt)
                    .currency(DEFAULT_CURRENCY)
                    .idempotencyKey(ikey)
                    .status(PayoutLifecycleStatus.PENDING)
                    .eligibleAt(Instant.now().plusSeconds(payoutDisputeHoldHours * 3600L))
                    .build();

            // Phase 3: Calculate tax withholding before persisting
            User owner = booking.getCar().getOwner();
            taxWithholdingService.calculateWithholding(ledger, owner);

            log.info("[Payment] Payout scheduled for booking {} → host {}: gross={} RSD, tax={}, net={} (fee {} + PDV {})",
                    booking.getId(), owner.getId(), ledger.getGrossOwnerIncome(),
                    ledger.getIncomeTaxWithheld(), ledger.getNetOwnerPayout(), fee, pdv);
            return payoutLedgerRepository.save(ledger);
        });
    }

    /**
     * Execute the actual provider transfer for a ledger row.
     * Called by the payout scheduler after the dispute window.
     *
     * <p>Idempotent via the ledger's own idempotency key.
     */
    @Transactional
    public PaymentResult executeHostPayout(Long payoutLedgerId) {
        PayoutLedger ledger = payoutLedgerRepository.findById(payoutLedgerId)
                .orElseThrow(() -> new ResourceNotFoundException("Payout ledger not found: " + payoutLedgerId));

        if (ledger.getStatus() == PayoutLifecycleStatus.COMPLETED) {
            log.info("[Payment] Payout {} already completed — idempotent return", payoutLedgerId);
            return PaymentResult.builder().success(true).status(PaymentStatus.SUCCESS)
                    .amount(ledger.getHostPayoutAmount()).build();
        }

        if (ledger.isOnHold()) {
            return PaymentResult.builder().success(false)
                    .errorMessage("Payout on hold: " + ledger.getHoldReason())
                    .status(PaymentStatus.FAILED).build();
        }

        // P0-4: Use stable currentAttemptKey for crash-recovery replay.
        // If the ledger already has a key it means we crashed mid-attempt — reuse it
        // so the provider returns the cached result rather than issuing a duplicate transfer.
        final String attemptKey;
        if (ledger.getCurrentAttemptKey() != null && !ledger.getCurrentAttemptKey().isBlank()) {
            // Crash recovery: reuse the key established for this attempt.
            attemptKey = ledger.getCurrentAttemptKey();
            // P1-FIX: Ensure status is PROCESSING before calling the provider.
            // The crash-recovery path must mirror the new-attempt path's status transition.
            // Without this, if a requeued payout still has a stale currentAttemptKey,
            // the provider call would execute while status remains ELIGIBLE, and a PENDING
            // result would leave the payout in a state where webhooks can't finalize it
            // (webhook handler checks status == PROCESSING).
            if (ledger.getStatus() != PayoutLifecycleStatus.PROCESSING) {
                ledger.setStatus(PayoutLifecycleStatus.PROCESSING);
                payoutLedgerRepository.save(ledger);
            }
            log.info("[Payment] Recovering payout attempt for ledger {} with stable key {} (attempt {})",
                    payoutLedgerId, attemptKey, ledger.getAttemptCount());
        } else {
            // New attempt: generate key, increment counter, persist both before calling provider.
            int nextAttempt = ledger.getAttemptCount() + 1;
            attemptKey = PaymentIdempotencyKey.forPayout(
                    ledger.getBookingId(), ledger.getHostUserId(), nextAttempt);
            ledger.setAttemptCount(nextAttempt);
            ledger.setStatus(PayoutLifecycleStatus.PROCESSING);
            ledger.setCurrentAttemptKey(attemptKey);
            payoutLedgerRepository.save(ledger);
        }

        // C3: Look up host's Monri recipient ID for payout routing.
        // If absent, the provider will return TERMINAL_FAILURE preventing silent misrouting.
        String recipientId = userRepository.findById(ledger.getHostUserId())
                .map(User::getMonriRecipientId)
                .orElse(null);

        // Phase 3: Transfer the net amount (after tax withholding) to the host.
        // Falls back to hostPayoutAmount for legacy rows without withholding data.
        BigDecimal transferAmount = ledger.getNetOwnerPayout() != null
                ? ledger.getNetOwnerPayout()
                : ledger.getHostPayoutAmount();

        PaymentRequest request = PaymentRequest.builder()
                .bookingId(ledger.getBookingId())
                .userId(ledger.getHostUserId())
                .amount(transferAmount)
                .currency(DEFAULT_CURRENCY)
                .description("Isplata domaćinu za rezervaciju #" + ledger.getBookingId())
                .type(PaymentType.PAYOUT)
                .recipientId(recipientId)
                .build();

        ProviderResult result = paymentProvider.payout(request, attemptKey);

        if (result.getOutcome() == ProviderOutcome.SUCCESS) {
            ledger.setStatus(PayoutLifecycleStatus.COMPLETED);
            ledger.setProviderReference(result.getProviderTransactionId());
            ledger.setPaidAt(Instant.now());
            ledger.setCurrentAttemptKey(null);  // P0-4: clear after terminal success
            paymentSuccessCounter.increment();
            // P0-5: Only set paymentReference on the booking after money has actually moved.
            // This is the sole place where paymentReference is set; processHostPayout
            // must NOT set it prematurely.
            bookingRepository.findById(ledger.getBookingId()).ifPresent(b -> {
                b.setPaymentReference(result.getProviderTransactionId());
                bookingRepository.save(b);
            });
            log.info("[Payment] Host payout completed for booking {}: {} RSD (ref {})",
                    ledger.getBookingId(), ledger.getHostPayoutAmount(), result.getProviderTransactionId());
        } else if (result.getOutcome() == ProviderOutcome.PENDING) {
            // P0-FIX: Async bank transfer accepted by provider — keep PROCESSING.
            // The provider will send a PAYOUT.COMPLETED or PAYOUT.FAILED webhook
            // to finalize the payout. Do NOT reset to ELIGIBLE or clear the attempt key,
            // otherwise the webhook handler will miss the PROCESSING status check and
            // the scheduler will re-fire a duplicate payout.
            ledger.setProviderReference(result.getProviderTransactionId());
            // Keep status as PROCESSING, keep currentAttemptKey for crash safety
            log.info("[Payment] Host payout PENDING (async) for booking {}: {} RSD (provider ref {}). "
                    + "Awaiting PAYOUT.COMPLETED webhook.",
                    ledger.getBookingId(), ledger.getHostPayoutAmount(), result.getProviderTransactionId());
        } else {
            boolean terminal = result.getOutcome() == ProviderOutcome.TERMINAL_FAILURE
                    || ledger.getAttemptCount() >= ledger.getMaxAttempts();
            if (terminal) {
                ledger.setStatus(PayoutLifecycleStatus.MANUAL_REVIEW);
                ledger.setCurrentAttemptKey(null);  // P0-4: clear so next run starts fresh key
                payoutManualReviewCounter.increment();
                log.error("[ALERT][MANUAL_REVIEW] Host payout ESCALATED to MANUAL_REVIEW for booking {} "
                        + "after {} attempts: {}. Runbook: https://wiki.internal/runbooks/payout-manual-review",
                        ledger.getBookingId(), ledger.getAttemptCount(), result.getErrorMessage());
            } else {
                // P0-5: Retryable failure — clear currentAttemptKey so the next run generates
                // a FRESH key and increments the attempt counter. Keeping the stale key would
                // replay the same cached RETRYABLE result forever, creating an infinite loop.
                ledger.setStatus(PayoutLifecycleStatus.ELIGIBLE);
                ledger.setNextRetryAt(Instant.now().plusSeconds(3600));
                ledger.setCurrentAttemptKey(null);  // P0-5: force new key on next attempt
            }
            ledger.setLastError(result.getErrorMessage());
            paymentFailedCounter.increment();
            log.warn("[Payment] Host payout failed for booking {} (attempt {}/{}): {}",
                    ledger.getBookingId(), ledger.getAttemptCount(), ledger.getMaxAttempts(),
                    result.getErrorMessage());
        }

        payoutLedgerRepository.save(ledger);
        return toLegacyResult(result);
    }

    /**
     * Legacy adapter: processHostPayout — schedules via ledger and marks booking.
     * Callers expecting the old fire-and-forget behaviour are redirected here.
     */
    @Transactional
    public PaymentResult processHostPayout(Booking booking, String batchReference) {
        PayoutLedger ledger = scheduleHostPayout(booking);
        // P0-5: Do NOT set paymentReference here \u2014 money has not moved yet.
        // paymentReference is set by executeHostPayout only when the provider confirms
        // a successful transfer (COMPLETED state). Setting it prematurely creates a false
        // accounting signal and blocks legitimate payout retries.
        log.info("[Payment] Payout ledger {} queued for booking {} (batch ref {}). " +
                "Actual transfer deferred to executeHostPayout.",
                ledger.getId(), booking.getId(), batchReference);
        return PaymentResult.builder()
                .success(true)
                .transactionId("LEDGER_" + ledger.getId())
                .amount(ledger.getHostPayoutAmount())
                .currency(DEFAULT_CURRENCY)
                .status(PaymentStatus.SUCCESS)
                .build();
    }

    // ========== DISPUTE PAYMENT ==========

    /**
     * Process approved damage claim charge via the payment provider.
     *
     * <p><b>R4-FIX:</b> Handles the PREMIUM (no-deposit) case. When no deposit
     * authorization exists, the method skips the capture path entirely and goes
     * directly to a stored-payment-method charge via {@link #executeDamageFallback}.
     * This is the expected flow for PREMIUM tier where depositMultiplier = 0.
     */
    @Transactional
    public PaymentResult processDisputePayment(DamageClaim claim) {
        Booking booking = claim.getBooking();
        String depositAuthId = booking.getDepositAuthorizationId();

        // R4-FIX: Ako nema deposit autorizacije (PREMIUM tier, depozit = 0),
        // preskoci capture i idi direktno na naplatu preko sacuvanog nacina placanja.
        if (depositAuthId == null || depositAuthId.isBlank()) {
            log.info("[Payment] No deposit authorization for claim {} on booking {} — "
                    + "routing directly to stored-payment-method charge (expected for zero-deposit tiers)",
                    claim.getId(), booking.getId());
            return executeDamageFallback(
                    booking.getId(),
                    claim.getId(),
                    claim,
                    claim.getApprovedAmount(),
                    null);
        }
        return chargeDamage(claim.getId(), depositAuthId);
    }

    // ========== HELPERS ==========

    private Booking getBooking(Long bookingId) {
        return bookingRepository.findByIdWithRelations(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));
    }

    private PaymentTransaction createTx(Long bookingId, Long userId,
                                         PaymentOperation operation, String ikey, BigDecimal amount) {
        PaymentTransaction tx = PaymentTransaction.builder()
                .bookingId(bookingId)
                .userId(userId)
                .operation(operation)
                .idempotencyKey(ikey)
                .amount(amount)
                .currency(DEFAULT_CURRENCY)
                .status(PaymentTransactionStatus.PROCESSING)
                .build();
        return txRepository.save(tx);
    }

    private void updateTx(PaymentTransaction tx, ProviderResult result) {
        switch (result.getOutcome()) {
            case SUCCESS -> {
                tx.setStatus(PaymentTransactionStatus.SUCCEEDED);
                tx.setProviderReference(result.getProviderTransactionId());
                tx.setProviderAuthId(result.getProviderAuthorizationId());
            }
            case REDIRECT_REQUIRED -> {
                tx.setStatus(PaymentTransactionStatus.REDIRECT_REQUIRED);
                tx.setRedirectUrl(result.getRedirectUrl());
                tx.setSessionToken(result.getSessionToken());
                tx.setProviderAuthId(result.getProviderAuthorizationId());
            }
            case PENDING -> {
                tx.setStatus(PaymentTransactionStatus.PENDING_CONFIRMATION);
                tx.setProviderAuthId(result.getProviderAuthorizationId());
            }
            case RETRYABLE_FAILURE -> {
                tx.setStatus(PaymentTransactionStatus.FAILED_RETRYABLE);
                tx.setErrorCode(result.getErrorCode());
            }
            case TERMINAL_FAILURE -> {
                tx.setStatus(PaymentTransactionStatus.FAILED_TERMINAL);
                tx.setErrorCode(result.getErrorCode());
            }
        }
        txRepository.save(tx);
    }

    /** Adapt a ProviderResult to the legacy PaymentResult for backward compat. */
    private PaymentResult toLegacyResult(ProviderResult r) {
        boolean success = r.getOutcome() == ProviderOutcome.SUCCESS;
        PaymentStatus status = switch (r.getOutcome()) {
            case SUCCESS -> PaymentStatus.SUCCESS;
            case REDIRECT_REQUIRED -> PaymentStatus.REDIRECT_REQUIRED;
            case PENDING -> PaymentStatus.PENDING;
            default -> PaymentStatus.FAILED;
        };
        return PaymentResult.builder()
                .success(success)
                .transactionId(r.getProviderTransactionId())
                .authorizationId(r.getProviderAuthorizationId())
                .amount(r.getAmount())
                .currency(r.getCurrency())
                .redirectUrl(r.getRedirectUrl())
                .errorCode(r.getErrorCode())
                .errorMessage(r.getErrorMessage())
            .status(status)
                .build();
    }

    private PaymentResult toLegacyResult(PaymentTransaction tx) {
        boolean success = tx.getStatus() == PaymentTransactionStatus.SUCCEEDED;
        PaymentStatus status = mapPaymentStatus(tx.getStatus());
        return PaymentResult.builder()
                .success(success)
                .transactionId(tx.getProviderReference())
                .authorizationId(tx.getProviderAuthId())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .redirectUrl(tx.getRedirectUrl())
                .status(status)
                .build();
    }

    private PaymentStatus mapPaymentStatus(PaymentTransactionStatus status) {
        return switch (status) {
            case SUCCEEDED -> PaymentStatus.SUCCESS;
            case REDIRECT_REQUIRED -> PaymentStatus.REDIRECT_REQUIRED;
            case PENDING_CONFIRMATION, PROCESSING -> PaymentStatus.PENDING;
            default -> PaymentStatus.FAILED;
        };
    }

    private PaymentResult toSuccess(PaymentTransaction tx) {
        return PaymentResult.builder()
                .success(true)
                .transactionId(tx.getProviderReference())
                .authorizationId(tx.getProviderAuthId())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .status(PaymentStatus.SUCCESS)
                .build();
    }

    /**
     * Resolve the pending 3DS redirect URL for a booking, if one exists.
     *
     * <p>Called by {@code BookingService} during idempotent replay of SCA bookings to
     * reconstruct the {@code BookingCreationResult.redirect()} envelope so the client
     * receives a consistent response shape on retry.
     *
     * @param bookingId booking whose authorize transaction to inspect
     * @return the redirect URL if the AUTHORIZE transaction is still in REDIRECT_REQUIRED state,
     *         empty otherwise (authorization already confirmed or not found)
     */
    @Transactional(readOnly = true)
    public java.util.Optional<String> findPendingRedirectUrl(Long bookingId) {
        String ikey = PaymentIdempotencyKey.forAuthorize(bookingId);
        return txRepository.findByIdempotencyKey(ikey)
                .filter(tx -> tx.getStatus() == PaymentTransactionStatus.REDIRECT_REQUIRED)
                .map(PaymentTransaction::getRedirectUrl);
    }

    /** Snapshot of extension payment action state for UI continuation and reconciliation. */
    public record ExtensionPaymentAction(
            boolean success,
            PaymentStatus status,
            String redirectUrl,
            String actionToken,
            String transactionId,
            String authorizationId
    ) {
        public boolean isPending() {
            return status == PaymentStatus.REDIRECT_REQUIRED || status == PaymentStatus.PENDING;
        }
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ExtensionPaymentAction> findExtensionPaymentAction(Long bookingId, Long extensionId) {
        String ikey = PaymentIdempotencyKey.forExtension(bookingId, extensionId);
        return txRepository.findByIdempotencyKey(ikey)
                .map(tx -> new ExtensionPaymentAction(
                        tx.getStatus() == PaymentTransactionStatus.SUCCEEDED,
                        mapPaymentStatus(tx.getStatus()),
                        tx.getRedirectUrl(),
                        tx.getSessionToken(),
                        tx.getProviderReference(),
                        tx.getProviderAuthId()
                ));
    }

    // ========== LIFECYCLE TRANSITION GUARDS ==========

    /**
     * Transition charge lifecycle status with strict state-machine enforcement.
     *
     * <p><b>H-10 FIX:</b> Previously used soft guards (log + proceed). Now uses the
     * throwing {@link ChargeLifecycleStatus#transition(ChargeLifecycleStatus)} method
     * which rejects invalid transitions with {@link IllegalStateException}. Null current
     * state (initial booking) is allowed — transitions from null to any state are valid.
     *
     * @throws IllegalStateException if the transition violates the state machine
     */
    private void transitionCharge(Booking booking, ChargeLifecycleStatus target, String context) {
        ChargeLifecycleStatus current = booking.getChargeLifecycleStatus();
        if (current != null) {
            current.transition(target);  // H-10: throws IllegalStateException on invalid transition
        }
        booking.setChargeLifecycleStatus(target);
        log.debug("[Payment] Charge lifecycle {} → {} in {}, booking {}",
                current, target, context, booking.getId());
    }

    /**
     * Transition deposit lifecycle status with strict state-machine enforcement.
     *
     * <p><b>H-10 FIX:</b> Uses throwing {@link DepositLifecycleStatus#transition(DepositLifecycleStatus)}.
     *
     * @throws IllegalStateException if the transition violates the state machine
     */
    private void transitionDeposit(Booking booking, DepositLifecycleStatus target, String context) {
        DepositLifecycleStatus current = booking.getDepositLifecycleStatus();
        if (current != null) {
            current.transition(target);  // H-10: throws IllegalStateException on invalid transition
        }
        booking.setDepositLifecycleStatus(target);
        log.debug("[Payment] Deposit lifecycle {} → {} in {}, booking {}",
                current, target, context, booking.getId());
    }
}
