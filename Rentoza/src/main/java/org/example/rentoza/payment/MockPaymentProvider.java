package org.example.rentoza.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mock payment provider for development and testing.
 * 
 * <p>Simulates successful payments without actual transactions.
 * 
 * <p><b>IMPORTANT:</b> Never use in production!
 * Enable with: {@code app.payment.provider=MOCK}
 * 
 * <p>Requires explicit configuration - will NOT activate if property is missing.
 * Set PAYMENT_PROVIDER=MOCK in .env.local for development.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "MOCK")
public class MockPaymentProvider implements PaymentProvider {

    @Override
    public String getProviderName() {
        return "MOCK";
    }

    @Override
    public PaymentResult authorize(PaymentRequest request) {
        log.info("[MockPaymentProvider] Authorizing {} {} for booking {}", 
            request.getAmount(), request.getCurrency(), request.getBookingId());
        
        simulateDelay();
        
        String authId = "auth_" + UUID.randomUUID().toString().substring(0, 8);
        
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
        
        String txnId = "txn_" + UUID.randomUUID().toString().substring(0, 8);
        
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
        
        String txnId = "txn_" + UUID.randomUUID().toString().substring(0, 8);
        
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
        
        String refundId = "ref_" + UUID.randomUUID().toString().substring(0, 8);
        
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
        
        return PaymentResult.builder()
                .success(true)
                .authorizationId(authorizationId)
                .status(PaymentStatus.CANCELLED)
                .build();
    }

    private void simulateDelay() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}


