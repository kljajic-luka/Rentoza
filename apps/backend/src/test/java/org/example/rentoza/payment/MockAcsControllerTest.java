package org.example.rentoza.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MockAcsController callback redirect tests")
class MockAcsControllerTest {

    @Mock
    private MockPaymentProvider mockPaymentProvider;

    @Mock
    private ProviderEventService providerEventService;

    private MockAcsController controller;

    @BeforeEach
    void setUp() {
        controller = new MockAcsController(mockPaymentProvider, providerEventService);
        ReflectionTestUtils.setField(controller, "frontendUrl", "https://staging.rentoza.rs");
        lenient().when(mockPaymentProvider.computeHmac(any(String.class))).thenReturn("mock-signature");
        lenient().when(providerEventService.ingestEvent(any(), any(), any(), any(), any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("complete approve redirects to payment-return success callback")
    void completeApprove_redirectsToPaymentReturnSuccess() {
        when(mockPaymentProvider.loadScaSession("sca-token"))
                .thenReturn(new MockScaSession("sca-token", 42L, "mock_auth_abc"));

        ResponseEntity<Void> response = controller.complete("sca-token", "approve");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("https://staging.rentoza.rs/bookings/payment-return?bookingId=42&status=success");
    }

    @Test
    @DisplayName("complete decline redirects to payment-return failed callback")
    void completeDecline_redirectsToPaymentReturnFailed() {
        when(mockPaymentProvider.loadScaSession("sca-token"))
                .thenReturn(new MockScaSession("sca-token", 42L, "mock_auth_abc"));

        ResponseEntity<Void> response = controller.complete("sca-token", "decline");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("https://staging.rentoza.rs/bookings/payment-return?bookingId=42&status=failed");
    }

    @Test
    @DisplayName("complete returns 404 for unknown token")
    void complete_unknownToken_returnsNotFound() {
        when(mockPaymentProvider.loadScaSession("missing")).thenReturn(null);

        ResponseEntity<Void> response = controller.complete("missing", "approve");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
