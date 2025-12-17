package org.example.rentoza.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * Enterprise-grade static resource configuration for uploads.
 * 
 * <p>Handles serving user-uploaded files with optimized caching and error handling:
 * <ul>
 *   <li><strong>Car images:</strong> /car-images/** (long cache, immutable)</li>
 *   <li><strong>Check-in photos:</strong> /check-in-photos/** (medium cache)</li>
 *   <li><strong>User avatars:</strong> /user-avatars/** (short cache, frequently updated)</li>
 *   <li><strong>Documents:</strong> /documents/** (no cache, security sensitive)</li>
 * </ul>
 * 
 * <p><strong>Cache Strategy:</strong>
 * <ul>
 *   <li>Car images: 30 days (images rarely change after upload)</li>
 *   <li>Check-in photos: 7 days (immutable once uploaded)</li>
 *   <li>User avatars: 1 hour (users may update frequently)</li>
 *   <li>Documents: No cache (privacy, security compliance)</li>
 * </ul>
 * 
 * <p><strong>Error Handling:</strong>
 * Missing files return clean 404 JSON via {@link StaticResourceExceptionHandler},
 * never exposing Spring stack traces.
 * 
 * <p><strong>Security Considerations:</strong>
 * <ul>
 *   <li>Paths restricted to configured upload directory (prevents path traversal)</li>
 *   <li>Documents served only with authentication (handled by SecurityConfig)</li>
 *   <li>CORS headers managed separately (see CorsConfig)</li>
 * </ul>
 * 
 * @see StaticResourceExceptionHandler for missing resource handling
 * @see CachingConfig for additional HTTP caching
 */
@Configuration
public class ResourceHandlerConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ResourceHandlerConfig.class);

    @Value("${app.upload.path:uploads}")
    private String uploadPath;

    /**
     * Configure resource handlers for serving user-uploaded files.
     * 
     * <p>Maps URL patterns to filesystem directories with appropriate caching.
     * 
     * <p><strong>URL Patterns:</strong>
     * <pre>
     * /car-images/47/photo.jpg         → uploads/car-images/47/photo.jpg
     * /check-in-photos/123/exterior.jpg → uploads/check-in-photos/123/exterior.jpg
     * /user-avatars/5/profile.jpg      → uploads/user-avatars/5/profile.jpg
     * /documents/booking-123.pdf       → uploads/documents/booking-123.pdf
     * </pre>
     * 
     * @param registry Spring resource handler registry
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        log.info("[ResourceHandler] Configuring static resource handlers with upload path: {}", uploadPath);

        // Car images - Long cache (30 days)
        // Cars rarely change images after listing, safe to cache aggressively
        registry.addResourceHandler("/car-images/**")
                .addResourceLocations("file:" + uploadPath + "/car-images/")
                .setCacheControl(CacheControl.maxAge(30, TimeUnit.DAYS)
                        .cachePublic()
                        .immutable());

        // Check-in photos - Medium cache (7 days)
        // Photos are immutable once uploaded during check-in process
        registry.addResourceHandler("/check-in-photos/**")
                .addResourceLocations("file:" + uploadPath + "/check-in-photos/")
                .setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS)
                        .cachePublic());

        // User avatars - Short cache (1 hour)
        // Users may update profile pictures frequently
        registry.addResourceHandler("/user-avatars/**")
                .addResourceLocations("file:" + uploadPath + "/user-avatars/")
                .setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS)
                        .cachePublic()
                        .mustRevalidate());

        // Documents - No cache (security sensitive)
        // Booking contracts, legal docs should not be cached
        // Authentication required (enforced by SecurityConfig)
        registry.addResourceHandler("/documents/**")
                .addResourceLocations("file:" + uploadPath + "/documents/")
                .setCacheControl(CacheControl.noStore()
                        .mustRevalidate());

        // Legacy /uploads/** handler (backward compatibility)
        // Deprecated - use specific paths above for new code
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath + "/")
                .setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS));

        log.info("[ResourceHandler] Static resource handlers configured successfully");
    }
}
