package org.example.rentoza.payment;

import org.example.rentoza.payment.PaymentProvider.PaymentRequest;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.example.rentoza.payment.PaymentProvider.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for MockPaymentProvider failure modes.
 * 
 * <p>Verifies that the configurable failure modes work correctly
 * for testing error handling in the payment flow.
 */
@DisplayName("MockPaymentProvider - Failure Mode Tests")
class MockPaymentProviderTest {

    private MockPaymentProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MockPaymentProvider();
        // Set minimal delay for faster tests
        ReflectionTestUtils.setField(provider, "delayMs", 10);
        ReflectionTestUtils.setField(provider, "forceFailure", false);
        ReflectionTestUtils.setField(provider, "failureRate", 0.0);
        ReflectionTestUtils.setField(provider, "failureCode", "CARD_DECLINED");
        ReflectionTestUtils.setField(provider, "simulateTimeout", false);
    }

    @Nested
    @DisplayName("Normal Operation (No Failures)")
    class NormalOperation {

        @Test
        @DisplayName("charge() returns success when no failure mode enabled")
        void chargeSucceedsNormally() {
            PaymentRequest request = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(5000))
                    .currency("RSD")
                    .bookingId(123L)
                    .build();

            PaymentResult result = provider.charge(request);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(result.getTransactionId()).startsWith("txn_");
            assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        }

        @Test
        @DisplayName("authorize() returns authorization ID")
        void authorizeSucceedsNormally() {
            PaymentRequest request = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(10000))
                    .currency("RSD")
                    .bookingId(456L)
                    .build();

            PaymentResult result = provider.authorize(request);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(result.getAuthorizationId()).startsWith("auth_");
        }

        @Test
        @DisplayName("capture() captures authorized amount")
        void captureSucceedsNormally() {
            PaymentResult result = provider.capture("auth_12345678", BigDecimal.valueOf(7500));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(result.getTransactionId()).startsWith("txn_");
        }

        @Test
        @DisplayName("refund() processes refund")
        void refundSucceedsNormally() {
            PaymentResult result = provider.refund("txn_12345678", BigDecimal.valueOf(3000), "Customer request");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(result.getTransactionId()).startsWith("ref_");
        }

        @Test
        @DisplayName("releaseAuthorization() releases hold")
        void releaseAuthorizationSucceedsNormally() {
            PaymentResult result = provider.releaseAuthorization("auth_12345678");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("Force Failure Mode")
    class ForceFailureMode {

        @BeforeEach
        void enableForceFailure() {
            ReflectionTestUtils.setField(provider, "forceFailure", true);
        }

        @Test
        @DisplayName("charge() fails when forceFailure=true")
        void chargeFailsWhenForced() {
            PaymentRequest request = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(5000))
                    .currency("RSD")
                    .bookingId(123L)
                    .build();

            PaymentResult result = provider.charge(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.getErrorCode()).isEqualTo("CARD_DECLINED");
            assertThat(result.getErrorMessage()).contains("odbijena");
        }

        @Test
        @DisplayName("authorize() fails when forceFailure=true")
        void authorizeFailsWhenForced() {
            PaymentRequest request = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(10000))
                    .currency("RSD")
                    .bookingId(456L)
                    .build();

            PaymentResult result = provider.authorize(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("refund() fails when forceFailure=true")
        void refundFailsWhenForced() {
            PaymentResult result = provider.refund("txn_12345678", BigDecimal.valueOf(3000), "Test");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("Custom Failure Codes")
    class CustomFailureCodes {

        @BeforeEach
        void enableForceFailure() {
            ReflectionTestUtils.setField(provider, "forceFailure", true);
        }

        @Test
        @DisplayName("INSUFFICIENT_FUNDS returns correct message")
        void insufficientFundsMessage() {
            ReflectionTestUtils.setField(provider, "failureCode", "INSUFFICIENT_FUNDS");

            PaymentRequest request = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(100000))
                    .currency("RSD")
                    .bookingId(789L)
                    .build();

            PaymentResult result = provider.charge(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(result.getErrorMessage()).contains("Nedovoljno sredstava");
        }

        @Test
        @DisplayName("EXPIRED_CARD returns correct message")
        void expiredCardMessage() {
            ReflectionTestUtils.setField(provider, "failureCode", "EXPIRED_CARD");

            PaymentRequest request = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(5000))
                    .currency("RSD")
                    .bookingId(101L)
                    .build();

            PaymentResult result = provider.charge(request);

            assertThat(result.getErrorCode()).isEqualTo("EXPIRED_CARD");
            assertThat(result.getErrorMessage()).contains("istekla");
        }

        @Test
        @DisplayName("FRAUD_SUSPECTED returns correct message")
        void fraudSuspectedMessage() {
            ReflectionTestUtils.setField(provider, "failureCode", "FRAUD_SUSPECTED");

            PaymentRequest request = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(50000))
                    .currency("RSD")
                    .bookingId(102L)
                    .build();

            PaymentResult result = provider.charge(request);

            assertThat(result.getErrorCode()).isEqualTo("FRAUD_SUSPECTED");
            assertThat(result.getErrorMessage()).contains("sigurnosnih razloga");
        }
    }

    @Nested
    @DisplayName("Random Failure Rate")
    class RandomFailureRate {

        @Test
        @DisplayName("100% failure rate causes all payments to fail")
        void hundredPercentFailureRate() {
            ReflectionTestUtils.setField(provider, "failureRate", 1.0);

            PaymentRequest request = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(5000))
                    .currency("RSD")
                    .bookingId(123L)
                    .build();

            // Run multiple times to ensure consistency
            for (int i = 0; i < 10; i++) {
                PaymentResult result = provider.charge(request);
                assertThat(result.isSuccess()).isFalse();
            }
        }

        @Test
        @DisplayName("0% failure rate causes all payments to succeed")
        void zeroPercentFailureRate() {
            ReflectionTestUtils.setField(provider, "failureRate", 0.0);

            PaymentRequest request = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(5000))
                    .currency("RSD")
                    .bookingId(123L)
                    .build();

            // Run multiple times to ensure consistency
            for (int i = 0; i < 10; i++) {
                PaymentResult result = provider.charge(request);
                assertThat(result.isSuccess()).isTrue();
            }
        }

        @Test
        @DisplayName("50% failure rate produces mixed results")
        void fiftyPercentFailureRate() {
            ReflectionTestUtils.setField(provider, "failureRate", 0.5);

            PaymentRequest request = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(5000))
                    .currency("RSD")
                    .bookingId(123L)
                    .build();

            int successes = 0;
            int failures = 0;
            
            // Run 100 times and check distribution
            for (int i = 0; i < 100; i++) {
                PaymentResult result = provider.charge(request);
                if (result.isSuccess()) {
                    successes++;
                } else {
                    failures++;
                }
            }

            // With 50% rate, we expect roughly 40-60 of each (with some variance)
            assertThat(successes).isBetween(25, 75);
            assertThat(failures).isBetween(25, 75);
        }
    }

    @Nested
    @DisplayName("Provider Metadata")
    class ProviderMetadata {

        @Test
        @DisplayName("getProviderName() returns MOCK")
        void providerNameIsMock() {
            assertThat(provider.getProviderName()).isEqualTo("MOCK");
        }
    }
}
