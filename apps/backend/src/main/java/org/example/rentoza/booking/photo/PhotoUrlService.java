package org.example.rentoza.booking.photo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.storage.SupabaseStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Objects;

/**
 * P0-3 FIX: Centralized photo URL generation with signed URLs and expiration.
 * 
 * <p>Prevents attackers from:
 * <ul>
 *   <li>Accessing photos via guessed/predictable URLs</li>
 *   <li>Mass-scraping photos with multiple requests</li>
 *   <li>Accessing photos after access window closes</li>
 * </ul>
 * 
 * <h2>URL Strategy</h2>
 * <ul>
 *   <li>All URLs are signed by Supabase with time-based expiration</li>
 *   <li>Default expiration: 15 minutes</li>
 *   <li>URLs are cached on backend for 14 minutes (refresh 1 min before expiry)</li>
 *   <li>Frontend requests fresh URL before every view</li>
 *   <li>Actual image serving is done by Supabase CDN (not our backend)</li>
 * </ul>
 * 
 * <h2>No Public URLs</h2>
 * Storage keys are never exposed to frontend. Only signed URLs are returned.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoUrlService {

    private final SupabaseStorageService supabaseStorageService;
    private final Environment environment;

    @Value("${app.photo.signed-url-expiry-seconds:900}")  // 15 minutes default
    private int signedUrlExpirySeconds;

    @Value("${app.photo.cache-ttl-seconds:840}")  // 14 minutes default — shared with RedisCacheConfig
    private int cacheTtlSeconds;

    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    /**
     * WI-10: Validate cache-TTL vs signed-URL-expiry invariant at startup.
     *
     * <ul>
     *   <li><b>Redis enabled:</b> cacheTtlSeconds MUST be strictly less than
     *       signedUrlExpirySeconds, otherwise cached URLs could be served after
     *       they expire. Violation is a fatal misconfiguration.</li>
     *   <li><b>Redis disabled (ConcurrentMapCache fallback):</b> there is no TTL
     *       enforcement whatsoever; cached signed URLs may outlive their expiry
     *       window. This is a known limitation logged as a warning.</li>
     * </ul>
     */
    @PostConstruct
    void validateCacheTtlConsistency() {
        if (redisEnabled) {
            if (cacheTtlSeconds >= signedUrlExpirySeconds) {
                throw new IllegalStateException(
                        String.format("Cache TTL (%ds) must be strictly less than signed URL expiry (%ds). " +
                                "Cached URLs would be served after they expire. " +
                                "Fix: set app.photo.cache-ttl-seconds < app.photo.signed-url-expiry-seconds",
                                cacheTtlSeconds, signedUrlExpirySeconds));
            }
            log.info("[PhotoURL] Cache TTL validated: cacheTtl={}s < signedUrlExpiry={}s (margin={}s)",
                    cacheTtlSeconds, signedUrlExpirySeconds, signedUrlExpirySeconds - cacheTtlSeconds);
        } else if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("Redis-backed photo signed URL caching is required in production. " +
                    "Enable app.redis.enabled=true so signed URL TTL guarantees are enforced.");
        } else {
            log.warn("[PhotoURL] Redis is disabled — photoSignedUrls cache uses ConcurrentMapCache " +
                    "with NO TTL enforcement. Expired signed URLs may be served from cache until " +
                    "application restart. Enable Redis (app.redis.enabled=true) for production use.");
        }
    }

    /**
     * Generate a signed URL for a photo that expires in 15 minutes.
     * 
     * URLs are cached by this service to avoid regenerating them unnecessarily.
     * Cache is invalidated 1 minute before expiry (managed via Spring @Cacheable).
     * 
     * @param bucket The Supabase storage bucket (e.g., "checkin_standard", "checkin_pii")
     * @param storageKey The file path within the bucket
     * @param photoId The database ID of the photo (used for audit logging)
     * @return A signed, time-limited URL suitable for direct image serving
     */
    @Cacheable(value = "photoSignedUrls", key = "#bucket + '::' + #storageKey")
    public String generateSignedUrl(String bucket, String storageKey, Long photoId) {
        try {
            log.debug("[PhotoURL] Generating signed URL: bucket={}, key={}, photoId={}", 
                bucket, storageKey, photoId);
            
            // Use the actual bucket name passed in — do NOT hardcode bucket routing.
            // The bucket name is resolved by the calling service (CheckOutService, CheckInService)
            // and must be passed through to the Supabase API correctly.
            String signedUrl = supabaseStorageService.createSignedUrlForBucket(
                    bucket, storageKey, signedUrlExpirySeconds);
            
            log.debug("[PhotoURL] Generated signed URL (expires in {}s): photoId={}", 
                signedUrlExpirySeconds, photoId);
            
            return signedUrl;
            
        } catch (Exception e) {
            log.error("[PhotoURL] Failed to generate signed URL: bucket={}, key={}, photoId={}", 
                bucket, storageKey, photoId, e);
            throw new RuntimeException(
                String.format("Nije moguće učitati fotografiju. Greška: %s", e.getMessage())
            );
        }
    }

    /**
     * Generate signed URL only if Supabase is enabled.
     * For local filesystem fallback (deprecated), returns null and lets controller serve locally.
     * 
     * This ensures we don't accidentally expose filesystem paths.
     * 
     * @param bucket The bucket name
     * @param storageKey The file path
     * @param photoId The photo ID
     * @param storageMode Configuration mode ("supabase" or "local")
     * @return Signed URL if Supabase enabled, null if using local storage
     */
    public String generateSignedUrlIfSupabase(String bucket, String storageKey, Long photoId, String storageMode) {
        if ("supabase".equalsIgnoreCase(storageMode)) {
            return generateSignedUrl(bucket, storageKey, photoId);
        }
        
        // Local filesystem: controller will serve directly (no URL needed)
        log.warn("[PhotoURL] Local storage in use. Recommend enabling Supabase: photoId={}", photoId);
        return null;
    }

    /**
     * Get the expiry time of a signed URL.
     * Used by frontend to know when to refresh the URL.
     * 
     * @return Instant when the signed URL expires
     */
    public Instant getSignedUrlExpiry() {
        return Instant.now().plusSeconds(signedUrlExpirySeconds);
    }

    /**
     * Calculate when the cache should refresh the URL (1 minute before expiry).
     * 
     * @return Instant when cache should be invalidated
     */
    public Instant getCacheRefreshTime() {
        return Instant.now().plusSeconds(signedUrlExpirySeconds - 60);
    }
}
