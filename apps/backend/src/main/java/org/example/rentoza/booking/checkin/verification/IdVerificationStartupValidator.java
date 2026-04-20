package org.example.rentoza.booking.checkin.verification;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AUDIT-F5: Startup validator that prevents the MOCK ID verification provider from running in production.
 *
 * <p>The MockIdVerificationProvider auto-approves all identity checks. If active in production,
 * unverified users can rent vehicles — creating liability exposure for damage disputes.
 *
 * <p>Behavior mirrors {@link org.example.rentoza.payment.PaymentProviderStartupValidator}:
 * <ul>
 *   <li>prod profile + MOCK provider + enforce flag → hard block (IllegalStateException)</li>
 *   <li>prod profile + MOCK provider + no enforce flag → critical log warning</li>
 *   <li>non-prod profile → informational warning only</li>
 * </ul>
 */
@Component
public class IdVerificationStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(IdVerificationStartupValidator.class);

    @Value("${app.id-verification.provider:}")
    private String idVerificationProvider;

    @Value("${spring.profiles.active:}")
    private String activeProfile;

    @Value("${app.id-verification.enforce-real-provider:false}")
    private boolean enforceRealProvider;

    @PostConstruct
    void validateIdVerificationProvider() {
        if ("MOCK".equalsIgnoreCase(idVerificationProvider)) {
            if (activeProfile != null && activeProfile.contains("prod")) {
                String message = "CRITICAL: app.id-verification.provider is set to MOCK in production. " +
                        "Identity verification is bypassed — unverified users can rent vehicles. " +
                        "Set APP_ID_VERIFICATION_PROVIDER to a real provider (e.g., ONFIDO) in Cloud Run environment variables.";

                if (enforceRealProvider) {
                    throw new IllegalStateException(message);
                }

                log.error("========================================================");
                log.error(message);
                log.error("Set app.id-verification.enforce-real-provider=true to block startup with MOCK.");
                log.error("========================================================");
                return;
            }
            log.warn("[SECURITY] ID verification provider is MOCK — all identity checks are simulated. " +
                     "This MUST NOT reach production.");
        }
    }
}
