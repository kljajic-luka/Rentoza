package org.example.rentoza.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis Cache Manager Configuration for CQRS Read Model.
 * 
 * <h2>Phase 2 Architecture Improvement</h2>
 * <p>Provides Redis-backed caching with JSON serialization for:
 * <ul>
 *   <li>Check-in status queries (30s TTL)</li>
 *   <li>Photo lists (60s TTL)</li>
 *   <li>User dashboards (120s TTL)</li>
 *   <li>Static data (24h TTL)</li>
 * </ul>
 * 
 * <h2>Cache Hierarchy</h2>
 * <pre>
 * Redis (distributed, L2)
 *    └─> In-Memory (local, L1 via Caffeine if needed)
 * </pre>
 * 
 * <h2>Serialization</h2>
 * <p>Uses Jackson JSON serialization for human-readable cache values
 * and cross-service compatibility.
 * 
 * @see org.example.rentoza.booking.checkin.cqrs.CheckInQueryService for cache consumers
 */
@Configuration
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
@Slf4j
public class RedisCacheConfig implements CachingConfigurer {

    // ========== TTL CONSTANTS ==========
    
    /** Short TTL for frequently changing data (check-in status) */
    private static final Duration TTL_SHORT = Duration.ofSeconds(30);
    
    /** Medium TTL for moderately stable data (photos, minimal status) */
    private static final Duration TTL_MEDIUM = Duration.ofSeconds(60);
    
    /** Standard TTL for user dashboards */
    private static final Duration TTL_STANDARD = Duration.ofMinutes(2);
    
    /** Long TTL for rarely changing data (car makes, features) */
    private static final Duration TTL_LONG = Duration.ofHours(1);
    
    /** Extended TTL for static reference data */
    private static final Duration TTL_STATIC = Duration.ofHours(24);

        /** Signed URL cache TTL (refresh 1 minute before 15-min expiry) */
        private static final Duration TTL_PHOTO_SIGNED = Duration.ofMinutes(14);
    
    /** Default TTL for unlisted caches */
    private static final Duration TTL_DEFAULT = Duration.ofMinutes(5);

    /**
     * Primary Redis Cache Manager with JSON serialization.
     * 
     * <p>Features:
     * <ul>
     *   <li>JSON serialization for human-readable cache values</li>
     *   <li>Type information preserved for polymorphic DTOs</li>
     *   <li>Per-cache TTL configuration</li>
     *   <li>Null value handling (do not cache nulls)</li>
     * </ul>
     */
    @Bean
    @Primary
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        log.info("[Redis-Cache] Initializing Redis Cache Manager with JSON serialization");
        
        // Configure ObjectMapper for Redis serialization
        ObjectMapper objectMapper = createRedisObjectMapper();
        
        // Create JSON serializer
        GenericJackson2JsonRedisSerializer jsonSerializer = 
                new GenericJackson2JsonRedisSerializer(objectMapper);
        
        // Base cache configuration
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL_DEFAULT)
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer))
                .disableCachingNullValues()  // Don't cache null results
                .prefixCacheNameWith("rentoza:");  // Namespace prefix
        
        // Per-cache configurations
        Map<String, RedisCacheConfiguration> cacheConfigs = buildCacheConfigurations(defaultConfig);
        
        RedisCacheManager cacheManager = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .transactionAware()  // Participate in Spring transactions
                .build();
        
        log.info("[Redis-Cache] Configured {} custom cache TTLs", cacheConfigs.size());
        
        return cacheManager;
    }

    /**
     * Build per-cache TTL configurations.
     */
    private Map<String, RedisCacheConfiguration> buildCacheConfigurations(
            RedisCacheConfiguration defaultConfig) {
        
        Map<String, RedisCacheConfiguration> configs = new HashMap<>();
        
        // ========== CHECK-IN CACHES (CQRS Read Model) ==========
        
        // Check-in status - frequently polled, short TTL
        configs.put("checkin-status", defaultConfig.entryTtl(TTL_SHORT));
        
        // Check-in photos - moderately stable
        configs.put("checkin-photos", defaultConfig.entryTtl(TTL_MEDIUM));
        
        // Minimal status for polling
        configs.put("checkin-status-minimal", defaultConfig.entryTtl(TTL_SHORT));
        
        // User's active check-ins (dashboard)
        configs.put("checkin-dashboard", defaultConfig.entryTtl(TTL_STANDARD));
        
        // ========== CAR CACHES ==========
        
        // Individual car details
        configs.put("cars", defaultConfig.entryTtl(TTL_STANDARD));
        
        // Search results - changes frequently
        configs.put("carSearch", defaultConfig.entryTtl(TTL_SHORT));
        
        // Static feature list
        configs.put("carFeatures", defaultConfig.entryTtl(TTL_STATIC));
        
        // Available car makes
        configs.put("carMakes", defaultConfig.entryTtl(TTL_STATIC));
        
        // ========== USER CACHES ==========
        
        // User entities
        configs.put("users", defaultConfig.entryTtl(TTL_STANDARD));
        
        // User profiles (public view)
        configs.put("userProfiles", defaultConfig.entryTtl(TTL_LONG));
        
        // ========== BOOKING CACHES ==========
        
        // Individual bookings
        configs.put("bookings", defaultConfig.entryTtl(TTL_STANDARD));
        
        // Booking availability (calendar)
        configs.put("bookingAvailability", defaultConfig.entryTtl(TTL_MEDIUM));
        
        // ========== REVIEW CACHES ==========
        
        // Car reviews
        configs.put("reviews", defaultConfig.entryTtl(TTL_LONG));
        
        // User ratings aggregate
        configs.put("userRatings", defaultConfig.entryTtl(TTL_LONG));
        
        // ========== RATE LIMITING ==========
        
        // Rate limit counters (managed by IdempotencyService)
        configs.put("rate-limits", defaultConfig.entryTtl(Duration.ofMinutes(1)));
        
        // Idempotency keys
        configs.put("idempotency", defaultConfig.entryTtl(Duration.ofHours(24)));
        
        // ========== GEOCODING CACHES (Phase 2.4) ==========
        
        // Geocode results - address to coordinates (24h TTL - addresses rarely change)
        configs.put("geocodeCache", defaultConfig.entryTtl(TTL_STATIC));
        
        // Reverse geocode results - coordinates to address (24h TTL)
        configs.put("reverseGeocodeCache", defaultConfig.entryTtl(TTL_STATIC));
        
        // OSRM routing results - distance/duration (1h TTL - traffic patterns change)
        configs.put("osrmRouting", defaultConfig.entryTtl(TTL_LONG));

                // Photo signed URLs (short-lived, refreshed often)
                configs.put("photoSignedUrls", defaultConfig.entryTtl(TTL_PHOTO_SIGNED));
        
        return configs;
    }

    /**
     * Create ObjectMapper configured for Redis JSON serialization.
     * 
     * <p>Features:
     * <ul>
     *   <li>Java 8 date/time support</li>
     *   <li>Type information for polymorphic deserialization</li>
     *   <li>Lenient parsing for forward compatibility</li>
     * </ul>
     */
    private ObjectMapper createRedisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // Java 8 date/time support
        mapper.registerModule(new JavaTimeModule());
        
        // Enable polymorphic type handling with security constraints
        BasicPolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .allowIfSubType("org.example.rentoza")  // Only allow our packages
                .allowIfSubType("java.util")
                .allowIfSubType("java.time")
                .allowIfSubType("java.lang")
                .build();
        
        mapper.activateDefaultTyping(
                ptv,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        
        // Lenient settings for forward compatibility
        mapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, 
                false);
        
        return mapper;
    }

    /**
     * Error handler that logs cache failures without propagating exceptions.
     * 
     * <p>Cache failures should not break the application - they should
     * just result in cache misses and database fallback.
     */
    @Override
    @Bean
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    /**
     * Custom error handler that logs cache errors at appropriate levels.
     */
    private static class LoggingCacheErrorHandler extends SimpleCacheErrorHandler {
        
        @Override
        public void handleCacheGetError(RuntimeException exception, 
                                        org.springframework.cache.Cache cache, 
                                        Object key) {
            log.warn("[Redis-Cache] GET error for cache '{}', key '{}': {}", 
                    cache.getName(), key, exception.getMessage());
            // Don't rethrow - allow fallback to database
        }
        
        @Override
        public void handleCachePutError(RuntimeException exception, 
                                        org.springframework.cache.Cache cache, 
                                        Object key, 
                                        Object value) {
            log.warn("[Redis-Cache] PUT error for cache '{}', key '{}': {}", 
                    cache.getName(), key, exception.getMessage());
            // Don't rethrow - operation succeeded, just cache failed
        }
        
        @Override
        public void handleCacheEvictError(RuntimeException exception, 
                                          org.springframework.cache.Cache cache, 
                                          Object key) {
            log.warn("[Redis-Cache] EVICT error for cache '{}', key '{}': {}", 
                    cache.getName(), key, exception.getMessage());
            // Don't rethrow - may cause stale data but not critical
        }
        
        @Override
        public void handleCacheClearError(RuntimeException exception, 
                                          org.springframework.cache.Cache cache) {
            log.error("[Redis-Cache] CLEAR error for cache '{}': {}", 
                    cache.getName(), exception.getMessage());
            // Don't rethrow - but log at error level as this is more serious
        }
    }
}
