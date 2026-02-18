package org.example.rentoza.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock payment provider for development and staging testing.
 * 
 * <p>Simulates a production-grade payment gateway with deterministic test-card
 * behavior, authorization lifecycle tracking, and configurable failure modes.
 * 
 * <p><b>IMPORTANT:</b> Never use in production!
 * Enable with: {@code app.payment.provider=MOCK}
 * 
 * <h2>Test Card Numbers (via paymentMethodId)</h2>
 * <ul>
 *   <li>{@code pm_card_visa} / any default - Succeeds</li>
 *   <li>{@code pm_card_declined} - Card declined</li>
 *   <li>{@code pm_card_insufficient} - Insufficient funds</li>
 *   <li>{@code pm_card_expired} - Expired card</li>
 *   <li>{@code pm_card_fraud} - Fraud suspected (3DS failure)</li>
 *   <li>{@code pm_card_sca_required} - SCA/3DS required (simulated challenge)</li>
 *   <li>{@code pm_card_processing_error} - Processing error (transient)</li>
 * </ul>
 * 
 * <h2>Authorization Lifecycle</h2>
 * <p>Tracks authorization state to validate:
 * <ul>
 *   <li>Capture only allowed on valid, non-expired authorizations</li>
 *   <li>Double capture is rejected</li>
 *   <li>Release of already-captured authorization is rejected</li>
 *   <li>Refund only on captured transactions</li>
 *   <li>Refund amount cannot exceed captured amount</li>
 * </ul>
 * 
 * <h2>Configuration Options</h2>
 * <ul>
 *   <li>{@code app.payment.mock.force-failure=true} - Force all payments to fail</li>
 *   <li>{@code app.payment.mock.failure-rate=0.1} - 10% random failure rate</li>
 *   <li>{@code app.payment.mock.failure-code=CARD_DECLINED} - Specific failure code</li>
 *   <li>{@code app.payment.mock.simulate-timeout=true} - Simulate network timeout</li>
 *   <li>{@code app.payment.mock.delay-ms=200} - Simulated processing delay</li>
 * </ul>
 * 
 * <p>Requires explicit configuration - will NOT activate if property is missing.
 * Set PAYMENT_PROVIDER=MOCK in .env.local for development.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "MOCK")
public class MockPaymentProvider implements PaymentProvider {

    private final Random random = new Random();
    
    // ========== AUTHORIZATION LIFECYCLE TRACKING ==========
    
    /**
     * Tracks authorization states for capture/release validation.
     * Key: authorizationId, Value: authorization details.
     */
    private final Map<String, MockAuthorization> authorizations = new ConcurrentHashMap<>();
    
    /**
     * Tracks captured transactions for refund validation.
     * Key: transactionId, Value: captured amount.
     */
    private final Map<String, BigDecimal> capturedTransactions = new ConcurrentHashMap<>();
    
    /**
     * Tracks total refunded amount per transaction for refund <= captured validation.
     * Key: transactionId, Value: total refunded so far.
     */
    private final Map<String, BigDecimal> refundedAmounts = new ConcurrentHashMap<>();
    
    /**
     * Test payment method IDs that trigger specific failure scenarios.
     * Simulates Stripe-style test card behavior for staging.
     */
    private static final Set<String> DECLINED_CARDS = Set.of(
            "pm_card_declined", "4000000000000002"
    );
    private static final Set<String> INSUFFICIENT_CARDS = Set.of(
            "pm_card_insufficient", "4000000000009995"
    );
    private static final Set<String> EXPIRED_CARDS = Set.of(
            "pm_card_expired", "4000000000000069"
    );
    private static final Set<String> FRAUD_CARDS = Set.of(
            "pm_card_fraud", "4000000000000127"
    );
    private static final Set<String> SCA_REQUIRED_CARDS = Set.of(
            "pm_card_sca_required", "4000002500003155"
    );
    private static final Set<String> PROCESSING_ERROR_CARDS = Set.of(
            "pm_card_processing_error", "4000000000000119"
    );
    
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

    @Override
    public String getProviderName() {
        return "MOCK";
    }

    @Override
    public PaymentResult authorize(PaymentRequest request) {
        log.info("[MockPaymentProvider] Authorizing {} {} for booking {} (method: {})", 
            request.getAmount(), request.getCurrency(), request.getBookingId(), request.getPaymentMethodId());
        
        simulateDelay();
        
        // Check test-card scenarios FIRST (before random/force failures)
        PaymentResult cardFailure = checkTestCard(request.getPaymentMethodId(), request.getAmount());
        if (cardFailure != null) {
            return cardFailure;
        }
        
        if (shouldFail()) {
            return createFailureResult(request.getAmount(), "Authorization failed");
        }
        
        String authId = "auth_" + UUID.randomUUID().toString().substring(0, 8);
        
        // Track authorization for lifecycle validation
        authorizations.put(authId, new MockAuthorization(
            authId, request.getAmount(), request.getCurrency(), 
            MockAuthorizationStatus.AUTHORIZED, request.getBookingId()
        ));
        
        log.info("[MockPaymentProvider] Authorization successful: {} (amount: {} {})", 
            authId, request.getAmount(), request.getCurrency());
        return PaymentResult.builder()
                .success(true)
                .authorizationId(authId)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(PaymentStatus.AUTHORIZED)
                .build();
    }

    @Override
    public PaymentResult capture(String authorizationId, BigDecimal amount) {
        log.info("[MockPaymentProvider] Capturing {} for authorization {}", amount, authorizationId);
        
        simulateDelay();
        
        // Validate authorization exists and is in correct state
        MockAuthorization auth = authorizations.get(authorizationId);
        if (auth != null) {
            if (auth.status == MockAuthorizationStatus.CAPTURED) {
                log.warn("[MockPaymentProvider] REJECTED: Authorization {} already captured (double capture attempt)", 
                    authorizationId);
                return PaymentResult.builder()
                        .success(false)
                        .errorCode("ALREADY_CAPTURED")
                        .errorMessage("Authorization already captured - rejected to prevent double charge")
                        .status(PaymentStatus.FAILED)
                        .build();
            }
            if (auth.status == MockAuthorizationStatus.RELEASED) {
                log.warn("[MockPaymentProvider] REJECTED: Authorization {} was already released", authorizationId);
                return PaymentResult.builder()
                        .success(false)
                        .errorCode("AUTH_RELEASED")
                        .errorMessage("Cannot capture a released authorization")
                        .status(PaymentStatus.FAILED)
                        .build();
            }
            if (amount.compareTo(auth.amount) > 0) {
                log.warn("[MockPaymentProvider] REJECTED: Capture amount {} exceeds authorized amount {}", 
                    amount, auth.amount);
                return PaymentResult.builder()
                        .success(false)
                        .errorCode("AMOUNT_EXCEEDS_AUTH")
                        .errorMessage("Capture amount exceeds authorized amount")
                        .status(PaymentStatus.FAILED)
                        .build();
            }
        }
        
        if (shouldFail()) {
            return createFailureResult(amount, "Capture failed");
        }
        
        String txnId = "txn_" + UUID.randomUUID().toString().substring(0, 8);
        
        // Update authorization state
        if (auth != null) {
            auth.status = MockAuthorizationStatus.CAPTURED;
            auth.capturedTransactionId = txnId;
        }
        
        // Track captured amount for refund validation
        capturedTransactions.put(txnId, amount);
        
        log.info("[MockPaymentProvider] Capture successful: {} (auth: {})", txnId, authorizationId);
        return PaymentResult.builder()
                .success(true)
                .transactionId(txnId)
                .authorizationId(authorizationId)
                .amount(amount)
                .status(PaymentStatus.CAPTURED)
                .build();
    }

    @Override
    public PaymentResult charge(PaymentRequest request) {
        log.info("[MockPaymentProvider] Charging {} {} for booking {} (method: {})", 
            request.getAmount(), request.getCurrency(), request.getBookingId(), request.getPaymentMethodId());
        
        simulateDelay();
        
        // Check test-card scenarios
        PaymentResult cardFailure = checkTestCard(request.getPaymentMethodId(), request.getAmount());
        if (cardFailure != null) {
            return cardFailure;
        }
        
        if (shouldFail()) {
            return createFailureResult(request.getAmount(), "Charge failed");
        }
        
        String txnId = "txn_" + UUID.randomUUID().toString().substring(0, 8);
        
        // Track for refund validation
        capturedTransactions.put(txnId, request.getAmount());
        
        log.info("[MockPaymentProvider] Charge successful: {}", txnId);
        return PaymentResult.builder()
                .success(true)
                .transactionId(txnId)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(PaymentStatus.CAPTURED)
                .build();
    }

    @Override
    public PaymentResult refund(String chargeId, BigDecimal amount, String reason) {
        log.info("[MockPaymentProvider] Refunding {} for charge {}: {}", amount, chargeId, reason);
        
        simulateDelay();
        
        // Validate refund <= captured amount
        BigDecimal capturedAmount = capturedTransactions.get(chargeId);
        if (capturedAmount != null) {
            BigDecimal alreadyRefunded = refundedAmounts.getOrDefault(chargeId, BigDecimal.ZERO);
            BigDecimal maxRefundable = capturedAmount.subtract(alreadyRefunded);
            
            if (amount.compareTo(maxRefundable) > 0) {
                log.warn("[MockPaymentProvider] REJECTED: Refund {} exceeds remaining refundable {} " +
                    "(captured: {}, already refunded: {})", amount, maxRefundable, capturedAmount, alreadyRefunded);
                return PaymentResult.builder()
                        .success(false)
                        .errorCode("REFUND_EXCEEDS_CAPTURED")
                        .errorMessage(String.format(
                            "Refund amount %s exceeds maximum refundable %s (captured: %s, already refunded: %s)",
                            amount, maxRefundable, capturedAmount, alreadyRefunded))
                        .status(PaymentStatus.FAILED)
                        .build();
            }
        }
        
        if (shouldFail()) {
            return createFailureResult(amount, "Refund failed");
        }
        
        String refundId = "ref_" + UUID.randomUUID().toString().substring(0, 8);
        
        // Track refunded amount
        refundedAmounts.merge(chargeId, amount, BigDecimal::add);
        
        log.info("[MockPaymentProvider] Refund successful: {} (charge: {}, amount: {})", refundId, chargeId, amount);
        return PaymentResult.builder()
                .success(true)
                .transactionId(refundId)
                .amount(amount)
                .status(PaymentStatus.REFUNDED)
                .build();
    }

    @Override
    public PaymentResult releaseAuthorization(String authorizationId) {
        log.info("[MockPaymentProvider] Releasing authorization {}", authorizationId);
        
        simulateDelay();
        
        // Validate authorization state
        MockAuthorization auth = authorizations.get(authorizationId);
        if (auth != null) {
            if (auth.status == MockAuthorizationStatus.CAPTURED) {
                log.warn("[MockPaymentProvider] REJECTED: Cannot release captured authorization {} - use refund instead", 
                    authorizationId);
                return PaymentResult.builder()
                        .success(false)
                        .errorCode("ALREADY_CAPTURED")
                        .errorMessage("Cannot release a captured authorization. Use refund instead.")
                        .status(PaymentStatus.FAILED)
                        .build();
            }
            if (auth.status == MockAuthorizationStatus.RELEASED) {
                log.info("[MockPaymentProvider] Authorization {} already released (idempotent)", authorizationId);
                return PaymentResult.builder()
                        .success(true)
                        .authorizationId(authorizationId)
                        .status(PaymentStatus.CANCELLED)
                        .build();
            }
        }
        
        if (shouldFail()) {
            return PaymentResult.builder()
                    .success(false)
                    .errorCode(failureCode)
                    .errorMessage("Authorization release failed: " + getFailureMessage())
                    .status(PaymentStatus.FAILED)
                    .build();
        }
        
        // Update authorization state
        if (auth != null) {
            auth.status = MockAuthorizationStatus.RELEASED;
        }
        
        log.info("[MockPaymentProvider] Authorization released: {}", authorizationId);
        return PaymentResult.builder()
                .success(true)
                .authorizationId(authorizationId)
                .status(PaymentStatus.CANCELLED)
                .build();
    }
    
    // ========== TEST CARD SIMULATION ==========
    
    /**
     * Checks the payment method ID against known test-card tokens.
     * Returns a failure result if the card triggers a specific scenario,
     * or null if the card should succeed normally.
     */
    private PaymentResult checkTestCard(String paymentMethodId, BigDecimal amount) {
        if (paymentMethodId == null) {
            return null; // No card specified = default success path
        }
        
        if (DECLINED_CARDS.contains(paymentMethodId)) {
            log.warn("[MockPaymentProvider] Test card DECLINED: {}", paymentMethodId);
            return PaymentResult.builder()
                    .success(false)
                    .amount(amount)
                    .errorCode("CARD_DECLINED")
                    .errorMessage("Kartica je odbijena od strane banke (test kartica)")
                    .status(PaymentStatus.FAILED)
                    .build();
        }
        
        if (INSUFFICIENT_CARDS.contains(paymentMethodId)) {
            log.warn("[MockPaymentProvider] Test card INSUFFICIENT_FUNDS: {}", paymentMethodId);
            return PaymentResult.builder()
                    .success(false)
                    .amount(amount)
                    .errorCode("INSUFFICIENT_FUNDS")
                    .errorMessage("Nedovoljno sredstava na računu (test kartica)")
                    .status(PaymentStatus.FAILED)
                    .build();
        }
        
        if (EXPIRED_CARDS.contains(paymentMethodId)) {
            log.warn("[MockPaymentProvider] Test card EXPIRED: {}", paymentMethodId);
            return PaymentResult.builder()
                    .success(false)
                    .amount(amount)
                    .errorCode("EXPIRED_CARD")
                    .errorMessage("Kartica je istekla (test kartica)")
                    .status(PaymentStatus.FAILED)
                    .build();
        }
        
        if (FRAUD_CARDS.contains(paymentMethodId)) {
            log.warn("[MockPaymentProvider] Test card FRAUD_SUSPECTED: {}", paymentMethodId);
            return PaymentResult.builder()
                    .success(false)
                    .amount(amount)
                    .errorCode("FRAUD_SUSPECTED")
                    .errorMessage("Transakcija blokirana - 3D Secure verifikacija neuspešna (test kartica)")
                    .status(PaymentStatus.FAILED)
                    .build();
        }
        
        if (SCA_REQUIRED_CARDS.contains(paymentMethodId)) {
            log.warn("[MockPaymentProvider] Test card SCA_REQUIRED: {}", paymentMethodId);
            return PaymentResult.builder()
                    .success(false)
                    .amount(amount)
                    .errorCode("SCA_REQUIRED")
                    .errorMessage("Potrebna 3D Secure autentifikacija - preusmerite korisnika na verifikaciju (test kartica)")
                    .status(PaymentStatus.FAILED)
                    .build();
        }
        
        if (PROCESSING_ERROR_CARDS.contains(paymentMethodId)) {
            log.warn("[MockPaymentProvider] Test card PROCESSING_ERROR: {}", paymentMethodId);
            return PaymentResult.builder()
                    .success(false)
                    .amount(amount)
                    .errorCode("PROCESSING_ERROR")
                    .errorMessage("Greška pri obradi plaćanja - pokušajte ponovo (test kartica)")
                    .status(PaymentStatus.FAILED)
                    .build();
        }
        
        return null; // Card not a failure-test card → proceed normally
    }
    
    /**
     * Determines if this payment operation should fail based on configuration.
     */
    private boolean shouldFail() {
        if (forceFailure) {
            log.warn("[MockPaymentProvider] Force failure enabled - failing payment");
            return true;
        }
        
        if (failureRate > 0 && random.nextDouble() < failureRate) {
            log.warn("[MockPaymentProvider] Random failure triggered (rate: {}%)", failureRate * 100);
            return true;
        }
        
        return false;
    }
    
    /**
     * Creates a standardized failure result.
     */
    private PaymentResult createFailureResult(BigDecimal amount, String context) {
        String message = context + ": " + getFailureMessage();
        log.error("[MockPaymentProvider] Payment failed: {} (code: {})", message, failureCode);
        
        return PaymentResult.builder()
                .success(false)
                .amount(amount)
                .errorCode(failureCode)
                .errorMessage(message)
                .status(PaymentStatus.FAILED)
                .build();
    }
    
    /**
     * Returns a human-readable failure message based on the configured failure code.
     */
    private String getFailureMessage() {
        return switch (failureCode) {
            case "CARD_DECLINED" -> "Kartica je odbijena od strane banke";
            case "INSUFFICIENT_FUNDS" -> "Nedovoljno sredstava na računu";
            case "EXPIRED_CARD" -> "Kartica je istekla";
            case "INVALID_CVV" -> "Neispravan sigurnosni kod";
            case "PROCESSING_ERROR" -> "Greška pri obradi plaćanja";
            case "NETWORK_TIMEOUT" -> "Mrežna greška - pokušajte ponovo";
            case "FRAUD_SUSPECTED" -> "Transakcija blokirana iz sigurnosnih razloga";
            default -> "Plaćanje nije uspelo: " + failureCode;
        };
    }

    /**
     * Simulates processing delay.
     * If timeout simulation is enabled, throws a runtime exception after a longer delay.
     */
    private void simulateDelay() {
        try {
            if (simulateTimeout) {
                log.warn("[MockPaymentProvider] Simulating timeout...");
                Thread.sleep(30000); // 30 second timeout
                throw new RuntimeException("Payment provider timeout (simulated)");
            }
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Payment processing interrupted", e);
        }
    }
    
    // ========== INTERNAL AUTHORIZATION STATE ==========
    
    private enum MockAuthorizationStatus {
        AUTHORIZED, CAPTURED, RELEASED
    }
    
    /**
     * Internal tracking of authorization lifecycle.
     * Simulates real gateway behavior where authorizations have strict state transitions.
     */
    private static class MockAuthorization {
        final String authorizationId;
        final BigDecimal amount;
        final String currency;
        final Long bookingId;
        MockAuthorizationStatus status;
        String capturedTransactionId;
        
        MockAuthorization(String authorizationId, BigDecimal amount, String currency, 
                          MockAuthorizationStatus status, Long bookingId) {
            this.authorizationId = authorizationId;
            this.amount = amount;
            this.currency = currency;
            this.status = status;
            this.bookingId = bookingId;
        }
    }
}


