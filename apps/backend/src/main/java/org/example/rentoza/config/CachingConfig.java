package org.example.rentoza.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.CacheControl;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Caching configuration for Rentoza application
 * Enables HTTP caching with ETag support and in-memory application caching
 */
@Configuration
@EnableCaching
public class CachingConfig implements WebMvcConfigurer {

    /**
     * ETag filter for automatic HTTP caching
     * Generates ETags for GET requests to enable conditional requests
     */
    @Bean
    public ShallowEtagHeaderFilter shallowEtagHeaderFilter() {
        return new ShallowEtagHeaderFilter();
    }

    /**
     * Cache manager for application-level caching
     * Caches frequently accessed data like car features, makes, and search results
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(Arrays.asList(
            // Car data caches
            new ConcurrentMapCache("cars"),           // Individual car details
            new ConcurrentMapCache("carSearch"),      // Search results (short TTL)
            new ConcurrentMapCache("carFeatures"),    // Static feature list
            new ConcurrentMapCache("carMakes"),       // Available car makes
            
            // User data caches
            new ConcurrentMapCache("users"),
            new ConcurrentMapCache("userProfiles"),
            
            // Booking data caches
            new ConcurrentMapCache("bookings"),
            
            // Review data caches
            new ConcurrentMapCache("reviews"),
            
            // Admin data caches
            new ConcurrentMapCache("adminMetrics"),   // Dashboard KPIs (5min TTL)
            
            // Photo signed URLs (fallback when Redis is disabled)
            new ConcurrentMapCache("photoSignedUrls"),
            
            // Geocoding caches (Phase 2.4)
            new ConcurrentMapCache("geocodeCache"),
            new ConcurrentMapCache("reverseGeocodeCache"),
            new ConcurrentMapCache("osrmRouting")
        ));
        return cacheManager;
    }

    /**
     * Dedicated cache manager for photo signed URLs.
     * Used by PhotoUrlService when Redis is not enabled.
     */
    @Bean(name = "photoUrlCacheManager")
    @ConditionalOnMissingBean(name = "photoUrlCacheManager")
    public CacheManager photoUrlCacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(Arrays.asList(
            new ConcurrentMapCache("photoSignedUrls")
        ));
        return cacheManager;
    }

    /**
     * Default cache control settings for static resources
     * Can be used in controllers via @ResponseBody annotations
     */
    public static CacheControl defaultCacheControl() {
        return CacheControl.maxAge(1, TimeUnit.HOURS)
                .mustRevalidate()
                .cachePublic();
    }

    /**
     * Cache control for frequently changing data
     * Short TTL with revalidation
     */
    public static CacheControl shortCacheControl() {
        return CacheControl.maxAge(5, TimeUnit.MINUTES)
                .mustRevalidate()
                .cachePrivate();
    }

    /**
     * Cache control for static/immutable data
     * Long TTL without revalidation
     */
    public static CacheControl longCacheControl() {
        return CacheControl.maxAge(24, TimeUnit.HOURS)
                .cachePublic()
                .immutable();
    }
}
