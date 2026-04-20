package org.example.rentoza.payment;

import org.example.rentoza.security.JwtUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock private BookingPaymentService bookingPaymentService;

    private PaymentController controller;
    private JwtUserPrincipal principal;

    @BeforeEach
    void setUp() {
        controller = new PaymentController(bookingPaymentService);
        principal = JwtUserPrincipal.create(10L, "renter@test.com", java.util.List.of("USER"));
    }

    @Test
    @DisplayName("returns 202 when reauth requires redirect")
    void redirect_required_returns_202() {
        when(bookingPaymentService.reauthorizeBookingPayment(anyLong(), anyString()))
                .thenReturn(PaymentProvider.PaymentResult.builder()
                        .success(false)
                        .status(PaymentProvider.PaymentStatus.REDIRECT_REQUIRED)
                        .redirectUrl("https://redirect")
                        .build());

        ResponseEntity<Map<String, Object>> response = controller.reauthorizeBookingPayment(
                1L, new PaymentController.ReauthorizeRequest("pm_1"), principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "REDIRECT_REQUIRED");
    }

    @Test
    @DisplayName("returns 422 on logical payment failure")
    void failure_returns_422() {
        when(bookingPaymentService.reauthorizeBookingPayment(anyLong(), anyString()))
                .thenReturn(PaymentProvider.PaymentResult.builder()
                        .success(false)
                        .status(PaymentProvider.PaymentStatus.FAILED)
                        .errorCode("CARD_DECLINED")
                        .errorMessage("declined")
                        .build());

        ResponseEntity<Map<String, Object>> response = controller.reauthorizeBookingPayment(
                1L, new PaymentController.ReauthorizeRequest("pm_1"), principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("errorCode", "CARD_DECLINED");
    }
}
