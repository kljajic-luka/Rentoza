package org.example.rentoza.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for B6: PaymentProviderStartupValidator prevents MOCK provider in production.
 */
class PaymentProviderStartupValidatorTest {

    private PaymentProviderStartupValidator createValidator(String provider, String profile) {
        PaymentProviderStartupValidator validator = new PaymentProviderStartupValidator();
        ReflectionTestUtils.setField(validator, "paymentProvider", provider);
        ReflectionTestUtils.setField(validator, "activeProfile", profile);
        return validator;
    }

    @Test
    @DisplayName("B6: MOCK provider in prod profile throws IllegalStateException")
    void givenMockProviderInProdProfile_throwsIllegalStateException() {
        PaymentProviderStartupValidator validator = createValidator("MOCK", "prod");
        assertThatThrownBy(validator::validatePaymentProvider)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOCK")
                .hasMessageContaining("production");
    }

    @Test
    @DisplayName("B6: MOCK provider (lowercase) in production profile throws")
    void givenMockLowercaseInProductionProfile_throwsIllegalStateException() {
        PaymentProviderStartupValidator validator = createValidator("mock", "production");
        assertThatThrownBy(validator::validatePaymentProvider)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOCK");
    }

    @Test
    @DisplayName("B6: MOCK provider in dev profile does not throw")
    void givenMockProviderInDevProfile_doesNotThrow() {
        PaymentProviderStartupValidator validator = createValidator("MOCK", "dev");
        assertThatCode(validator::validatePaymentProvider).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("B6: MONRI provider in prod profile does not throw")
    void givenMonriProviderInProdProfile_doesNotThrow() {
        PaymentProviderStartupValidator validator = createValidator("MONRI", "prod");
        assertThatCode(validator::validatePaymentProvider).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("B6: MOCK provider in staging profile does not throw")
    void givenMockProviderInStagingProfile_doesNotThrow() {
        PaymentProviderStartupValidator validator = createValidator("MOCK", "staging");
        assertThatCode(validator::validatePaymentProvider).doesNotThrowAnyException();
    }
}
