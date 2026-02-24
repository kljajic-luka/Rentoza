package org.example.rentoza.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Mock payment provider for development and staging testing.
 *
 * <p>Implements the full {@link PaymentProvider} contract including Monri-style
 * redirect-based 3DS2, authorization expiry, idempotency, and strict unknown-ID rejection.
 *
 * <h2>Test Card Tokens (via {@code paymentMethodId})</h2>
 * <ul>
 *   <li>{@code pm_card_visa} / any default — succeeds</li>
 *   <li>{@code pm_card_declined} — TERMINAL_FAILURE (card declined)</li>
 *   <li>{@code pm_card_insufficient} — TERMINAL_FAILURE (insufficient funds)</li>
 *   <li>{@code pm_card_expired} — TERMINAL_FAILURE (card expired)</li>
 *   <li>{@code pm_card_fraud} — TERMINAL_FAILURE (fraud suspected)</li>
 *   <li>{@code pm_card_sca_required} — REDIRECT_REQUIRED (3DS2 — must not mark FAILED)</li>
 *   <li>{@code pm_card_processing_error} — RETRYABLE_FAILURE (transient)</li>
 *   <li>{@code pm_card_async} — PENDING (async confirmation path)</li>
 * </ul>
 *
 * <h2>Strict Unknown-ID Behavior</h2>
 * <p>Capture, refund, and release on IDs not created by this provider instance
 * return a TERMINAL_FAILURE. This exposes integration bugs before Monri credentials.
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code app.payment.mock.force-failure=true} — all operations fail</li>
 *   <li>{@code app.payment.mock.failure-rate=0.1} — 10% random RETRYABLE_FAILURE rate</li>
 *   <li>{@code app.payment.mock.failure-code=CARD_DECLINED}</li>
 *   <li>{@code app.payment.mock.simulate-timeout=true}</li>
 *   <li>{@code app.payment.mock.delay-ms=200}</li>
 *   <li>{@code app.payment.mock.auth-expiry-minutes=7200} (default 5 days = 7200 min)</li>
 * </ul>
 *
 * <p><b>NEVER USE IN PRODUCTION.</b>
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "MOCK")
public class MockPaymentProvider implements PaymentProvider {

    private final Random random = new Random();

    // ── Lifecycle tracking ────────────────────────────────────────────────────

    /** Authorization state: authId → MockAuthorization */
    private final Map<String, MockAuthorization> authorizations = new ConcurrentHashMap<>();

    /** Captured transactions: providerTransactionId → captured amount */
    private final Map<String, BigDecimal> capturedTransactions = new ConcurrentHashMap<>();

    /** Running refunded amount per transaction: txnId → total refunded so far */
    private final Map<String, BigDecimal> refundedAmounts = new ConcurrentHashMap<>();

    /**
     * Per-transaction refund lock objects.
     * P1-FIX: Prevents the check-then-update TOCTOU race where two concurrent refunds
     * with different idempotency keys both observe the same {@code alreadyRefunded} value
     * and together over-refund the captured amount.
     */
    private final Map<String, Object> txRefundLocks = new ConcurrentHashMap<>();

    /** Idempotency store: idempotencyKey → ProviderResult */
    private final Map<String, ProviderResult> idempotencyStore = new ConcurrentHashMap<>();

    // ── Test card sets ─────────────────────────────────────────────────────

    private static final Set<String> DECLINED_CARDS      = Set.of("pm_card_declined",        "4000000000000002");
    private static final Set<String> INSUFFICIENT_CARDS  = Set.of("pm_card_insufficient",    "4000000000009995");
    private static final Set<String> EXPIRED_CARDS       = Set.of("pm_card_expired",         "4000000000000069");
    private static final Set<String> FRAUD_CARDS         = Set.of("pm_card_fraud",           "4000000000000127");
    private static final Set<String> SCA_REQUIRED_CARDS  = Set.of("pm_card_sca_required",    "4000002500003155");
    private static final Set<String> PROCESSING_ERR_CARDS = Set.of("pm_card_processing_error","4000000000000119");
    private static final Set<String> ASYNC_CARDS         = Set.of("pm_card_async",           "4000002400000000");

    // ── Configuration ──────────────────────────────────────────────────────

    @Value("${app.payment.mock.force-failure:false}")
    private boolean forceFailure;

    @Value("${app.payment.mock.failure-rate:0.0}")
    private double failureRate;

    @Value("${app.payment.mock.failure-code:CARD_DECLINED}")
    private String failureCode;

    @Value("${app.payment.mock.simulate-timeout:false}")
    private boolean simulateTimeout;

    @Value("${app.payment.mock.delay-ms:200}")
    private int delayMs;

    /** Default authorization validity window (Monri holds expire in ~5 days). */
    @Value("${app.payment.mock.auth-expiry-minutes:7200}")
    private int authExpiryMinutes = 7200;

    // =========================================================================
    // PaymentProvider implementation
    // =========================================================================

    @Override
    public String getProviderName() {
        return "MOCK";
    }

    // ── authorize ─────────────────────────────────────────────────────────────

    @Override
    public ProviderResult authorize(PaymentRequest request, String idempotencyKey) {
        log.info("[Mock] authorize booking={} amount={} {} method={} idk={}",
                request.getBookingId(), request.getAmount(), request.getCurrency(),
                request.getPaymentMethodId(), idempotencyKey);

        return withIdempotency(idempotencyKey, () -> {
            simulateDelay();

            ProviderResult cardResult = checkTestCard(request.getPaymentMethodId(), request.getAmount());
            if (cardResult != null) return cardResult;
            if (shouldFail()) return retryableOrTerminal(request.getAmount());

            String authId = "mock_auth_" + shortUuid();
            Instant expiresAt = Instant.now().plus(Duration.ofMinutes(authExpiryMinutes));
            authorizations.put(authId, new MockAuthorization(
                    authId, request.getAmount(), request.getCurrency(),
                    MockAuthorizationStatus.AUTHORIZED, request.getBookingId(), expiresAt));

            ProviderResult result = ProviderResult.authSuccess(authId, request.getAmount(), expiresAt);
            log.info("[Mock] authorize SUCCESS authId={} expires={}", authId, expiresAt);
            return result;
        });
    }

    // ── capture ───────────────────────────────────────────────────────────────

    @Override
    public ProviderResult capture(String authorizationId, BigDecimal amount, String idempotencyKey) {
        log.info("[Mock] capture authId={} amount={} idk={}", authorizationId, amount, idempotencyKey);

        return withIdempotency(idempotencyKey, () -> {
            simulateDelay();

            // P1-11: Reject zero/negative amounts before any state inspection.
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                return ProviderResult.terminalFailure("INVALID_AMOUNT",
                        "Capture amount must be positive: " + amount);
            }

            // ── STRICT: reject unknown authorization IDs ──
            MockAuthorization auth = authorizations.get(authorizationId);
            if (auth == null) {
                log.error("[Mock] REJECTED capture: unknown authorizationId={}", authorizationId);
                return ProviderResult.terminalFailure("UNKNOWN_AUTHORIZATION",
                        "Authorization ID not found: " + authorizationId);
            }

            // P1-FIX: Move ALL state inspections INSIDE the synchronized block so that
            // concurrent capture calls cannot both observe AUTHORIZED status and proceed.
            synchronized (auth) {
                // ── Expiry check (re-evaluated under lock) ──
                if (auth.expiresAt != null && Instant.now().isAfter(auth.expiresAt)) {
                    log.warn("[Mock] REJECTED capture: authorization {} expired at {}", authorizationId, auth.expiresAt);
                    return ProviderResult.terminalFailure("AUTHORIZATION_EXPIRED",
                            "Authorization " + authorizationId + " has expired");
                }

                if (auth.status == MockAuthorizationStatus.CAPTURED) {
                    log.warn("[Mock] REJECTED capture: already captured authId={}", authorizationId);
                    return ProviderResult.terminalFailure("ALREADY_CAPTURED",
                            "Authorization already captured — rejected to prevent double charge");
                }
                if (auth.status == MockAuthorizationStatus.RELEASED) {
                    log.warn("[Mock] REJECTED capture: authorization released authId={}", authorizationId);
                    return ProviderResult.terminalFailure("AUTH_RELEASED",
                            "Cannot capture a released authorization");
                }
                if (amount.compareTo(auth.amount) > 0) {
                    log.warn("[Mock] REJECTED capture: amount {} > authorized {} authId={}", amount, auth.amount, authorizationId);
                    return ProviderResult.terminalFailure("AMOUNT_EXCEEDS_AUTH",
                            "Capture amount " + amount + " exceeds authorized " + auth.amount);
                }

                if (shouldFail()) {
                    return ProviderResult.retryableFailure(failureCode, "Capture failed: " + getFailureMessage());
                }

                String txnId = "mock_txn_" + shortUuid();
                auth.status = MockAuthorizationStatus.CAPTURED;
                auth.capturedTransactionId = txnId;
                capturedTransactions.put(txnId, amount);

                ProviderResult result = ProviderResult.captureSuccess(txnId, amount);
                log.info("[Mock] capture SUCCESS txnId={} authId={}", txnId, authorizationId);
                return result;
            }
        });
    }

    // ── charge ────────────────────────────────────────────────────────────────

    @Override
    public ProviderResult charge(PaymentRequest request, String idempotencyKey) {
        log.info("[Mock] charge booking={} amount={} method={} idk={}",
                request.getBookingId(), request.getAmount(), request.getPaymentMethodId(), idempotencyKey);

        return withIdempotency(idempotencyKey, () -> {
            simulateDelay();

            ProviderResult cardResult = checkTestCard(request.getPaymentMethodId(), request.getAmount());
            if (cardResult != null) return cardResult;
            if (shouldFail()) return retryableOrTerminal(request.getAmount());

            String txnId = "mock_txn_" + shortUuid();
            capturedTransactions.put(txnId, request.getAmount());

            ProviderResult result = ProviderResult.captureSuccess(txnId, request.getAmount());
            log.info("[Mock] charge SUCCESS txnId={}", txnId);
            return result;
        });
    }

    // ── refund ────────────────────────────────────────────────────────────────

    @Override
    public ProviderResult refund(String providerTransactionId, BigDecimal amount,
                                 String reason, String idempotencyKey) {
        log.info("[Mock] refund txnId={} amount={} reason={} idk={}",
                providerTransactionId, amount, reason, idempotencyKey);

        return withIdempotency(idempotencyKey, () -> {
            simulateDelay();

            // P1-11: Reject zero/negative refund amounts early.
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                return ProviderResult.terminalFailure("INVALID_AMOUNT",
                        "Refund amount must be positive: " + amount);
            }

            // ── STRICT: reject unknown transaction IDs ──
            BigDecimal capturedAmount = capturedTransactions.get(providerTransactionId);
            if (capturedAmount == null) {
                log.error("[Mock] REJECTED refund: unknown providerTransactionId={}", providerTransactionId);
                return ProviderResult.terminalFailure("UNKNOWN_TRANSACTION",
                        "Transaction ID not found: " + providerTransactionId);
            }

            // P1-FIX: Serialize the check-and-update on a per-transaction lock to prevent
            // the TOCTOU race where two concurrent refunds (with different idempotency keys)
            // both observe the same alreadyRefunded value and together over-refund the amount.
            Object txLock = txRefundLocks.computeIfAbsent(providerTransactionId, id -> new Object());
            synchronized (txLock) {
                BigDecimal alreadyRefunded = refundedAmounts.getOrDefault(providerTransactionId, BigDecimal.ZERO);
                BigDecimal maxRefundable = capturedAmount.subtract(alreadyRefunded);
                if (amount.compareTo(maxRefundable) > 0) {
                    log.warn("[Mock] REJECTED refund: {} > refundable {} (captured={}, refunded={})",
                            amount, maxRefundable, capturedAmount, alreadyRefunded);
                    return ProviderResult.terminalFailure("REFUND_EXCEEDS_CAPTURED",
                            "Refund " + amount + " exceeds max refundable " + maxRefundable);
                }

                if (shouldFail()) {
                    return ProviderResult.retryableFailure(failureCode, "Refund failed: " + getFailureMessage());
                }

                String refundId = "mock_ref_" + shortUuid();
                refundedAmounts.merge(providerTransactionId, amount, BigDecimal::add);

                ProviderResult result = ProviderResult.refundSuccess(refundId, amount);
                log.info("[Mock] refund SUCCESS refundId={} txnId={} amount={}", refundId, providerTransactionId, amount);
                return result;
            }
        });
    }

    // ── releaseAuthorization ──────────────────────────────────────────────────

    @Override
    public ProviderResult releaseAuthorization(String authorizationId, String idempotencyKey) {
        log.info("[Mock] release authId={} idk={}", authorizationId, idempotencyKey);

        return withIdempotency(idempotencyKey, () -> {
            simulateDelay();

            // ── STRICT: reject unknown authorization IDs ──
            MockAuthorization auth = authorizations.get(authorizationId);
            if (auth == null) {
                log.error("[Mock] REJECTED release: unknown authorizationId={}", authorizationId);
                return ProviderResult.terminalFailure("UNKNOWN_AUTHORIZATION",
                        "Authorization ID not found: " + authorizationId);
            }

            // P1-3 FIX: Hold the same lock used by capture so that release and capture
            // cannot interleave — prevents TOCTOU where concurrent capture+release both
            // observe non-CAPTURED/non-RELEASED status and proceed.
            synchronized (auth) {
                if (auth.status == MockAuthorizationStatus.CAPTURED) {
                    log.warn("[Mock] REJECTED release: already captured authId={}", authorizationId);
                    return ProviderResult.terminalFailure("ALREADY_CAPTURED",
                            "Cannot release a captured authorization — use refund instead");
                }

                // Idempotent: already released
                if (auth.status == MockAuthorizationStatus.RELEASED) {
                    log.info("[Mock] release already released (idempotent) authId={}", authorizationId);
                    return ProviderResult.releaseSuccess(authorizationId);
                }

                if (shouldFail()) {
                    return ProviderResult.retryableFailure(failureCode, "Release failed: " + getFailureMessage());
                }

                auth.status = MockAuthorizationStatus.RELEASED;
                ProviderResult result = ProviderResult.releaseSuccess(authorizationId);
                log.info("[Mock] release SUCCESS authId={}", authorizationId);
                return result;
            }
        });
    }

    // =========================================================================
    // Payout
    // =========================================================================

    @Override
    public ProviderResult payout(PaymentRequest request, String idempotencyKey) {
        return withIdempotency(idempotencyKey, () -> {
            simulateDelay();

            if (shouldFail()) {
                ProviderResult failure = retryableOrTerminal(request.getAmount());
                log.warn("[Mock] Payout FAILED for host={} booking={}: {}",
                        request.getUserId(), request.getBookingId(), failure.getErrorCode());
                return failure;
            }

            String txnId = "mock_payout_" + shortUuid();
            ProviderResult result = ProviderResult.captureSuccess(txnId, request.getAmount());
            log.info("[Mock] Payout SUCCESS host={} booking={} txn={} amount={}",
                    request.getUserId(), request.getBookingId(), txnId, request.getAmount());
            return result;
        });
    }

    // =========================================================================
    // Test-only helpers (called from tests to inject state / simulate async)
    // =========================================================================

    /**
     * Simulate expiring an authorization (for testing reauth path).
     * TEST USE ONLY.
     */
    public void expireAuthorization(String authorizationId) {
        MockAuthorization auth = authorizations.get(authorizationId);
        if (auth != null) {
            auth.expiresAt = Instant.now().minusSeconds(1);
            log.info("[Mock] TEST: authorization {} explicitly expired", authorizationId);
        }
    }

    /**
     * Check whether an authorization is tracked by this provider instance.
     * TEST USE ONLY.
     */
    /**
     * Returns true if the authorization exists AND is still active (not released or expired).
     * Released or expired authorizations are treated as absent for test assertion purposes.
     */
    public boolean hasAuthorization(String authorizationId) {
        MockAuthorization auth = authorizations.get(authorizationId);
        return auth != null && auth.status != MockAuthorizationStatus.RELEASED;
    }

    /**
     * Inject a captured transaction (for testing refund paths).
     * TEST USE ONLY.
     */
    public void injectCapturedTransaction(String txnId, BigDecimal amount) {
        capturedTransactions.put(txnId, amount);
    }

    /**
     * Return whether an authorization is expired per this provider's clock.
     * TEST USE ONLY.
     */
    public boolean isAuthorizationExpired(String authorizationId) {
        MockAuthorization auth = authorizations.get(authorizationId);
        if (auth == null) return false;
        return auth.expiresAt != null && Instant.now().isAfter(auth.expiresAt);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Check the payment method ID against known test-card tokens.
     * Returns a failure/redirect result if the card triggers a specific scenario,
     * or {@code null} if the card should succeed normally.
     *
     * <p>3DS2 / SCA cards return REDIRECT_REQUIRED (not FAILED) so callers
     * can initiate the redirect flow without prematurely marking the payment failed.
     */
    private ProviderResult checkTestCard(String paymentMethodId, BigDecimal amount) {
        if (paymentMethodId == null) return null;

        if (DECLINED_CARDS.contains(paymentMethodId)) {
            log.warn("[Mock] test card DECLINED method={}", paymentMethodId);
            return ProviderResult.terminalFailure("CARD_DECLINED",
                    "Kartica je odbijena od strane banke (test kartica)");
        }
        if (INSUFFICIENT_CARDS.contains(paymentMethodId)) {
            log.warn("[Mock] test card INSUFFICIENT_FUNDS method={}", paymentMethodId);
            return ProviderResult.terminalFailure("INSUFFICIENT_FUNDS",
                    "Nedovoljno sredstava na računu (test kartica)");
        }
        if (EXPIRED_CARDS.contains(paymentMethodId)) {
            log.warn("[Mock] test card EXPIRED method={}", paymentMethodId);
            return ProviderResult.terminalFailure("EXPIRED_CARD",
                    "Kartica je istekla (test kartica)");
        }
        if (FRAUD_CARDS.contains(paymentMethodId)) {
            log.warn("[Mock] test card FRAUD_SUSPECTED method={}", paymentMethodId);
            return ProviderResult.terminalFailure("FRAUD_SUSPECTED",
                    "Transakcija blokirana — 3D Secure verifikacija neuspešna (test kartica)");
        }
        if (SCA_REQUIRED_CARDS.contains(paymentMethodId)) {
            // ── REDIRECT_REQUIRED — do NOT mark as failed ──
            log.warn("[Mock] test card SCA_REQUIRED → REDIRECT method={}", paymentMethodId);
            return ProviderResult.redirectRequired(
                    "https://mock-3ds.rentoza.rs/challenge?token=" + shortUuid(),
                    "sca_session_" + shortUuid(),
                    Instant.now().plus(Duration.ofMinutes(15)));
        }
        if (PROCESSING_ERR_CARDS.contains(paymentMethodId)) {
            log.warn("[Mock] test card PROCESSING_ERROR method={}", paymentMethodId);
            return ProviderResult.retryableFailure("PROCESSING_ERROR",
                    "Greška pri obradi plaćanja — pokušajte ponovo (test kartica)");
        }
        if (ASYNC_CARDS.contains(paymentMethodId)) {
            log.warn("[Mock] test card ASYNC → PENDING method={}", paymentMethodId);
            return ProviderResult.pending("ASYNC_PROCESSING");
        }
        return null;
    }

    /**
     * P1-2: Atomic idempotency guard.
     *
     * <p>{@link ConcurrentHashMap#computeIfAbsent} guarantees the mapping function is invoked
     * at most once per key, eliminating the get-then-put TOCTOU race present in the old pattern.
     * Concurrent callers with the same key will block until the first computation completes,
     * then receive the already-stored result without re-running the operation.
     */
    private ProviderResult withIdempotency(String key, Supplier<ProviderResult> computation) {
        return idempotencyStore.computeIfAbsent(key, k -> computation.get());
    }

    private boolean shouldFail() {
        if (forceFailure) {
            log.warn("[Mock] force-failure=true");
            return true;
        }
        return failureRate > 0 && random.nextDouble() < failureRate;
    }

    /** Fails that originate from random/forced mode are retryable unless the code is hard-decline. */
    private ProviderResult retryableOrTerminal(BigDecimal amount) {
        String msg = "Plaćanje nije uspelo: " + getFailureMessage();
        boolean isHardDecline = Set.of("CARD_DECLINED", "FRAUD_SUSPECTED", "EXPIRED_CARD")
                .contains(failureCode);
        return isHardDecline
                ? ProviderResult.terminalFailure(failureCode, msg)
                : ProviderResult.retryableFailure(failureCode, msg);
    }

    private String getFailureMessage() {
        return switch (failureCode) {
            case "CARD_DECLINED"    -> "Kartica je odbijena od strane banke";
            case "INSUFFICIENT_FUNDS" -> "Nedovoljno sredstava na računu";
            case "EXPIRED_CARD"     -> "Kartica je istekla";
            case "INVALID_CVV"      -> "Neispravan sigurnosni kod";
            case "PROCESSING_ERROR" -> "Greška pri obradi plaćanja";
            case "NETWORK_TIMEOUT"  -> "Mrežna greška — pokušajte ponovo";
            case "FRAUD_SUSPECTED"  -> "Transakcija blokirana iz sigurnosnih razloga";
            default                 -> "Plaćanje nije uspelo: " + failureCode;
        };
    }

    private void simulateDelay() {
        try {
            if (simulateTimeout) {
                log.warn("[Mock] simulating timeout...");
                Thread.sleep(30_000);
                throw new RuntimeException("Payment provider timeout (simulated)");
            }
            if (delayMs > 0) Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Payment processing interrupted", e);
        }
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // ── Internal authorization state ──────────────────────────────────────────

    private enum MockAuthorizationStatus { AUTHORIZED, CAPTURED, RELEASED }

    private static class MockAuthorization {
        final String authorizationId;
        final BigDecimal amount;
        final String currency;
        final Long bookingId;
        MockAuthorizationStatus status;
        String capturedTransactionId;
        Instant expiresAt;

        MockAuthorization(String authorizationId, BigDecimal amount, String currency,
                          MockAuthorizationStatus status, Long bookingId, Instant expiresAt) {
            this.authorizationId = authorizationId;
            this.amount          = amount;
            this.currency        = currency;
            this.status          = status;
            this.bookingId       = bookingId;
            this.expiresAt       = expiresAt;
        }
    }
}
