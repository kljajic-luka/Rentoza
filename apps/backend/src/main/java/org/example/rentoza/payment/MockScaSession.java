package org.example.rentoza.payment;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Transient session state for a mock 3DS2 SCA challenge.
 *
 * <p>Created when {@link MockPaymentProvider} returns {@code REDIRECT_REQUIRED}
 * for an SCA-required test card. Looked up by {@link MockAcsController}
 * when the user completes the mock challenge page.
 *
 * <p>{@code @JsonAutoDetect} enables Jackson field-level serialization for Redis storage.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
class MockScaSession {

    String token;
    Long bookingId;
    String providerAuthorizationId;

    /** No-arg constructor for Jackson deserialization. */
    MockScaSession() {}

    MockScaSession(String token, Long bookingId, String providerAuthorizationId) {
        this.token = token;
        this.bookingId = bookingId;
        this.providerAuthorizationId = providerAuthorizationId;
    }
}
