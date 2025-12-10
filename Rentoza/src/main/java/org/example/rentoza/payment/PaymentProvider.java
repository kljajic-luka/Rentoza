package org.example.rentoza.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Interface for payment providers.
 * 
 * <p>Implementations may integrate with:
 * <ul>
 *   <li>Stripe - International payments</li>
 *   <li>Local Serbian gateway - Domestic cards</li>
 *   <li>Mock provider - For development/testing</li>
 * </ul>
 */
public interface PaymentProvider {

    /**
     * Get provider name.
     */
    String getProviderName();

    /**
     * Authorize a payment (hold amount without capturing).
     * Used for security deposits.
     */
    PaymentResult authorize(PaymentRequest request);

    /**
     * Capture a previously authorized payment.
     */
    PaymentResult capture(String authorizationId, BigDecimal amount);

    /**
     * Charge a payment immediately.
     */
    PaymentResult charge(PaymentRequest request);

    /**
     * Refund a captured payment.
     */
    PaymentResult refund(String chargeId, BigDecimal amount, String reason);

    /**
     * Release an authorization without capturing.
     */
    PaymentResult releaseAuthorization(String authorizationId);

    // ========== DATA CLASSES ==========

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
        private String paymentMethodId; // Token from frontend
    }

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
    }

    enum PaymentType {
        BOOKING_PAYMENT,
        SECURITY_DEPOSIT,
        DAMAGE_CHARGE,
        LATE_FEE,
        EXTENSION_PAYMENT,
        REFUND
    }

    enum PaymentStatus {
        PENDING,
        AUTHORIZED,
        CAPTURED,
        REFUNDED,
        FAILED,
        CANCELLED,
        SUCCESS
    }
}


