package org.example.rentoza.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.rentoza.payment.PaymentProvider.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.*;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MonriPaymentProvider}.
 *
 * <p>Verifies H-1/H-2 (Monri provider implementation) and H-5 (response code classification).
 */
@ExtendWith(MockitoExtension.class)
class MonriPaymentProviderTest {

    @Mock RestTemplate restTemplate;
    @InjectMocks MonriPaymentProvider provider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // Inject configuration values via reflection
        setField("apiUrl", "https://ipgtest.monri.com");
        setField("merchantKey", "test-merchant-key");
        setField("authenticityToken", "test-auth-token");
        setField("authExpiryHours", 120);

        // Inject real ObjectMapper since @InjectMocks doesn't cover it
        var omField = MonriPaymentProvider.class.getDeclaredField("objectMapper");
        omField.setAccessible(true);
        omField.set(provider, objectMapper);
    }

    private void setField(String name, Object value) throws Exception {
        var field = MonriPaymentProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(provider, value);
    }

    // ── Authorization Tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("Authorize success returns SUCCESS with authorizationId and expiry")
    void authorizeSuccess() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "approved");
        body.put("id", "auth_12345");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        ProviderResult result = provider.authorize(
                PaymentRequest.builder().bookingId(1L).amount(BigDecimal.valueOf(5000))
                        .currency("RSD").paymentMethodId("tok_visa").build(),
                "pay_auth_1_booking");

        assertThat(result.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
        assertThat(result.getProviderAuthorizationId()).isEqualTo("auth_12345");
        assertThat(result.getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("Authorize with 3DS2 redirect returns REDIRECT_REQUIRED with providerAuthorizationId (H6)")
    void authorizeRedirectRequired() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "action_required");
        body.put("acs_url", "https://bank.example.com/3ds2");
        body.put("authenticity_token", "session_abc");
        body.put("id", "pending_auth_777");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        ProviderResult result = provider.authorize(
                PaymentRequest.builder().bookingId(1L).amount(BigDecimal.valueOf(5000)).build(),
                "pay_auth_1_booking");

        assertThat(result.getOutcome()).isEqualTo(ProviderOutcome.REDIRECT_REQUIRED);
        assertThat(result.getRedirectUrl()).isEqualTo("https://bank.example.com/3ds2");
        assertThat(result.getProviderAuthorizationId()).isEqualTo("pending_auth_777");
    }

    @Test
    @DisplayName("Authorize card declined returns TERMINAL_FAILURE")
    void authorizeCardDeclined() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "declined");
        body.put("response_code", "card_declined");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        ProviderResult result = provider.authorize(
                PaymentRequest.builder().bookingId(1L).amount(BigDecimal.valueOf(5000)).build(),
                "pay_auth_1_booking");

        assertThat(result.getOutcome()).isEqualTo(ProviderOutcome.TERMINAL_FAILURE);
        assertThat(result.getErrorCode()).isEqualTo("CARD_DECLINED");
    }

    // ── Capture Tests ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Capture success returns captureSuccess")
    void captureSuccess() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "captured");
        body.put("id", "txn_67890");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        ProviderResult result = provider.capture("auth_12345", BigDecimal.valueOf(5000), "pay_cap_1_booking_r1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProviderTransactionId()).isEqualTo("txn_67890");
    }

    // ── Refund Tests ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Refund success returns refundSuccess")
    void refundSuccess() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "refunded");
        body.put("id", "ref_abc");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        ProviderResult result = provider.refund("txn_67890", BigDecimal.valueOf(5000),
                "Guest cancellation", "pay_refund_1_cancel_r1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProviderRefundId()).isEqualTo("ref_abc");
    }

    // ── Release Tests ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Release (void) success")
    void releaseSuccess() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "voided");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        ProviderResult result = provider.releaseAuthorization("auth_12345", "pay_release_1_booking_r1");
        assertThat(result.isSuccess()).isTrue();
    }

    // ── Payout Tests (H-8) ──────────────────────────────────────────────────

    @Test
    @DisplayName("Payout returns PENDING for async bank transfer")
    void payoutPending() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "pending");
        body.put("id", "pout_999");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        ProviderResult result = provider.payout(
                PaymentRequest.builder().bookingId(1L).userId(42L)
                        .amount(BigDecimal.valueOf(4250)).currency("RSD")
                        .recipientId("monri_rcpt_test").build(),
                "pay_payout_42_1_host_r1");

        assertThat(result.isPending()).isTrue();
        assertThat(result.getProviderTransactionId()).isEqualTo("pout_999");
    }

    @Test
    @DisplayName("Payout immediate success (rare)")
    void payoutImmediateSuccess() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "completed");
        body.put("id", "pout_888");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        ProviderResult result = provider.payout(
                PaymentRequest.builder().bookingId(1L).userId(42L)
                        .amount(BigDecimal.valueOf(4250))
                        .recipientId("monri_rcpt_test").build(),
                "pay_payout_42_1_host_r1");

        assertThat(result.isSuccess()).isTrue();
    }

    // ── Error Handling Tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("Network error returns RETRYABLE_FAILURE")
    void networkErrorRetryable() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        ProviderResult result = provider.authorize(
                PaymentRequest.builder().bookingId(1L).amount(BigDecimal.valueOf(5000)).build(),
                "pay_auth_1_booking");

        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getErrorCode()).isEqualTo("NETWORK_ERROR");
    }

    @Test
    @DisplayName("HTTP 500 returns RETRYABLE_FAILURE")
    void serverErrorRetryable() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        ProviderResult result = provider.capture("auth_1", BigDecimal.valueOf(5000), "key");

        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getErrorCode()).isEqualTo("GATEWAY_SERVER_ERROR");
    }

    @Test
    @DisplayName("HTTP 429 Rate Limited returns RETRYABLE_FAILURE")
    void rateLimitedRetryable() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));

        ProviderResult result = provider.authorize(
                PaymentRequest.builder().bookingId(1L).amount(BigDecimal.valueOf(5000)).build(),
                "key");

        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getErrorCode()).isEqualTo("RATE_LIMITED");
    }

    @Test
    @DisplayName("HTTP 400 Bad Request returns TERMINAL_FAILURE")
    void badRequestTerminal() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        ProviderResult result = provider.authorize(
                PaymentRequest.builder().bookingId(1L).amount(BigDecimal.valueOf(5000)).build(),
                "key");

        assertThat(result.isTerminalFailure()).isTrue();
        assertThat(result.getErrorCode()).isEqualTo("HTTP_400");
    }

    @Test
    @DisplayName("Empty response body returns RETRYABLE_FAILURE")
    void emptyResponseRetryable() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(null));

        ProviderResult result = provider.authorize(
                PaymentRequest.builder().bookingId(1L).amount(BigDecimal.valueOf(5000)).build(),
                "key");

        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getErrorCode()).isEqualTo("EMPTY_RESPONSE");
    }

    @Test
    @DisplayName("Provider returns correct name")
    void providerName() {
        assertThat(provider.getProviderName()).isEqualTo("MONRI");
    }

    // ── Idempotency Key in Headers ──────────────────────────────────────────

    @Test
    @DisplayName("Idempotency key is sent in X-Idempotency-Key header")
    void idempotencyKeyInHeader() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "approved");
        body.put("id", "auth_1");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        provider.authorize(
                PaymentRequest.builder().bookingId(1L).amount(BigDecimal.valueOf(5000)).build(),
                "my_idem_key");

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                argThat(entity -> {
                    HttpHeaders headers = entity.getHeaders();
                    return "my_idem_key".equals(headers.getFirst("X-Idempotency-Key"));
                }),
                eq(JsonNode.class));
    }

    @Test
    @DisplayName("H7: Authorization header uses WP3-v2.1 with digest (not plain WP3-v2)")
    void authorizationHeaderUsesV21WithDigest() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "approved");
        body.put("id", "auth_1");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        provider.authorize(
                PaymentRequest.builder().bookingId(1L).amount(BigDecimal.valueOf(5000)).build(),
                "test_key");

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                argThat(entity -> {
                    String auth = entity.getHeaders().getFirst("Authorization");
                    // Must start with WP3-v2.1, include authenticityToken, timestamp, and hex digest
                    return auth != null
                            && auth.startsWith("WP3-v2.1 test-auth-token ")
                            && auth.split(" ").length == 4
                            && auth.split(" ")[3].matches("[0-9a-f]{128}"); // SHA-512 hex = 128 chars
                }),
                eq(JsonNode.class));
    }

    // ── Response Code Classification (H-5 contract) ─────────────────────────

    @Test
    @DisplayName("Unknown response codes default to RETRYABLE_FAILURE (safe default)")
    void unknownCodeRetryable() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "unknown_new_status");
        body.put("response_code", "some_future_code");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        ProviderResult result = provider.authorize(
                PaymentRequest.builder().bookingId(1L).amount(BigDecimal.valueOf(5000)).build(),
                "key");

        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("Insufficient funds is terminal failure")
    void insufficientFundsTerminal() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "declined");
        body.put("response_code", "insufficient_funds");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        ProviderResult result = provider.authorize(
                PaymentRequest.builder().bookingId(1L).amount(BigDecimal.valueOf(5000)).build(),
                "key");

        assertThat(result.isTerminalFailure()).isTrue();
        assertThat(result.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

        @Test
        @DisplayName("H5: Blank merchant key in MONRI mode throws at startup")
        void blankMerchantKeyInMonriMode_throwsAtStartup() throws Exception {
                setField("merchantKey", "");

                assertThatThrownBy(() -> provider.validateCredentials())
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("MONRI_MERCHANT_KEY");
        }

        @Test
        @DisplayName("H5: Blank authenticity token in MONRI mode throws at startup")
        void blankAuthenticityTokenInMonriMode_throwsAtStartup() throws Exception {
                setField("authenticityToken", "");

                assertThatThrownBy(() -> provider.validateCredentials())
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("MONRI_AUTHENTICITY_TOKEN");
        }
}
