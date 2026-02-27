package org.example.rentoza.payment;

import org.example.rentoza.booking.BookingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for B5: ProviderEventService fail-fast on missing webhook HMAC secret in production.
 */
@ExtendWith(MockitoExtension.class)
class ProviderEventServiceWebhookSecretTest {

    @Mock
    private ProviderEventRepository eventRepository;

    @Mock
    private PaymentTransactionRepository txRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private PayoutLedgerRepository payoutLedgerRepository;

    private ProviderEventService createService(String webhookSecret, String activeProfile) {
        ProviderEventService service = new ProviderEventService(eventRepository, txRepository, bookingRepository, payoutLedgerRepository);
        ReflectionTestUtils.setField(service, "webhookSecret", webhookSecret);
        ReflectionTestUtils.setField(service, "activeProfile", activeProfile);
        return service;
    }

    @Test
    @DisplayName("B5: Blank webhook secret in prod profile throws IllegalStateException")
    void givenBlankSecretInProdProfile_throwsIllegalStateException() {
        ProviderEventService service = createService("", "prod");
        assertThatThrownBy(service::validateWebhookSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_WEBHOOK_SECRET");
    }

    @Test
    @DisplayName("B5: Null webhook secret in prod profile throws IllegalStateException")
    void givenNullSecretInProdProfile_throwsIllegalStateException() {
        ProviderEventService service = createService(null, "prod");
        assertThatThrownBy(service::validateWebhookSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_WEBHOOK_SECRET");
    }

    @Test
    @DisplayName("B5: Dev mock value in 'production' profile throws IllegalStateException")
    void givenDevMockSecretInProductionProfile_throwsIllegalStateException() {
        ProviderEventService service = createService("dev-mock-webhook-secret-not-for-production", "production");
        assertThatThrownBy(service::validateWebhookSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev mock value");
    }

    @Test
    @DisplayName("B5: Blank webhook secret in dev profile does not throw")
    void givenBlankSecretInDevProfile_doesNotThrow() {
        ProviderEventService service = createService("", "dev");
        assertThatCode(service::validateWebhookSecret).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("B5: Dev mock value in dev profile does not throw")
    void givenDevMockSecretInDevProfile_doesNotThrow() {
        ProviderEventService service = createService("dev-mock-webhook-secret-not-for-production", "dev");
        assertThatCode(service::validateWebhookSecret).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("B5: Real secret in prod profile does not throw")
    void givenRealSecretInProdProfile_doesNotThrow() {
        ProviderEventService service = createService("a-real-production-hmac-secret-value", "prod");
        assertThatCode(service::validateWebhookSecret).doesNotThrowAnyException();
    }
}
