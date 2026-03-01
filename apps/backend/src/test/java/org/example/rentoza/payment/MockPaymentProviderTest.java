package org.example.rentoza.payment;

import org.example.rentoza.payment.PaymentProvider.PaymentRequest;
import org.example.rentoza.payment.PaymentProvider.ProviderOutcome;
import org.example.rentoza.payment.PaymentProvider.ProviderResult;
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

            ProviderResult result = provider.charge(request, "test_charge_ikey");

            assertThat(result.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
            assertThat(result.getProviderTransactionId()).startsWith("mock_txn_");
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

            ProviderResult result = provider.authorize(request, "test_auth_ikey");

            assertThat(result.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
            assertThat(result.getProviderAuthorizationId()).startsWith("mock_auth_");
        }

        @Test
        @DisplayName("capture() captures authorized amount")
        void captureSucceedsNormally() {
            // Must first authorize to get a valid auth ID in the mock's internal map
            PaymentRequest authRequest = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(7500))
                    .currency("RSD")
                    .bookingId(789L)
                    .build();
            ProviderResult authorized = provider.authorize(authRequest, "test_auth_ikey_2");
            assertThat(authorized.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
            String authId = authorized.getProviderAuthorizationId();

            ProviderResult result = provider.capture(authId, BigDecimal.valueOf(7500), "test_cap_ikey");

            assertThat(result.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
            assertThat(result.getProviderTransactionId()).startsWith("mock_txn_");
        }

        @Test
        @DisplayName("refund() processes refund")
        void refundSucceedsNormally() {
            // Must first authorize+capture to get a valid captured-transaction ID
            PaymentRequest authRequest = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(3000))
                    .currency("RSD")
                    .bookingId(111L)
                    .build();
            String authId = provider.authorize(authRequest, "test_auth_ikey_3").getProviderAuthorizationId();
            String txnId = provider.capture(authId, BigDecimal.valueOf(3000), "test_cap_ikey_2").getProviderTransactionId();

            ProviderResult result = provider.refund(txnId, BigDecimal.valueOf(3000), "Customer request", "test_ref_ikey");

            assertThat(result.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
            assertThat(result.getProviderRefundId()).startsWith("mock_ref_");
        }

        @Test
        @DisplayName("releaseAuthorization() releases hold")
        void releaseAuthorizationSucceedsNormally() {
            // Must first authorize to get a valid auth ID in the mock's internal map
            PaymentRequest authRequest = PaymentRequest.builder()
                    .amount(BigDecimal.valueOf(5000))
                    .currency("RSD")
                    .bookingId(222L)
                    .build();
            String authId = provider.authorize(authRequest, "test_auth_ikey_4").getProviderAuthorizationId();

            ProviderResult result = provider.releaseAuthorization(authId, "test_release_ikey");

            assertThat(result.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
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

            ProviderResult result = provider.charge(request, "test_charge_fail_ikey");

            assertThat(result.getOutcome()).isNotEqualTo(ProviderOutcome.SUCCESS);
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

            ProviderResult result = provider.authorize(request, "test_auth_fail_ikey");

            assertThat(result.getOutcome()).isNotEqualTo(ProviderOutcome.SUCCESS);
        }

        @Test
        @DisplayName("refund() fails when forceFailure=true")
        void refundFailsWhenForced() {
            ProviderResult result = provider.refund("txn_12345678", BigDecimal.valueOf(3000), "Test", "test_ref_fail_ikey");

            assertThat(result.getOutcome()).isNotEqualTo(ProviderOutcome.SUCCESS);
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

            ProviderResult result = provider.charge(request, "test_insuff_ikey");

            assertThat(result.getOutcome()).isNotEqualTo(ProviderOutcome.SUCCESS);
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

            ProviderResult result = provider.charge(request, "test_expired_ikey");

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

            ProviderResult result = provider.charge(request, "test_fraud_ikey");

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
                ProviderResult result = provider.charge(request, "test_rate100_ikey_" + i);
                assertThat(result.getOutcome()).isNotEqualTo(ProviderOutcome.SUCCESS);
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
                ProviderResult result = provider.charge(request, "test_rate0_ikey_" + i);
                assertThat(result.getOutcome()).isEqualTo(ProviderOutcome.SUCCESS);
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
                ProviderResult result = provider.charge(request, "test_rate50_ikey_" + i);
                if (result.getOutcome() == ProviderOutcome.SUCCESS) {
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
