package org.example.rentoza.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;

/**
 * Mock payment provider for development and testing.
 * 
 * <p>Simulates payments with configurable failure modes for testing error scenarios.
 * 
 * <p><b>IMPORTANT:</b> Never use in production!
 * Enable with: {@code app.payment.provider=MOCK}
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
 * <h2>Failure Codes</h2>
 * <ul>
 *   <li>CARD_DECLINED - Card was declined by issuer</li>
 *   <li>INSUFFICIENT_FUNDS - Not enough balance</li>
 *   <li>EXPIRED_CARD - Card has expired</li>
 *   <li>INVALID_CVV - Security code mismatch</li>
 *   <li>PROCESSING_ERROR - Generic processing failure</li>
 *   <li>NETWORK_TIMEOUT - Simulated timeout</li>
 *   <li>FRAUD_SUSPECTED - Fraud detection triggered</li>
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
    
    /**
     * When true, all payment operations will fail.
     * Useful for testing error handling flows end-to-end.
     */
    @Value("${app.payment.mock.force-failure:false}")
    private boolean forceFailure;
    
    /**
     * Probability (0.0 to 1.0) that a payment will randomly fail.
     * Set to 0.1 for 10% failure rate during integration testing.
     */
    @Value("${app.payment.mock.failure-rate:0.0}")
    private double failureRate;
    
    /**
     * Specific error code to return when failure occurs.
     * Default is CARD_DECLINED.
     */
    @Value("${app.payment.mock.failure-code:CARD_DECLINED}")
    private String failureCode;
    
    /**
     * When true, simulates a network timeout instead of returning a response.
     */
    @Value("${app.payment.mock.simulate-timeout:false}")
    private boolean simulateTimeout;
    
    /**
     * Simulated processing delay in milliseconds.
     * Set higher values to test timeout handling.
     */
    @Value("${app.payment.mock.delay-ms:200}")
    private int delayMs;

    @Override
    public String getProviderName() {
        return "MOCK";
    }

    @Override
    public PaymentResult authorize(PaymentRequest request) {
        log.info("[MockPaymentProvider] Authorizing {} {} for booking {}", 
            request.getAmount(), request.getCurrency(), request.getBookingId());
        
        simulateDelay();
        
        if (shouldFail()) {
            return createFailureResult(request.getAmount(), "Authorization failed");
        }
        
        String authId = "auth_" + UUID.randomUUID().toString().substring(0, 8);
        
        log.info("[MockPaymentProvider] Authorization successful: {}", authId);
        return PaymentResult.builder()
                .success(true)
                .authorizationId(authId)
                .amount(request.getAmount())
                .status(PaymentStatus.AUTHORIZED)
                .build();
    }

    @Override
    public PaymentResult capture(String authorizationId, BigDecimal amount) {
        log.info("[MockPaymentProvider] Capturing {} for authorization {}", amount, authorizationId);
        
        simulateDelay();
        
        if (shouldFail()) {
            return createFailureResult(amount, "Capture failed");
        }
        
        String txnId = "txn_" + UUID.randomUUID().toString().substring(0, 8);
        
        log.info("[MockPaymentProvider] Capture successful: {}", txnId);
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
        log.info("[MockPaymentProvider] Charging {} {} for booking {}", 
            request.getAmount(), request.getCurrency(), request.getBookingId());
        
        simulateDelay();
        
        if (shouldFail()) {
            return createFailureResult(request.getAmount(), "Charge failed");
        }
        
        String txnId = "txn_" + UUID.randomUUID().toString().substring(0, 8);
        
        log.info("[MockPaymentProvider] Charge successful: {}", txnId);
        return PaymentResult.builder()
                .success(true)
                .transactionId(txnId)
                .amount(request.getAmount())
                .status(PaymentStatus.CAPTURED)
                .build();
    }

    @Override
    public PaymentResult refund(String chargeId, BigDecimal amount, String reason) {
        log.info("[MockPaymentProvider] Refunding {} for charge {}: {}", amount, chargeId, reason);
        
        simulateDelay();
        
        if (shouldFail()) {
            return createFailureResult(amount, "Refund failed");
        }
        
        String refundId = "ref_" + UUID.randomUUID().toString().substring(0, 8);
        
        log.info("[MockPaymentProvider] Refund successful: {}", refundId);
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
        
        if (shouldFail()) {
            return PaymentResult.builder()
                    .success(false)
                    .errorCode(failureCode)
                    .errorMessage("Authorization release failed: " + getFailureMessage())
                    .status(PaymentStatus.FAILED)
                    .build();
        }
        
        log.info("[MockPaymentProvider] Authorization released: {}", authorizationId);
        return PaymentResult.builder()
                .success(true)
                .authorizationId(authorizationId)
                .status(PaymentStatus.CANCELLED)
                .build();
    }
    
    /**
     * Determines if this payment operation should fail based on configuration.
     * 
     * @return true if the operation should be simulated as a failure
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
}


