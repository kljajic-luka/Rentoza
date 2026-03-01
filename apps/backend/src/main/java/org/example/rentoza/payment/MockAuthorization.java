package org.example.rentoza.payment;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mutable authorization state tracked by {@link MockPaymentProvider}.
 * Package-private: used by the provider and its state stores.
 *
 * <p>Fields are non-final to support mutation under lock and JSON deserialization
 * (Redis-backed state store). {@link JsonAutoDetect} enables Jackson field access
 * without requiring public getters/setters.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
class MockAuthorization {
    String authorizationId;
    BigDecimal amount;
    String currency;
    Long bookingId;
    MockAuthorizationStatus status;
    String capturedTransactionId;
    Instant expiresAt;

    /** No-arg constructor for JSON deserialization. */
    MockAuthorization() {}

    MockAuthorization(String authorizationId, BigDecimal amount, String currency,
                      MockAuthorizationStatus status, Long bookingId, Instant expiresAt) {
        this.authorizationId = authorizationId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.bookingId = bookingId;
        this.expiresAt = expiresAt;
    }
}
