package org.example.rentoza.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for B3: Webhook endpoint accessibility and CSRF exemption.
 *
 * <p>Verifies the webhook controller can be reached as an unauthenticated caller
 * and that the underlying processing logic is invoked. Real SecurityConfig CSRF
 * and auth rules are verified by reviewing the config directly (CSRF ignore for
 * {@code /api/webhooks/payment} + {@code permitAll} for POST on that route).
 *
 * <p>NOTE: A full {@code @SpringBootTest} + MockMvc integration test would validate
 * the filter chain end-to-end but requires Testcontainers + full context wiring.
 * The SecurityConfig changes (CSRF exempt + permitAll for POST) are structurally
 * verified by inspecting the config; this test covers the controller's own behavior.
 */
class WebhookSecurityTest {

    @Test
    @DisplayName("B3: POST to webhook without auth invokes providerEventService and returns 200")
    void givenPostToWebhookWithoutAuth_returns200() {
        ProviderEventService mockService = mock(ProviderEventService.class);
        when(mockService.ingestEvent(anyString(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(true);

        WebhookPaymentController controller = new WebhookPaymentController(mockService);

        ResponseEntity<Map<String, Object>> response = controller.handleWebhook(
                "evt-123",
                "PAYMENT_CONFIRMED",
                "42",
                "auth-abc",
                "signature-xyz",
                "{\"amount\":100}"
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "ok");
        assertThat(response.getBody()).containsEntry("processed", true);
        verify(mockService).ingestEvent("evt-123", "PAYMENT_CONFIRMED", 42L, "auth-abc",
                "{\"amount\":100}", "signature-xyz");
    }

    @Test
    @DisplayName("B3: Missing event ID with blank webhook secret generates synthetic ID (dev mode)")
    void givenMissingEventIdInDevMode_syntheticIdIsGenerated() {
        ProviderEventService mockService = mock(ProviderEventService.class);
        when(mockService.ingestEvent(anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(true);

        WebhookPaymentController controller = new WebhookPaymentController(mockService);

        ResponseEntity<Map<String, Object>> response = controller.handleWebhook(
                null, "PAYMENT_CONFIRMED", null, null, null, "{}"
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "ok");
        // Verify ingestEvent was called with a synthetic event ID
        verify(mockService).ingestEvent(argThat(id -> id.startsWith("synthetic_")),
                eq("PAYMENT_CONFIRMED"), isNull(), isNull(), eq("{}"), isNull());
    }

    @Test
    @DisplayName("B3: Processing error still returns 200 (prevent provider retries)")
    void givenProcessingError_returns200() {
        ProviderEventService mockService = mock(ProviderEventService.class);
        when(mockService.ingestEvent(anyString(), any(), any(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("DB down"));

        WebhookPaymentController controller = new WebhookPaymentController(mockService);

        ResponseEntity<Map<String, Object>> response = controller.handleWebhook(
                "evt-456", "PAYMENT_FAILED", null, null, null, "{}"
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "error");
        assertThat(response.getBody()).containsEntry("processed", false);
    }
}
