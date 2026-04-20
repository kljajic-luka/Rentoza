package org.example.rentoza.exception;

/**
 * Exception thrown when payment authorization fails during booking creation.
 * 
 * <p>This exception maps to HTTP 402 (Payment Required) and indicates that
 * the booking cannot proceed because payment could not be authorized.
 * 
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Card declined by issuer</li>
 *   <li>Insufficient funds for booking total or security deposit</li>
 *   <li>Expired card</li>
 *   <li>Payment provider timeout/error</li>
 *   <li>No valid payment method on file</li>
 * </ul>
 * 
 * <h2>Frontend Handling</h2>
 * <p>Check for {@code error.code === 'PAYMENT_FAILED'} or
 * {@code error.code === 'INSUFFICIENT_FUNDS'} to show appropriate UI.
 * 
 * @since Phase 5 - Payment Integration
 */
public class PaymentAuthorizationException extends RuntimeException {

    private final String errorCode;

    public PaymentAuthorizationException(String message) {
        super(message);
        this.errorCode = "PAYMENT_FAILED";
    }

    public PaymentAuthorizationException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public PaymentAuthorizationException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
