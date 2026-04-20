package org.example.rentoza.security.supabase;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.example.rentoza.security.supabase.SupabaseAuthClient.SupabaseAuthException;

/**
 * Integration test proving the supabaseAuth circuit breaker transitions
 * CLOSED → OPEN under sustained failure, and that OPEN state causes
 * fast-fail with the expected SupabaseAuthException.
 *
 * <p>Uses the same CB configuration as {@code ResilienceConfiguration}
 * (minimumNumberOfCalls=5, failureRateThreshold=50%, COUNT_BASED window=10).
 * Retry is disabled to keep the test deterministic.
 */
class SupabaseAuthClientCircuitBreakerTest {

    private SupabaseAuthClient client;
    private CircuitBreakerRegistry cbRegistry;

    @BeforeEach
    void setUp() {
        // Mirror production circuit breaker config from ResilienceConfiguration
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .waitDurationInOpenState(Duration.ofSeconds(60))     // Long wait so it stays OPEN for the test
                .permittedNumberOfCallsInHalfOpenState(3)
                .minimumNumberOfCalls(5)
                .recordExceptions(
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class,
                        ResourceAccessException.class,
                        org.springframework.web.client.HttpServerErrorException.class
                )
                .ignoreExceptions(
                        org.springframework.web.client.HttpClientErrorException.class
                )
                .build();

        cbRegistry = CircuitBreakerRegistry.of(cbConfig);

        // No retries — we want each call to count as one CB call
        RetryConfig noRetry = RetryConfig.custom()
                .maxAttempts(1)
                .build();
        RetryRegistry retryRegistry = RetryRegistry.of(noRetry);
        retryRegistry.retry("supabaseAuth", noRetry);

        // Build client with dummy Supabase coordinates (calls will fail before reaching the network)
        client = new SupabaseAuthClient(
                "https://fake.supabase.co",
                "fake-anon-key",
                "fake-service-role-key",
                500,   // connect timeout
                500,   // read timeout
                retryRegistry,
                cbRegistry
        );
    }

    @Nested
    @DisplayName("Circuit Breaker State Transitions")
    class StateTransitions {

        @Test
        @DisplayName("CB starts in CLOSED state")
        void startsInClosed() {
            CircuitBreaker cb = cbRegistry.circuitBreaker("supabaseAuth");
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("CB transitions CLOSED → OPEN after sustained failures exceeding threshold")
        void transitionsToOpenAfterSustainedFailures() {
            CircuitBreaker cb = cbRegistry.circuitBreaker("supabaseAuth");

            // Drive 5 failures (minimumNumberOfCalls=5, threshold=50%)
            // All 5 fail → failure rate = 100% → trips the breaker
            for (int i = 0; i < 5; i++) {
                try {
                    // signUp will hit fake URL → ResourceAccessException (recorded by CB)
                    client.signUp("fail-" + i + "@test.com", "password123");
                } catch (Exception e) {
                    // Expected — network error or CB open
                }
            }

            assertThat(cb.getState())
                    .as("Circuit breaker should be OPEN after 5 consecutive failures")
                    .isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("OPEN CB fast-fails with SupabaseAuthException containing unavailable message")
        void openCbFastFailsWithCorrectException() {
            CircuitBreaker cb = cbRegistry.circuitBreaker("supabaseAuth");

            // Trip the breaker
            for (int i = 0; i < 5; i++) {
                try {
                    client.signUp("trip-" + i + "@test.com", "password123");
                } catch (Exception ignored) { }
            }

            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // Next call should fast-fail without network access
            assertThatThrownBy(() -> client.signUp("blocked@test.com", "password123"))
                    .isInstanceOf(SupabaseAuthException.class)
                    .hasMessageContaining("temporarily unavailable");
        }

        @Test
        @DisplayName("CB stays CLOSED when failures are below threshold")
        void staysClosedBelowThreshold() {
            CircuitBreaker cb = cbRegistry.circuitBreaker("supabaseAuth");

            // Record 2 successes manually to dilute failure rate
            cb.onSuccess(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            cb.onSuccess(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            cb.onSuccess(100, java.util.concurrent.TimeUnit.MILLISECONDS);

            // Drive 2 failures  → 2/5 = 40% < 50% threshold → stays CLOSED
            for (int i = 0; i < 2; i++) {
                try {
                    client.signUp("partial-" + i + "@test.com", "password123");
                } catch (Exception ignored) { }
            }

            assertThat(cb.getState())
                    .as("CB should remain CLOSED when failure rate (40%) is below threshold (50%)")
                    .isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    @Nested
    @DisplayName("Metrics & Observability")
    class Metrics {

        @Test
        @DisplayName("CB metrics reflect recorded failures")
        void metricsReflectFailures() {
            CircuitBreaker cb = cbRegistry.circuitBreaker("supabaseAuth");

            for (int i = 0; i < 5; i++) {
                try {
                    client.signUp("metric-" + i + "@test.com", "password123");
                } catch (Exception ignored) { }
            }

            CircuitBreaker.Metrics metrics = cb.getMetrics();
            assertThat(metrics.getNumberOfFailedCalls())
                    .as("All 5 calls should be recorded as failures")
                    .isGreaterThanOrEqualTo(5);
            assertThat(metrics.getFailureRate())
                    .as("Failure rate should be 100%")
                    .isEqualTo(100.0f);
        }
    }
}
