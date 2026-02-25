package org.example.rentoza.payment;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * B6: Startup validator that prevents the MOCK payment provider from running in production.
 *
 * <p>The MockPaymentProvider simulates all payment operations in-memory, returning
 * synthetic authorization IDs and skipping real card charges. If activated in production,
 * guests would never be charged and hosts would never receive payouts.
 *
 * <p>This validator throws {@link IllegalStateException} at startup if
 * {@code app.payment.provider=MOCK} and the active profile contains "prod".
 */
@Component
public class PaymentProviderStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderStartupValidator.class);

    @Value("${app.payment.provider}")
    private String paymentProvider;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @PostConstruct
    void validatePaymentProvider() {
        if ("MOCK".equalsIgnoreCase(paymentProvider)) {
            if (activeProfile != null && activeProfile.contains("prod")) {
                throw new IllegalStateException(
                        "FATAL: app.payment.provider is set to MOCK in production. " +
                        "Real payment processing is disabled — guests will NOT be charged. " +
                        "Set PAYMENT_PROVIDER to a real provider (e.g., MONRI) in Cloud Run environment variables.");
            }
            log.warn("[SECURITY] Payment provider is MOCK — all charges are simulated. " +
                     "This MUST NOT reach production.");
        }
    }
}
