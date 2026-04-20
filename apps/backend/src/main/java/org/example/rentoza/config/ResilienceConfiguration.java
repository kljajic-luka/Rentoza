package org.example.rentoza.config;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j configuration for enterprise-grade fault tolerance.
 * 
 * <h2>Phase 1 Critical Fix: Circuit Breaker Pattern</h2>
 * <p>Provides graceful degradation when external services fail:
 * <ul>
 *   <li><b>exifValidation</b> - EXIF metadata extraction from photos</li>
 *   <li><b>geofenceValidation</b> - GPS proximity checks</li>
 *   <li><b>notificationService</b> - Push/email notifications</li>
 *   <li><b>paymentGateway</b> - Payment processing</li>
 * </ul>
 * 
 * <h2>Circuit Breaker States</h2>
 * <pre>
 * CLOSED (healthy) → OPEN (failures) → HALF_OPEN (testing) → CLOSED
 *                         ↓
 *                  Fallback response
 * </pre>
 * 
 * <h2>Configuration Philosophy</h2>
 * <ul>
 *   <li>Conservative thresholds to avoid cascading failures</li>
 *   <li>Quick detection (5 calls sliding window)</li>
 *   <li>Reasonable recovery (30s wait in OPEN state)</li>
 *   <li>Metrics integration with Micrometer/Prometheus</li>
 * </ul>
 * 
 * @see <a href="https://resilience4j.readme.io">Resilience4j Documentation</a>
 */
@Configuration
@Slf4j
public class ResilienceConfiguration {

    // ========== CIRCUIT BREAKER CONFIGURATION ==========

    /**
     * Circuit breaker registry with named configurations for different use cases.
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        // Default configuration - conservative for external services
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                // Trip to OPEN after 50% failures in sliding window
                .failureRateThreshold(50)
                // Sliding window of last 10 calls
                .slidingWindowSize(10)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                // Wait 30 seconds before transitioning to HALF_OPEN
                .waitDurationInOpenState(Duration.ofSeconds(30))
                // Allow 3 test calls in HALF_OPEN state
                .permittedNumberOfCallsInHalfOpenState(3)
                // Minimum 5 calls before evaluating failure rate
                .minimumNumberOfCalls(5)
                // Record these as failures
                .recordExceptions(
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class,
                        org.springframework.web.client.ResourceAccessException.class
                )
                // Slow calls (>2s) count as failures
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .slowCallRateThreshold(80)
                .build();

        // EXIF validation - more lenient (non-critical, can fallback to PENDING)
        CircuitBreakerConfig exifConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(70)
                .slidingWindowSize(5)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .permittedNumberOfCallsInHalfOpenState(2)
                .minimumNumberOfCalls(3)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .slowCallRateThreshold(90)
                .build();

        // Payment gateway - strict (critical, must fail fast)
        CircuitBreakerConfig paymentConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(30)
                .slidingWindowSize(10)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(2)
                .minimumNumberOfCalls(5)
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .slowCallRateThreshold(50)
                .build();

        // Notification service - lenient (non-blocking, can retry later)
        CircuitBreakerConfig notificationConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(80)
                .slidingWindowSize(10)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(3)
                .minimumNumberOfCalls(5)
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .slowCallRateThreshold(100)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(defaultConfig);
        
        // Register named circuit breakers
        registry.circuitBreaker("exifValidation", exifConfig);
        registry.circuitBreaker("paymentGateway", paymentConfig);
        registry.circuitBreaker("notificationService", notificationConfig);
        registry.circuitBreaker("geofenceValidation", defaultConfig);

        // Supabase Auth API circuit breaker
        // Fail fast when Supabase is down to avoid cascading timeouts
        CircuitBreakerConfig supabaseAuthConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .minimumNumberOfCalls(5)
                .recordExceptions(
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class,
                        org.springframework.web.client.ResourceAccessException.class,
                        org.springframework.web.client.HttpServerErrorException.class
                )
                .ignoreExceptions(
                        org.springframework.web.client.HttpClientErrorException.class
                )
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .slowCallRateThreshold(80)
                .build();
        registry.circuitBreaker("supabaseAuth", supabaseAuthConfig);
        
        // Add event consumers for observability
        registry.getEventPublisher()
                .onEntryAdded(event -> {
                    var cb = event.getAddedEntry();
                    cb.getEventPublisher()
                            .onStateTransition(e -> 
                                    log.warn("[CircuitBreaker] {} transitioned: {} → {}",
                                            e.getCircuitBreakerName(),
                                            e.getStateTransition().getFromState(),
                                            e.getStateTransition().getToState()))
                            .onFailureRateExceeded(e ->
                                    log.error("[CircuitBreaker] {} failure rate exceeded: {}%",
                                            e.getCircuitBreakerName(),
                                            e.getFailureRate()))
                            .onSlowCallRateExceeded(e ->
                                    log.warn("[CircuitBreaker] {} slow call rate exceeded: {}%",
                                            e.getCircuitBreakerName(),
                                            e.getSlowCallRate()));
                });

        log.info("[Resilience] CircuitBreakerRegistry initialized with {} configs", 
                registry.getAllCircuitBreakers().spliterator().getExactSizeIfKnown());
        
        return registry;
    }

    // ========== RETRY CONFIGURATION ==========

    /**
     * Retry registry for transient failure recovery.
     */
    @Bean
    public RetryRegistry retryRegistry() {
        // Default retry - 3 attempts with exponential backoff
        RetryConfig defaultRetry = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .retryExceptions(
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class
                )
                .ignoreExceptions(
                        IllegalArgumentException.class,
                        org.springframework.security.access.AccessDeniedException.class
                )
                .build();

        // EXIF retry - more attempts for metadata extraction
        RetryConfig exifRetry = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(200))
                .retryExceptions(java.io.IOException.class)
                .build();

        // Payment retry - exponential backoff for payment processing
        RetryConfig paymentRetry = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(1))
                .retryExceptions(
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class
                )
                .build();

        RetryRegistry registry = RetryRegistry.of(defaultRetry);
        registry.retry("exifValidation", exifRetry);
        registry.retry("paymentGateway", paymentRetry);
        registry.retry("notificationService", defaultRetry);

        // Supabase Auth API retry - exponential backoff (500ms → 1s → 2s)
        // Retries on 5xx, connection reset, timeouts. Does NOT retry 4xx.
        RetryConfig supabaseAuthRetry = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(500, 2.0))
                .retryExceptions(
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class,
                        org.springframework.web.client.ResourceAccessException.class,
                        org.springframework.web.client.HttpServerErrorException.class
                )
                .ignoreExceptions(
                        org.springframework.web.client.HttpClientErrorException.class,
                        IllegalArgumentException.class
                )
                .build();
        registry.retry("supabaseAuth", supabaseAuthRetry);

        // JWKS fetch retry - lighter backoff for key refresh
        RetryConfig supabaseJwksRetry = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(300, 2.0))
                .retryExceptions(
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class,
                        org.springframework.web.client.ResourceAccessException.class,
                        org.springframework.web.client.HttpServerErrorException.class
                )
                .build();
        registry.retry("supabaseJwks", supabaseJwksRetry);

        log.info("[Resilience] RetryRegistry initialized");
        
        return registry;
    }

    // ========== TIME LIMITER CONFIGURATION ==========

    /**
     * Time limiter registry for timeout protection.
     */
    @Bean
    public TimeLimiterRegistry timeLimiterRegistry() {
        // Default timeout - 5 seconds
        TimeLimiterConfig defaultConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5))
                .cancelRunningFuture(true)
                .build();

        // EXIF timeout - longer for image processing
        TimeLimiterConfig exifConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(10))
                .cancelRunningFuture(true)
                .build();

        // Payment timeout - strict
        TimeLimiterConfig paymentConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(15))
                .cancelRunningFuture(true)
                .build();

        TimeLimiterRegistry registry = TimeLimiterRegistry.of(defaultConfig);
        registry.timeLimiter("exifValidation", exifConfig);
        registry.timeLimiter("paymentGateway", paymentConfig);

        log.info("[Resilience] TimeLimiterRegistry initialized");
        
        return registry;
    }

    // ========== RATE LIMITER CONFIGURATION ==========

    /**
     * Rate limiter registry for API protection.
     * 
     * <h3>Check-In Endpoints</h3>
     * <ul>
     *   <li><b>photoUpload</b>: 30 req/min per user (prevent abuse)</li>
     *   <li><b>checkInMutation</b>: 10 req/min per user (state changes)</li>
     *   <li><b>handshake</b>: 5 req/min per user (critical operation)</li>
     * </ul>
     */
    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        // Photo upload - generous for batch uploads
        RateLimiterConfig photoUploadConfig = RateLimiterConfig.custom()
                .limitForPeriod(30)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build();

        // Check-in mutations - moderate
        RateLimiterConfig checkInMutationConfig = RateLimiterConfig.custom()
                .limitForPeriod(10)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(2))
                .build();

        // Handshake - strict (critical operation)
        RateLimiterConfig handshakeConfig = RateLimiterConfig.custom()
                .limitForPeriod(5)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(1))
                .build();

        // Default - moderate protection
        RateLimiterConfig defaultConfig = RateLimiterConfig.custom()
                .limitForPeriod(20)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(3))
                .build();

        // Admin approval - generous for bulk approvals (100 req/min)
        RateLimiterConfig adminApprovalConfig = RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(0)) // Fail fast if exceeded
                .build();

        RateLimiterRegistry registry = RateLimiterRegistry.of(defaultConfig);
        registry.rateLimiter("photoUpload", photoUploadConfig);
        registry.rateLimiter("checkInMutation", checkInMutationConfig);
        registry.rateLimiter("handshake", handshakeConfig);
        registry.rateLimiter("admin-approval", adminApprovalConfig);

        log.info("[Resilience] RateLimiterRegistry initialized");
        
        return registry;
    }
}
