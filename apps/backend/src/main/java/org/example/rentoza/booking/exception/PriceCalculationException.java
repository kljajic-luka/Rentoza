package org.example.rentoza.booking.exception;

import java.math.BigDecimal;

/**
 * Exception thrown when price calculation fails or results in an unexpected value.
 * 
 * <p>This typically occurs when:
 * <ul>
 *   <li>Price changed between quote and booking</li>
 *   <li>Discount calculation results in negative total</li>
 *   <li>Currency conversion fails</li>
 *   <li>Precision overflow in BigDecimal calculations</li>
 * </ul>
 * 
 * @author Rentoza Platform Team
 * @since Phase 9.0 - Edge Case Hardening
 */
public class PriceCalculationException extends RuntimeException {

    private final BigDecimal expectedPrice;
    private final BigDecimal actualPrice;
    private final String reason;

    public PriceCalculationException(String message) {
        super(message);
        this.expectedPrice = null;
        this.actualPrice = null;
        this.reason = message;
    }

    public PriceCalculationException(String message, BigDecimal expectedPrice, BigDecimal actualPrice) {
        super(message);
        this.expectedPrice = expectedPrice;
        this.actualPrice = actualPrice;
        this.reason = message;
    }

    public PriceCalculationException(String message, Throwable cause) {
        super(message, cause);
        this.expectedPrice = null;
        this.actualPrice = null;
        this.reason = message;
    }

    public BigDecimal getExpectedPrice() {
        return expectedPrice;
    }

    public BigDecimal getActualPrice() {
        return actualPrice;
    }

    public String getReason() {
        return reason;
    }

    public BigDecimal getDifference() {
        if (expectedPrice != null && actualPrice != null) {
            return actualPrice.subtract(expectedPrice).abs();
        }
        return null;
    }
}
