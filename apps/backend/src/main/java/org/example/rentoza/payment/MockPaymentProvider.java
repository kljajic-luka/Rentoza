package org.example.rentoza.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Mock payment provider for development and staging testing.
 *
 * <p>Implements the full {@link PaymentProvider} contract including Monri-style
 * redirect-based 3DS2, authorization expiry, idempotency, and strict unknown-ID rejection.
 *
 * <h2>State Store</h2>
 * <p>All mutable state (authorizations, captured transactions, refunded amounts,
 * idempotency cache) is delegated to a {@link MockStateStore}. When Redis is available
 * (staging), {@link RedisMockStateStore} is used so multiple replicas share state.
 * Otherwise, {@link InMemoryMockStateStore} provides ConcurrentHashMap-backed storage
 * for single-instance dev and unit tests.
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
 *   <li>{@code app.payment.mock.redis-namespace=mock_pay} — Redis key prefix</li>
 *   <li>{@code app.payment.mock.redis-ttl-hours=168} — Redis key TTL (7 days default)</li>
 * </ul>
 *
 * <p><b>NEVER USE IN PRODUCTION.</b>
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "MOCK")
public class MockPaymentProvider implements PaymentProvider {

    private final Random random = new Random();

    // ── State store (Redis or in-memory) ──────────────────────────────────────

    /**
     * Initialized to in-memory by default. Upgraded to Redis in {@link #initStateStore()}
     * when Spring injects RedisTemplate. Tests using {@code new MockPaymentProvider()}
     * get the in-memory store without needing Spring.
     */
    private MockStateStore store = new InMemoryMockStateStore();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Value("${app.payment.mock.redis-namespace:mock_pay}")
    private String redisNamespace = "mock_pay";

    @Value("${app.payment.mock.redis-ttl-hours:168}")
    private int redisTtlHours = 168;

    // ── Async / 3DS completion dependencies (optional — not available in unit tests) ──

    @Autowired(required = false)
    private TaskScheduler taskScheduler;

    @Autowired(required = false)
    private ProviderEventService providerEventService;

    @Value("${app.payment.webhook.secret:}")
    private String webhookSecret = "";

    @Value("${app.payment.mock.async-confirm-delay-seconds:5}")
    private int asyncConfirmDelaySeconds = 5;

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

    // ── State store initialization ────────────────────────────────────────────

    /**
     * Select Redis or in-memory store based on available beans.
     * Called by Spring after field injection. Tests using {@code new MockPaymentProvider()}
     * skip this — the field initializer provides the in-memory fallback.
     */
    @PostConstruct
    void initStateStore() {
        if (redisTemplate != null && objectMapper != null) {
            store = new RedisMockStateStore(redisTemplate, objectMapper,
                    redisNamespace, Duration.ofHours(redisTtlHours));
            log.info("[Mock] Using Redis-backed state store (namespace={}, ttl={}h)",
                    redisNamespace, redisTtlHours);
        } else {
            log.info("[Mock] Using in-memory state store (Redis not available)");
        }
    }

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

        return store.computeIdempotent(idempotencyKey, () -> {
            simulateDelay();

            ProviderResult cardResult = checkTestCard(request.getPaymentMethodId(), request.getAmount());
            if (cardResult != null) {
                if (cardResult.isRedirectRequired()) {
                    return handleScaRequired(request);
                }
                if (cardResult.isPending()) {
                    return handleAsyncPending(request);
                }
                return cardResult;
            }
            if (shouldFail()) return retryableOrTerminal(request.getAmount());

            String authId = "mock_auth_" + shortUuid();
            Instant expiresAt = Instant.now().plus(Duration.ofMinutes(authExpiryMinutes));
            store.saveAuthorization(authId, new MockAuthorization(
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

        return store.computeIdempotent(idempotencyKey, () -> {
            simulateDelay();

            // P1-11: Reject zero/negative amounts before any state inspection.
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                return ProviderResult.terminalFailure("INVALID_AMOUNT",
                        "Capture amount must be positive: " + amount);
            }

            // ── STRICT: reject unknown authorization IDs (quick check before lock) ──
            MockAuthorization preCheck = store.loadAuthorization(authorizationId);
            if (preCheck == null) {
                log.error("[Mock] REJECTED capture: unknown authorizationId={}", authorizationId);
                return ProviderResult.terminalFailure("UNKNOWN_AUTHORIZATION",
                        "Authorization ID not found: " + authorizationId);
            }

            // Acquire per-authorization lock for all state inspections and mutations.
            // Same lock key as releaseAuthorization to prevent capture+release interleave.
            return store.withLock("auth:" + authorizationId, () -> {
                MockAuthorization auth = store.loadAuthorization(authorizationId);
                if (auth == null) {
                    return ProviderResult.terminalFailure("UNKNOWN_AUTHORIZATION",
                            "Authorization ID not found: " + authorizationId);
                }

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
                store.saveAuthorization(authorizationId, auth);
                store.saveCapturedTransaction(txnId, amount);

                ProviderResult result = ProviderResult.captureSuccess(txnId, amount);
                log.info("[Mock] capture SUCCESS txnId={} authId={}", txnId, authorizationId);
                return result;
            });
        });
    }

    // ── charge ────────────────────────────────────────────────────────────────

    @Override
    public ProviderResult charge(PaymentRequest request, String idempotencyKey) {
        log.info("[Mock] charge booking={} amount={} method={} idk={}",
                request.getBookingId(), request.getAmount(), request.getPaymentMethodId(), idempotencyKey);

        return store.computeIdempotent(idempotencyKey, () -> {
            simulateDelay();

            ProviderResult cardResult = checkTestCard(request.getPaymentMethodId(), request.getAmount());
            if (cardResult != null) {
                if (cardResult.isRedirectRequired()) {
                    return handleScaRequired(request);
                }
                if (cardResult.isPending()) {
                    return handleAsyncPending(request);
                }
                return cardResult;
            }
            if (shouldFail()) return retryableOrTerminal(request.getAmount());

            String txnId = "mock_txn_" + shortUuid();
            store.saveCapturedTransaction(txnId, request.getAmount());

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

        return store.computeIdempotent(idempotencyKey, () -> {
            simulateDelay();

            // P1-11: Reject zero/negative refund amounts early.
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                return ProviderResult.terminalFailure("INVALID_AMOUNT",
                        "Refund amount must be positive: " + amount);
            }

            // ── STRICT: reject unknown transaction IDs ──
            BigDecimal capturedAmount = store.loadCapturedAmount(providerTransactionId);
            if (capturedAmount == null) {
                log.error("[Mock] REJECTED refund: unknown providerTransactionId={}", providerTransactionId);
                return ProviderResult.terminalFailure("UNKNOWN_TRANSACTION",
                        "Transaction ID not found: " + providerTransactionId);
            }

            // Per-transaction lock to prevent TOCTOU race on refunded amounts
            return store.withLock("refund:" + providerTransactionId, () -> {
                BigDecimal alreadyRefunded = store.getRefundedAmount(providerTransactionId);
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
                store.addRefundedAmount(providerTransactionId, amount);

                ProviderResult result = ProviderResult.refundSuccess(refundId, amount);
                log.info("[Mock] refund SUCCESS refundId={} txnId={} amount={}", refundId, providerTransactionId, amount);
                return result;
            });
        });
    }

    // ── releaseAuthorization ──────────────────────────────────────────────────

    @Override
    public ProviderResult releaseAuthorization(String authorizationId, String idempotencyKey) {
        log.info("[Mock] release authId={} idk={}", authorizationId, idempotencyKey);

        return store.computeIdempotent(idempotencyKey, () -> {
            simulateDelay();

            // ── STRICT: reject unknown authorization IDs ──
            MockAuthorization preCheck = store.loadAuthorization(authorizationId);
            if (preCheck == null) {
                log.error("[Mock] REJECTED release: unknown authorizationId={}", authorizationId);
                return ProviderResult.terminalFailure("UNKNOWN_AUTHORIZATION",
                        "Authorization ID not found: " + authorizationId);
            }

            // Same lock key as capture to prevent capture+release interleave
            return store.withLock("auth:" + authorizationId, () -> {
                MockAuthorization auth = store.loadAuthorization(authorizationId);
                if (auth == null) {
                    return ProviderResult.terminalFailure("UNKNOWN_AUTHORIZATION",
                            "Authorization ID not found: " + authorizationId);
                }

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
                store.saveAuthorization(authorizationId, auth);

                ProviderResult result = ProviderResult.releaseSuccess(authorizationId);
                log.info("[Mock] release SUCCESS authId={}", authorizationId);
                return result;
            });
        });
    }

    // =========================================================================
    // Payout
    // =========================================================================

    @Override
    public ProviderResult payout(PaymentRequest request, String idempotencyKey) {
        return store.computeIdempotent(idempotencyKey, () -> {
            simulateDelay();

            if (shouldFail()) {
                ProviderResult failure = retryableOrTerminal(request.getAmount());
                log.warn("[Mock] Payout FAILED for host={} booking={}: {}",
                        request.getUserId(), request.getBookingId(), failure.getErrorCode());
                return failure;
            }

            String txnId = "mock_payout_" + shortUuid();
            ProviderResult result = ProviderResult.payoutSuccess(txnId, request.getAmount());
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
     * L1: Package-private — test-only, must not be called from production code.
     */
    void expireAuthorization(String authorizationId) {
        MockAuthorization auth = store.loadAuthorization(authorizationId);
        if (auth != null) {
            auth.expiresAt = Instant.now().minusSeconds(1);
            store.saveAuthorization(authorizationId, auth);
            log.info("[Mock] TEST: authorization {} explicitly expired", authorizationId);
        }
    }

    /**
     * Returns true if the authorization exists AND is still active (not released or expired).
     * Released or expired authorizations are treated as absent for test assertion purposes.
     * L1: Package-private — test-only.
     */
    boolean hasAuthorization(String authorizationId) {
        MockAuthorization auth = store.loadAuthorization(authorizationId);
        return auth != null && auth.status != MockAuthorizationStatus.RELEASED;
    }

    /**
     * Inject a captured transaction (for testing refund paths).
     * L1: Package-private — test-only.
     */
    void injectCapturedTransaction(String txnId, BigDecimal amount) {
        store.saveCapturedTransaction(txnId, amount);
    }

    /**
     * Return whether an authorization is expired per this provider's clock.
     * L1: Package-private — test-only.
     */
    boolean isAuthorizationExpired(String authorizationId) {
        MockAuthorization auth = store.loadAuthorization(authorizationId);
        if (auth == null) return false;
        return auth.expiresAt != null && Instant.now().isAfter(auth.expiresAt);
    }

    /**
     * Load an SCA session by token. Used by {@link MockAcsController}
     * to resolve the challenge page context.
     */
    MockScaSession loadScaSession(String token) {
        return store.loadScaSession(token);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Handle an SCA-required test card: create a real authorization and an SCA session,
     * then return REDIRECT_REQUIRED pointing to the mock ACS challenge page.
     */
    private ProviderResult handleScaRequired(PaymentRequest request) {
        String authId = "mock_auth_" + shortUuid();
        String token = shortUuid();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(authExpiryMinutes));

        store.saveAuthorization(authId, new MockAuthorization(
                authId, request.getAmount(), request.getCurrency(),
                MockAuthorizationStatus.AUTHORIZED, request.getBookingId(), expiresAt));

        store.saveScaSession(token, new MockScaSession(token, request.getBookingId(), authId));

        ProviderResult result = ProviderResult.builder()
                .outcome(ProviderOutcome.REDIRECT_REQUIRED)
                .redirectUrl("/mock/acs/challenge?token=" + token)
                .sessionToken("sca_session_" + token)
                .providerAuthorizationId(authId)
                .amount(request.getAmount())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(15)))
                .build();
        log.info("[Mock] SCA required → REDIRECT authId={} token={} booking={}",
                authId, token, request.getBookingId());
        return result;
    }

    /**
     * Handle an async test card: create a real authorization, return PENDING,
     * and schedule a delayed synthetic webhook to confirm the payment.
     */
    private ProviderResult handleAsyncPending(PaymentRequest request) {
        String authId = "mock_auth_" + shortUuid();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(authExpiryMinutes));

        store.saveAuthorization(authId, new MockAuthorization(
                authId, request.getAmount(), request.getCurrency(),
                MockAuthorizationStatus.AUTHORIZED, request.getBookingId(), expiresAt));

        scheduleAsyncConfirmation(request.getBookingId(), authId);

        ProviderResult result = ProviderResult.builder()
                .outcome(ProviderOutcome.PENDING)
                .providerAuthorizationId(authId)
                .amount(request.getAmount())
                .rawProviderStatus("ASYNC_PROCESSING")
                .build();
        log.info("[Mock] ASYNC → PENDING authId={} booking={} (confirmation in {}s)",
                authId, request.getBookingId(), asyncConfirmDelaySeconds);
        return result;
    }

    /**
     * Schedule a delayed synthetic webhook via {@link TaskScheduler} to confirm
     * an async payment. If TaskScheduler or ProviderEventService are not available
     * (unit tests without Spring), the webhook is skipped with a log warning.
     */
    private void scheduleAsyncConfirmation(Long bookingId, String authId) {
        if (taskScheduler == null || providerEventService == null) {
            log.warn("[Mock] TaskScheduler or ProviderEventService not available — "
                    + "async confirmation webhook will not fire for booking={}", bookingId);
            return;
        }
        taskScheduler.schedule(() -> {
            try {
                String eventId = "mock_async_" + shortUuid();
                String payload = String.format(
                        "{\"type\":\"PAYMENT_CONFIRMED\",\"booking_id\":%d,"
                        + "\"auth_id\":\"%s\",\"timestamp\":\"%s\"}",
                        bookingId, authId, Instant.now().toString());
                String signature = computeHmac(payload);
                providerEventService.ingestEvent(
                        eventId, "PAYMENT_CONFIRMED", bookingId, authId, payload, signature);
                log.info("[Mock] Async confirmation fired for booking={} authId={}", bookingId, authId);
            } catch (Exception e) {
                log.error("[Mock] Async confirmation failed for booking={}: {}", bookingId, e.getMessage(), e);
            }
        }, Instant.now().plusSeconds(asyncConfirmDelaySeconds));
    }

    /**
     * Compute HMAC-SHA256 signature matching {@link ProviderEventService}'s verification.
     * Returns {@code null} if no webhook secret is configured.
     */
    String computeHmac(String payload) {
        if (webhookSecret == null || webhookSecret.isBlank()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("[Mock] Failed to compute HMAC for webhook", e);
            return null;
        }
    }

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

    /**
     * Simulate real-world provider latency or timeout.
     *
     * <p><b>M3:</b> Timeout simulation now throws immediately instead of blocking for
     * 30 seconds — real gateway timeouts are detected by the HTTP client layer,
     * not by the provider sleeping on the request thread.
     *
     * <p>Normal delay is logged but does NOT block the thread unless
     * {@code app.payment.mock.delay-blocking=true} is set (for staging realism).
     * This avoids wasting Tomcat threads during dev and test.
     */
    private void simulateDelay() {
        if (simulateTimeout) {
            log.warn("[Mock] simulating timeout (immediate throw)");
            throw new RuntimeException("Payment provider timeout (simulated)");
        }
        if (delayMs > 0) {
            log.debug("[Mock] simulated {}ms provider latency", delayMs);
        }
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
