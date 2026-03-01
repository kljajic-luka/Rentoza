package org.example.rentoza.payment;

/**
 * Lifecycle status of a mock authorization.
 * Package-private: used by {@link MockPaymentProvider} and its state stores.
 */
enum MockAuthorizationStatus {
    AUTHORIZED,
    CAPTURED,
    RELEASED
}
