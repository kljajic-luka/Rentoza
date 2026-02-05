package org.example.chatservice.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.example.chatservice.dto.client.BookingConversationDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Service for enriching conversations with booking context using caching and resilience patterns.
 * 
 * Architecture:
 * - Layer 1: Caffeine cache (in-memory, TTL=2min, size=5k)
 * - Layer 2: Circuit breaker (fail-fast if backend unhealthy)
 * - Layer 3: Retry (maxAttempts=2, backoff=100ms)
 * - Layer 4: BackendApiClient (HTTP calls to Main API)
 * - Layer 5: Fallback (graceful degradation with "Unknown car")
 * 
 * Performance:
 * - Cache hit: <1ms (no network call)
 * - Cache miss: ~50-200ms (HTTP roundtrip + DB query)
 * - Circuit breaker open: <1ms (fail-fast, no network call)
 * - Fallback: <1ms (in-memory DTO creation)
 * 
 * Security:
 * - Cache key: bookingId + actAsUserId (isolated per user)
 * - RLS enforcement: Main API validates user assertion
 * - NO PII in cache: BookingConversationDTO has no renter/owner details
 * 
 * Observability:
 * - Audit logging: Cache hits/misses, enrichment latency, fallback reasons
 * - Metrics: enrichment_requests, enrichment_cache_hits, enrichment_failures, enrichment_latency
 * - Circuit breaker states: CLOSED, OPEN, HALF_OPEN
 * 
 * Use Cases:
 * - GET /api/conversations → Enrich each conversation with booking context
 * - GET /api/conversations/{id} → Enrich single conversation
 * - WebSocket messages → Enrich real-time messages (future enhancement)
 */
@Service
public class ConversationEnrichmentService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationEnrichmentService.class);

    private final BackendApiClient backendApiClient;
    private final Cache<String, BookingConversationDTO> cache;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    
    // Metrics
    private final Timer enrichmentTimer;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    public ConversationEnrichmentService(BackendApiClient backendApiClient, MeterRegistry meterRegistry) {
        this.backendApiClient = backendApiClient;
        
        // Initialize metrics
        this.enrichmentTimer = Timer.builder("chat.enrichment.duration")
                .description("Duration of conversation enrichment calls")
                .tag("service", "chat")
                .register(meterRegistry);
        
        this.successCounter = Counter.builder("chat.enrichment.success")
                .description("Successful conversation enrichment calls")
                .tag("service", "chat")
                .register(meterRegistry);
        
        this.failureCounter = Counter.builder("chat.enrichment.failure")
                .description("Failed conversation enrichment calls")
                .tag("service", "chat")
                .register(meterRegistry);
        
        this.cacheHitCounter = Counter.builder("chat.enrichment.cache.hit")
                .description("Cache hits for conversation enrichment")
                .tag("service", "chat")
                .register(meterRegistry);
        
        this.cacheMissCounter = Counter.builder("chat.enrichment.cache.miss")
                .description("Cache misses for conversation enrichment")
                .tag("service", "chat")
                .register(meterRegistry);
        
        // Configure Caffeine cache
        // TTL: 2 minutes (balance between freshness and performance)
        // Max size: 5,000 entries (estimated ~500KB memory for 5k DTOs)
        // Eviction: LRU (Least Recently Used)
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(2, TimeUnit.MINUTES)
                .maximumSize(5000)
                .recordStats() // Enable cache statistics for monitoring
                .build();
        
        // Configure Circuit Breaker
        // Failure rate threshold: 50% (open circuit if 50% of requests fail)
        // Minimum calls: 5 (require 5 calls before calculating failure rate)
        // Wait duration: 10s (stay open for 10s before attempting HALF_OPEN)
        // Permitted calls in HALF_OPEN: 3 (allow 3 test calls to check backend health)
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slidingWindowSize(10)
                .build();
        this.circuitBreaker = CircuitBreaker.of("bookingEnrichment", circuitBreakerConfig);
        
        // Configure Retry
        // Max attempts: 2 (1 initial + 1 retry)
        // Wait duration: 100ms (backoff between retries)
        // Retry only on network errors, not on 403/404 (those are valid responses)
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(100))
                .retryExceptions(org.springframework.web.reactive.function.client.WebClientRequestException.class)
                .ignoreExceptions(
                        org.springframework.web.reactive.function.client.WebClientResponseException.NotFound.class,
                        org.springframework.web.reactive.function.client.WebClientResponseException.Forbidden.class
                )
                .build();
        this.retry = Retry.of("bookingEnrichment", retryConfig);
        
        // Log resilience configuration
        logger.info("✅ ConversationEnrichmentService initialized with cache (TTL=2min, size=5k), " +
                "circuit breaker (threshold=50%, wait=10s), retry (maxAttempts=2, backoff=100ms)");
    }

    /**
     * Enrich conversation with booking context using cache + resilience patterns.
     * 
     * @param bookingId Booking ID from conversation
     * @param actAsUserId User ID asserting access (from authenticated user in chat)
     * @return Mono<BookingConversationDTO> with conversation-safe booking summary or fallback
     */
    public Mono<BookingConversationDTO> enrichConversation(String bookingId, String actAsUserId) {
        return enrichmentTimer.record(() -> {
            long startTime = System.currentTimeMillis();
            String cacheKey = buildCacheKey(bookingId, actAsUserId);
            
            // Check cache first
            BookingConversationDTO cachedDto = cache.getIfPresent(cacheKey);
            if (cachedDto != null) {
                long latency = System.currentTimeMillis() - startTime;
                cacheHitCounter.increment();
                logger.debug("✅ Cache HIT for bookingId={}, actAsUserId={} (latency={}ms)", 
                        bookingId, actAsUserId, latency);
                return Mono.just(cachedDto);
            }
            
            cacheMissCounter.increment();
            logger.debug("⚠️ Cache MISS for bookingId={}, actAsUserId={}", bookingId, actAsUserId);
        
            // Cache miss: Fetch from backend with resilience patterns
            return backendApiClient.getConversationView(bookingId, actAsUserId)
                    .transformDeferred(RetryOperator.of(retry))
                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                    .doOnSuccess(dto -> {
                        long latency = System.currentTimeMillis() - startTime;
                        
                        // Cache successful enrichment (even fallback DTOs for negative caching)
                        cache.put(cacheKey, dto);
                        
                        // Record metrics
                        if ("UNAVAILABLE".equals(dto.getTripStatus())) {
                            failureCounter.increment();
                            logger.warn("⚠️ Enrichment fallback for bookingId={}, actAsUserId={} (latency={}ms)", 
                                    bookingId, actAsUserId, latency);
                        } else {
                            successCounter.increment();
                            logger.info("✅ Enrichment success for bookingId={}, actAsUserId={}, tripStatus={}, car={} {} {} (latency={}ms)", 
                                    bookingId, actAsUserId, dto.getTripStatus(), 
                                    dto.getCarYear(), dto.getCarBrand(), dto.getCarModel(), latency);
                        }
                    })
                    .doOnError(error -> {
                        long latency = System.currentTimeMillis() - startTime;
                        failureCounter.increment();
                        logger.error("❌ Enrichment error for bookingId={}, actAsUserId={} (latency={}ms): {}", 
                                bookingId, actAsUserId, latency, error.getMessage());
                    })
                    .onErrorResume(error -> {
                        // Circuit breaker open or retry exhausted: Return fallback
                        failureCounter.increment();
                        logger.warn("⚠️ Resilience fallback for bookingId={}, actAsUserId={}: {}", 
                                bookingId, actAsUserId, error.getMessage());
                        BookingConversationDTO fallback = BookingConversationDTO.createFallback(bookingId);
                        cache.put(cacheKey, fallback); // Cache fallback for negative caching (avoid retry storms)
                        return Mono.just(fallback);
                    });
        });
    }

    /**
     * Build cache key from booking ID and user ID.
     * Isolated per user to prevent cross-user cache pollution.
     * 
     * @param bookingId Booking ID
     * @param actAsUserId User ID
     * @return Cache key (e.g., "booking:123:user:456")
     */
    private String buildCacheKey(String bookingId, String actAsUserId) {
        return String.format("booking:%s:user:%s", bookingId, actAsUserId);
    }

    /**
     * Get cache statistics for monitoring.
     * 
     * @return Cache stats (hit rate, miss rate, eviction count, size)
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats getCacheStats() {
        return cache.stats();
    }

    /**
     * Clear cache (for testing or manual cache invalidation).
     */
    public void clearCache() {
        cache.invalidateAll();
        logger.info("🧹 Enrichment cache cleared");
    }

    /**
     * Get circuit breaker state for monitoring.
     * 
     * @return Circuit breaker state (CLOSED, OPEN, HALF_OPEN)
     */
    public io.github.resilience4j.circuitbreaker.CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }
}
