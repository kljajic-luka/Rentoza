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
 * <p>Behavior is controlled by {@code app.payment.enforce-real-provider} (default: false):
 * <ul>
 *   <li>{@code true} — throws {@link IllegalStateException} at startup (hard block)</li>
 *   <li>{@code false} — logs a critical warning but allows startup (pre-production/staging)</li>
 * </ul>
 *
 * <p>Set {@code enforce-real-provider=true} once a real provider (e.g., MONRI) is configured.
 */
@Component
public class PaymentProviderStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderStartupValidator.class);

    @Value("${app.payment.provider}")
    private String paymentProvider;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Value("${app.payment.enforce-real-provider:false}")
    private boolean enforceRealProvider;

    @PostConstruct
    void validatePaymentProvider() {
        if ("MOCK".equalsIgnoreCase(paymentProvider)) {
            if (activeProfile != null && activeProfile.contains("prod")) {
                String message = "CRITICAL: app.payment.provider is set to MOCK in production. " +
                        "Real payment processing is disabled — guests will NOT be charged. " +
                        "Set PAYMENT_PROVIDER to a real provider (e.g., MONRI) in Cloud Run environment variables.";

                if (enforceRealProvider) {
                    throw new IllegalStateException(message);
                }

                log.error("========================================================");
                log.error(message);
                log.error("Set app.payment.enforce-real-provider=true to block startup with MOCK.");
                log.error("========================================================");
                return;
            }
            log.warn("[SECURITY] Payment provider is MOCK — all charges are simulated. " +
                     "This MUST NOT reach production.");
        }
    }
}
