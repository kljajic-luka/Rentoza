package org.example.rentoza.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payment provider contract — Monri/Mori-ready.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Every mutating operation carries an {@code idempotencyKey} — callers generate
 *       deterministic keys so replayed calls return the same result.</li>
 *   <li>{@link ProviderResult} distinguishes terminal vs non-terminal outcomes,
 *       retryable vs non-retryable failures, and redirect-required 3DS2 flows.</li>
 *   <li>The {@code redirectUrl} field enables Monri's redirect-based 3DS2 without
 *       prematurely marking a payment as failed.</li>
 *   <li>All methods are side-effect free on repeated calls with the same
 *       {@code idempotencyKey}.</li>
 * </ul>
 *
 * <h2>Monri Integration Note</h2>
 * <p>When Monri credentials are available, implement this interface as
 * {@code MonriPaymentProvider}. The mock implementation ({@link MockPaymentProvider})
 * already implements the full contract for integration testing without live credentials.
 *
 * @see MockPaymentProvider
 */
public interface PaymentProvider {

    /** Provider identifier for logging and routing. */
    String getProviderName();

    /**
     * Place an authorization hold on the guest's payment method.
     * Funds are reserved but NOT captured.
     *
     * @param request        full payment context
     * @param idempotencyKey deterministic key — use {@link PaymentIdempotencyKey#forAuthorize}
     */
    ProviderResult authorize(PaymentRequest request, String idempotencyKey);

    /**
     * Capture a previously authorized hold.
     *
     * @param authorizationId provider authorization reference
     * @param amount          amount ≤ authorized amount
     * @param idempotencyKey  deterministic key
     */
    ProviderResult capture(String authorizationId, BigDecimal amount, String idempotencyKey);

    /**
     * Immediate charge (authorize + capture).
     * Used for extension payments, late fees, damage charges.
     */
    ProviderResult charge(PaymentRequest request, String idempotencyKey);

    /**
     * Refund a captured transaction, fully or partially.
     *
     * @param providerTransactionId provider reference from capture/charge
     * @param amount                amount ≤ captured
     * @param reason                human-readable reason for audit
     * @param idempotencyKey        deterministic key
     */
    ProviderResult refund(String providerTransactionId, BigDecimal amount,
                          String reason, String idempotencyKey);

    /**
     * Void/release an authorization that has not yet been captured.
     *
     * @param authorizationId provider authorization reference
     * @param idempotencyKey  deterministic key
     */
    ProviderResult releaseAuthorization(String authorizationId, String idempotencyKey);

    /**
     * Initiate a marketplace disbursement to a host's bank account.
     *
     * @param request        payout context (amount, userId = host's user ID)
     * @param idempotencyKey deterministic key — use {@link PaymentIdempotencyKey#forPayout}
     */
    ProviderResult payout(PaymentRequest request, String idempotencyKey);

    // =========================================================================
    // REQUEST
    // =========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class PaymentRequest {
        private Long bookingId;
        private Long userId;
        private BigDecimal amount;
        private String currency;
        private String description;
        private PaymentType type;
        /** Token from frontend / stored customer payment method. */
        private String paymentMethodId;
        /** Optional: client IP for fraud scoring (Monri requirement). */
        private String clientIp;
        /** Optional: order reference for provider reconciliation. */
        private String orderReference;
        /** Provider-assigned recipient ID for payout disbursements (e.g. Monri onboarded host). */
        private String recipientId;
    }

    // =========================================================================
    // STRUCTURED RESULT
    // =========================================================================

    /**
     * Structured result from a provider operation.
     *
     * <p>Callers MUST check {@link #getOutcome()} rather than just {@code success}:
     * <ul>
     *   <li>{@link ProviderOutcome#SUCCESS} — complete; use {@link #getProviderTransactionId()}</li>
     *   <li>{@link ProviderOutcome#REDIRECT_REQUIRED} — guest must be sent to {@link #getRedirectUrl()} for 3DS2</li>
     *   <li>{@link ProviderOutcome#PENDING} — async confirmation pending</li>
     *   <li>{@link ProviderOutcome#RETRYABLE_FAILURE} — transient error, retry safe</li>
     *   <li>{@link ProviderOutcome#TERMINAL_FAILURE} — hard decline, do not retry</li>
     * </ul>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class ProviderResult {

        /** High-level outcome. Never null. */
        private ProviderOutcome outcome;

        /** Provider transaction reference (from capture/charge). */
        private String providerTransactionId;

        /** Provider authorization reference (from authorize). */
        private String providerAuthorizationId;

        /** Refund reference (from refund). */
        private String providerRefundId;

        private BigDecimal amount;
        private String currency;

        /** 3DS2 redirect URL — non-null only when outcome == REDIRECT_REQUIRED. */
        private String redirectUrl;

        /** Opaque session token to correlate redirect return. */
        private String sessionToken;

        /** Machine-readable error code (CARD_DECLINED, INSUFFICIENT_FUNDS, …). */
        private String errorCode;

        /** Human-readable, localized error message. */
        private String errorMessage;

        /** Session/auth expiry for REDIRECT_REQUIRED / PENDING outcomes. */
        private Instant expiresAt;

        /** Raw provider status string for debugging. */
        private String rawProviderStatus;

        // ── Convenience accessors ──────────────────────────────────────────

        public boolean isSuccess()          { return outcome == ProviderOutcome.SUCCESS; }
        public boolean isRedirectRequired() { return outcome == ProviderOutcome.REDIRECT_REQUIRED; }
        public boolean isRetryable()        { return outcome == ProviderOutcome.RETRYABLE_FAILURE; }
        public boolean isTerminalFailure()  { return outcome == ProviderOutcome.TERMINAL_FAILURE; }
        public boolean isPending()          { return outcome == ProviderOutcome.PENDING; }

        /** True if the operation failed in any way (not success and not redirect/pending). */
        public boolean isFailed() {
            return outcome == ProviderOutcome.RETRYABLE_FAILURE
                    || outcome == ProviderOutcome.TERMINAL_FAILURE;
        }

        // ── Static factory helpers ─────────────────────────────────────────

        public static ProviderResult authSuccess(String authId, BigDecimal amount) {
            return authSuccess(authId, amount, null);
        }

        public static ProviderResult authSuccess(String authId, BigDecimal amount, Instant expiresAt) {
            return ProviderResult.builder()
                    .outcome(ProviderOutcome.SUCCESS)
                    .providerAuthorizationId(authId)
                    .amount(amount)
                    .expiresAt(expiresAt)
                    .build();
        }

        public static ProviderResult captureSuccess(String txnId, BigDecimal amount) {
            return ProviderResult.builder()
                    .outcome(ProviderOutcome.SUCCESS)
                    .providerTransactionId(txnId)
                    .amount(amount)
                    .build();
        }

        public static ProviderResult refundSuccess(String refundId, BigDecimal amount) {
            return ProviderResult.builder()
                    .outcome(ProviderOutcome.SUCCESS)
                    .providerRefundId(refundId)
                    .amount(amount)
                    .build();
        }

        public static ProviderResult payoutSuccess(String txnId, BigDecimal amount) {
            return ProviderResult.builder()
                    .outcome(ProviderOutcome.SUCCESS)
                    .providerTransactionId(txnId)
                    .amount(amount)
                    .build();
        }

        public static ProviderResult releaseSuccess(String authId) {
            return ProviderResult.builder()
                    .outcome(ProviderOutcome.SUCCESS)
                    // Deliberately no providerAuthorizationId: release creates no new auth.
                    // The legacy bridge then maps this as CANCELLED via the all-null-ids path.
                    .rawProviderStatus("RELEASED:" + authId)
                    .build();
        }

        public static ProviderResult redirectRequired(String redirectUrl, String sessionToken,
                                                       Instant expiresAt) {
            return ProviderResult.builder()
                    .outcome(ProviderOutcome.REDIRECT_REQUIRED)
                    .redirectUrl(redirectUrl)
                    .sessionToken(sessionToken)
                    .expiresAt(expiresAt)
                    .build();
        }

        public static ProviderResult retryableFailure(String errorCode, String message) {
            return ProviderResult.builder()
                    .outcome(ProviderOutcome.RETRYABLE_FAILURE)
                    .errorCode(errorCode)
                    .errorMessage(message)
                    .build();
        }

        public static ProviderResult terminalFailure(String errorCode, String message) {
            return ProviderResult.builder()
                    .outcome(ProviderOutcome.TERMINAL_FAILURE)
                    .errorCode(errorCode)
                    .errorMessage(message)
                    .build();
        }

        public static ProviderResult pending(String rawStatus) {
            return ProviderResult.builder()
                    .outcome(ProviderOutcome.PENDING)
                    .rawProviderStatus(rawStatus)
                    .build();
        }
    }

    // =========================================================================
    // ENUMS
    // =========================================================================

    enum ProviderOutcome {
        SUCCESS,
        REDIRECT_REQUIRED,
        PENDING,
        RETRYABLE_FAILURE,
        TERMINAL_FAILURE
    }

    enum PaymentType {
        BOOKING_PAYMENT,
        SECURITY_DEPOSIT,
        DAMAGE_CHARGE,
        LATE_FEE,
        EXTENSION_PAYMENT,
        REFUND,
        PAYOUT
    }

    // Legacy result — kept until full migration
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class PaymentResult {
        private boolean success;
        private String transactionId;
        private String authorizationId;
        private BigDecimal amount;
        private String currency;
        private String errorCode;
        private String errorMessage;
        private PaymentStatus status;
        /** Populated for redirect-based 3DS2 flows. */
        private String redirectUrl;

        public boolean isRedirectRequired() {
            return redirectUrl != null && !redirectUrl.isBlank();
        }
    }

    enum PaymentStatus {
        PENDING,
        AUTHORIZED,
        CAPTURED,
        REFUNDED,
        FAILED,
        CANCELLED,
        SUCCESS,
        REDIRECT_REQUIRED
    }
}


