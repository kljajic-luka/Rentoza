package org.example.rentoza.security.ratelimit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Production-ready Redis-backed rate limiting service.
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>Atomic Lua script for thread-safe counter operations</li>
 *   <li>Circuit breaker for Redis failure isolation</li>
 *   <li>Micrometer metrics for observability</li>
 *   <li>Fail-open strategy for graceful degradation</li>
 *   <li>SCAN-based cleanup (non-blocking)</li>
 * </ul>
 * 
 * <h2>Circuit Breaker States</h2>
 * <pre>
 * CLOSED (normal)     → Redis healthy, all requests checked
 * OPEN (tripped)      → Redis failing, requests bypass rate limiting (fail-open)
 * HALF_OPEN (testing) → Trying Redis again, 3 probe requests
 * </pre>
 * 
 * <h2>Metrics Exposed</h2>
 * <ul>
 *   <li>rate_limit.allowed - Requests that passed rate limiting</li>
 *   <li>rate_limit.blocked - Requests blocked by rate limiting</li>
 *   <li>rate_limit.redis.latency - Redis operation latency</li>
 *   <li>rate_limit.redis.errors - Redis operation errors</li>
 *   <li>rate_limit.circuit.state - Current circuit breaker state</li>
 * </ul>
 * 
 * @author Rentoza Platform Team
 * @since Phase 3.0 - Production Redis Hardening
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisRateLimitService implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitService.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final String keyPrefix;
    private final CircuitBreaker circuitBreaker;

    // ========== METRICS ==========
    private final Counter allowedCounter;
    private final Counter blockedCounter;
    private final Counter redisErrorCounter;
    private final Counter circuitOpenBypassCounter;
    private final Counter criticalFailClosedCounter;
    private final Counter standardFailOpenCounter;
    private final Timer redisLatencyTimer;
    private final AtomicLong circuitStateGauge;

    /**
     * Lua script for atomic increment with TTL.
     * 
     * ATOMICITY: This script runs as a single Redis operation.
     * Even under high concurrency, the counter will be accurate.
     * 
     * Logic:
     * 1. INCR the key (creates with value 1 if not exists)
     * 2. If this is a new key (count == 1), set TTL
     * 3. Return current count
     */
    private static final String INCREMENT_SCRIPT = 
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current == 1 then " +
            "    redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
            "end " +
            "return current";

    private final DefaultRedisScript<Long> incrementScript;

    public RedisRateLimitService(
            RedisTemplate<String, String> redisTemplate,
            MeterRegistry meterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${app.rate-limit.redis-key-prefix:rate_limit}") String keyPrefix) {
        
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;

        // Initialize Lua script
        this.incrementScript = new DefaultRedisScript<>();
        this.incrementScript.setScriptText(INCREMENT_SCRIPT);
        this.incrementScript.setResultType(Long.class);

        // ========== CIRCUIT BREAKER SETUP ==========
        // Get or create circuit breaker with production-ready config
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)                          // Last 10 calls
                .minimumNumberOfCalls(5)                        // Need 5 calls before evaluating
                .failureRateThreshold(50)                       // Open at 50% failure rate
                .slowCallRateThreshold(80)                      // Open at 80% slow call rate
                .slowCallDurationThreshold(Duration.ofMillis(500))  // Slow = > 500ms
                .waitDurationInOpenState(Duration.ofSeconds(30))    // Stay open for 30s
                .permittedNumberOfCallsInHalfOpenState(3)           // Allow 3 test calls
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(Exception.class)
                .ignoreExceptions()  // Don't ignore any exceptions
                .build();

        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("redis-rate-limit", cbConfig);

        // Log circuit breaker state changes
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("🔌 Redis Circuit Breaker: {} → {}", 
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));

        // ========== METRICS SETUP ==========
        this.allowedCounter = Counter.builder("rate_limit.allowed")
                .description("Requests that passed rate limiting")
                .tag("service", "rate-limit")
                .register(meterRegistry);

        this.blockedCounter = Counter.builder("rate_limit.blocked")
                .description("Requests blocked by rate limiting")
                .tag("service", "rate-limit")
                .register(meterRegistry);

        this.redisErrorCounter = Counter.builder("rate_limit.redis.errors")
                .description("Redis operation errors")
                .tag("service", "rate-limit")
                .register(meterRegistry);

        this.circuitOpenBypassCounter = Counter.builder("rate_limit.circuit.bypass")
                .description("Requests bypassed due to circuit breaker open")
                .tag("service", "rate-limit")
                .register(meterRegistry);

        this.criticalFailClosedCounter = Counter.builder("rate_limit.redis.failure.critical")
                .description("CRITICAL-tier requests blocked due to Redis failure (fail-closed)")
                .tag("service", "rate-limit")
                .register(meterRegistry);

        this.standardFailOpenCounter = Counter.builder("rate_limit.redis.failure.standard")
                .description("STANDARD-tier requests allowed despite Redis failure (fail-open)")
                .tag("service", "rate-limit")
                .register(meterRegistry);

        this.redisLatencyTimer = Timer.builder("rate_limit.redis.latency")
                .description("Redis operation latency")
                .tag("service", "rate-limit")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        // Gauge for circuit breaker state (0=closed, 1=open, 2=half-open)
        this.circuitStateGauge = new AtomicLong(0);
        Gauge.builder("rate_limit.circuit.state", circuitStateGauge, AtomicLong::get)
                .description("Circuit breaker state (0=closed, 1=open, 2=half-open)")
                .tag("service", "rate-limit")
                .register(meterRegistry);

        log.info("✅ RedisRateLimitService initialized with key prefix: {}", keyPrefix);
        log.info("   ✅ Circuit breaker: enabled");
        log.info("   ✅ Metrics: enabled");
    }

    @Override
    public boolean allowRequest(String key, int limit, int windowSeconds) {
        String redisKey = buildRedisKey(key, windowSeconds);

        // Update circuit state gauge
        updateCircuitStateGauge();

        // Use circuit breaker to protect against Redis failures
        Supplier<Boolean> redisCheck = () -> executeRateLimitCheck(redisKey, limit, windowSeconds);

        try {
            boolean allowed = circuitBreaker.executeSupplier(redisCheck);

            // Record metrics
            if (allowed) {
                allowedCounter.increment();
            } else {
                blockedCounter.increment();
            }

            return allowed;

        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            // Circuit is OPEN - fail open (allow request) for backward-compatible callers
            circuitOpenBypassCounter.increment();
            log.debug("Circuit breaker OPEN - bypassing rate limit for key: {}", key);
            return true;

        } catch (Exception e) {
            // Unexpected exception - fail open for backward-compatible callers
            redisErrorCounter.increment();
            log.error("Unexpected error in rate limit check for key: {}. Failing OPEN. Error: {}",
                    key, e.getMessage());
            return true;
        }
    }

    /**
     * Tier-aware rate limit check.
     *
     * <p>{@link RateLimitTier#CRITICAL} endpoints (auth, payment, booking mutations)
     * return {@code false} when Redis is unavailable, causing the caller to respond 503.
     * {@link RateLimitTier#STANDARD} endpoints preserve the existing fail-open behavior.
     */
    @Override
    public boolean allowRequest(String key, int limit, int windowSeconds, RateLimitTier tier) {
        String redisKey = buildRedisKey(key, windowSeconds);
        updateCircuitStateGauge();

        Supplier<Boolean> redisCheck = () -> executeRateLimitCheck(redisKey, limit, windowSeconds);

        try {
            boolean allowed = circuitBreaker.executeSupplier(redisCheck);
            if (allowed) {
                allowedCounter.increment();
            } else {
                blockedCounter.increment();
            }
            return allowed;

        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            return handleRedisFailure(key, tier, "Circuit breaker OPEN");

        } catch (Exception e) {
            redisErrorCounter.increment();
            return handleRedisFailure(key, tier, e.getMessage());
        }
    }

    /**
     * Central failure handler that respects the tier policy.
     */
    private boolean handleRedisFailure(String key, RateLimitTier tier, String reason) {
        if (tier == RateLimitTier.CRITICAL) {
            criticalFailClosedCounter.increment();
            log.warn("[RATE-LIMIT] Redis unavailable — BLOCKING critical request. key={}, reason={}",
                    key, reason);
            return false;
        }
        standardFailOpenCounter.increment();
        log.debug("[RATE-LIMIT] Redis unavailable — allowing standard request. key={}, reason={}",
                key, reason);
        return true;
    }

    /**
     * Execute the actual Redis rate limit check with timing.
     */
    private boolean executeRateLimitCheck(String redisKey, int limit, int windowSeconds) {
        return redisLatencyTimer.record(() -> {
            try {
                Long currentCount = redisTemplate.execute(
                        incrementScript,
                        Collections.singletonList(redisKey),
                        String.valueOf(windowSeconds)
                );

                if (currentCount == null) {
                    log.error("⚠️ Redis returned null for rate limit key: {}. Failing OPEN.", redisKey);
                    throw new RuntimeException("Redis returned null");
                }

                boolean allowed = currentCount <= limit;

                if (!allowed) {
                    log.warn("🚫 Rate limit exceeded: key={}, count={}, limit={}, window={}s",
                            redisKey, currentCount, limit, windowSeconds);
                }

                return allowed;

            } catch (Exception e) {
                redisErrorCounter.increment();
                log.error("❌ Redis error during rate limit check: {}", e.getMessage());
                throw e;  // Let circuit breaker handle it
            }
        });
    }

    @Override
    public int getCurrentCount(String key) {
        try {
            for (int windowSeconds : List.of(60, 300, 3600)) {
                String redisKey = buildRedisKey(key, windowSeconds);
                String value = redisTemplate.opsForValue().get(redisKey);
                if (value != null) {
                    return Integer.parseInt(value);
                }
            }
        } catch (Exception e) {
            log.debug("Error getting count for key {}: {}", key, e.getMessage());
        }
        return 0;
    }

    @Override
    public long getRemainingSeconds(String key) {
        try {
            for (int windowSeconds : List.of(60, 300, 3600)) {
                String redisKey = buildRedisKey(key, windowSeconds);
                Long ttl = redisTemplate.getExpire(redisKey);
                if (ttl != null && ttl > 0) {
                    return ttl;
                }
            }
        } catch (Exception e) {
            log.debug("Error getting TTL for key {}: {}", key, e.getMessage());
        }
        return 0;
    }

    /**
     * Reset all rate limit keys using SCAN (non-blocking).
     * 
     * Uses SCAN instead of KEYS to avoid blocking Redis.
     * KEYS * is O(N) and blocks ALL Redis operations during execution.
     * SCAN is cursor-based and yields to other operations.
     */
    @Override
    public void reset() {
        try {
            String pattern = keyPrefix + ":*";
            ScanOptions scanOptions = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(100)  // Process 100 keys per iteration
                    .build();

            int deletedCount = 0;
            try (Cursor<String> cursor = redisTemplate.scan(scanOptions)) {
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    redisTemplate.delete(key);
                    deletedCount++;
                }
            }

            log.info("🔄 Cleared {} rate limit keys from Redis using SCAN", deletedCount);

        } catch (Exception e) {
            log.error("❌ Error clearing rate limit keys: {}", e.getMessage());
        }
    }

    /**
     * Build Redis key with namespace and window identifier.
     */
    private String buildRedisKey(String key, int windowSeconds) {
        return keyPrefix + ":" + key + ":" + windowSeconds;
    }

    /**
     * Update circuit state gauge for metrics.
     */
    private void updateCircuitStateGauge() {
        switch (circuitBreaker.getState()) {
            case CLOSED -> circuitStateGauge.set(0);
            case OPEN -> circuitStateGauge.set(1);
            case HALF_OPEN -> circuitStateGauge.set(2);
            case DISABLED -> circuitStateGauge.set(3);
            case FORCED_OPEN -> circuitStateGauge.set(4);
            case METRICS_ONLY -> circuitStateGauge.set(5);
        }
    }

    /**
     * Check if Redis connection is healthy.
     */
    public boolean isHealthy() {
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            log.warn("Redis health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get current circuit breaker state for monitoring.
     */
    public String getCircuitBreakerState() {
        return circuitBreaker.getState().name();
    }

    /**
     * Get circuit breaker metrics for monitoring.
     */
    public CircuitBreaker.Metrics getCircuitBreakerMetrics() {
        return circuitBreaker.getMetrics();
    }
}
