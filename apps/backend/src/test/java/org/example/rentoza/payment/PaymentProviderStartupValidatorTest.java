package org.example.rentoza.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for B6: PaymentProviderStartupValidator prevents MOCK provider in production.
 */
class PaymentProviderStartupValidatorTest {

    private PaymentProviderStartupValidator createValidator(String provider, String profile, boolean enforce) {
        PaymentProviderStartupValidator validator = new PaymentProviderStartupValidator();
        ReflectionTestUtils.setField(validator, "paymentProvider", provider);
        ReflectionTestUtils.setField(validator, "activeProfile", profile);
        ReflectionTestUtils.setField(validator, "enforceRealProvider", enforce);
        return validator;
    }

    @Test
    @DisplayName("B6: MOCK in prod with enforce=true throws IllegalStateException")
    void givenMockProviderInProdWithEnforce_throwsIllegalStateException() {
        PaymentProviderStartupValidator validator = createValidator("MOCK", "prod", true);
        assertThatThrownBy(validator::validatePaymentProvider)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOCK")
                .hasMessageContaining("production");
    }

    @Test
    @DisplayName("B6: MOCK (lowercase) in production with enforce=true throws")
    void givenMockLowercaseInProductionWithEnforce_throwsIllegalStateException() {
        PaymentProviderStartupValidator validator = createValidator("mock", "production", true);
        assertThatThrownBy(validator::validatePaymentProvider)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOCK");
    }

    @Test
    @DisplayName("B6: MOCK in prod with enforce=false logs warning but does not throw")
    void givenMockProviderInProdWithoutEnforce_doesNotThrow() {
        PaymentProviderStartupValidator validator = createValidator("MOCK", "prod", false);
        assertThatCode(validator::validatePaymentProvider).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("B6: MOCK provider in dev profile does not throw regardless of enforce flag")
    void givenMockProviderInDevProfile_doesNotThrow() {
        PaymentProviderStartupValidator validator = createValidator("MOCK", "dev", true);
        assertThatCode(validator::validatePaymentProvider).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("B6: MONRI provider in prod profile does not throw")
    void givenMonriProviderInProdProfile_doesNotThrow() {
        PaymentProviderStartupValidator validator = createValidator("MONRI", "prod", true);
        assertThatCode(validator::validatePaymentProvider).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("B6: MOCK provider in staging profile does not throw")
    void givenMockProviderInStagingProfile_doesNotThrow() {
        PaymentProviderStartupValidator validator = createValidator("MOCK", "staging", true);
        assertThatCode(validator::validatePaymentProvider).doesNotThrowAnyException();
    }
}
