package org.example.rentoza.car.storage;

import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarImage;
import org.example.rentoza.storage.SupabaseStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for resolving car image URLs across different storage backends.
 * 
 * <p>This service handles legacy data migration scenarios where images might be stored as:
 * <ul>
 *   <li>Supabase Storage URLs (current standard)</li>
 *   <li>Local filesystem paths (development mode)</li>
 *   <li>Base64 data URIs (legacy inline images)</li>
 *   <li>Storage paths without full URL</li>
 * </ul>
 * 
 * <p>Enterprise-grade features:
 * <ul>
 *   <li>Automatic URL format detection and resolution</li>
 *   <li>Fallback handling for legacy data</li>
 *   <li>CDN-ready URL transformation</li>
 * </ul>
 */
@Service
public class CarImageUrlResolver {

    private static final Logger log = LoggerFactory.getLogger(CarImageUrlResolver.class);

    @Value("${supabase.url:}")
    private String supabaseUrl;

    @Value("${app.base-url:http://localhost:8080}")
    private String appBaseUrl;

    @Autowired(required = false)
    private SupabaseStorageService supabaseStorageService;

    /**
     * Resolve all image URLs for a car to displayable format.
     * 
     * @param car Car entity
     * @return List of resolved image URLs
     */
    public List<String> resolveImageUrls(Car car) {
        if (car == null) {
            return List.of();
        }

        // Try new CarImage entities first
        if (car.getImages() != null && !car.getImages().isEmpty()) {
            return car.getImages().stream()
                    .map(CarImage::getImageUrl)
                    .map(this::resolveUrl)
                    .collect(Collectors.toList());
        }

        // Fallback to legacy single imageUrl
        if (car.getImageUrl() != null && !car.getImageUrl().isBlank()) {
            return List.of(resolveUrl(car.getImageUrl()));
        }

        return List.of();
    }

    /**
     * Resolve primary (first) image URL for a car.
     * 
     * @param car Car entity
     * @return Primary image URL or null
     */
    public String resolvePrimaryImageUrl(Car car) {
        List<String> urls = resolveImageUrls(car);
        return urls.isEmpty() ? null : urls.get(0);
    }

    /**
     * Resolve a single URL to displayable format.
     * 
     * @param imageUrl URL, path, or base64 data
     * @return Displayable URL
     */
    public String resolveUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }

        // Already a full HTTP/HTTPS URL
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            return imageUrl;
        }

        // Base64 data URI - return as-is (browser can display directly)
        if (imageUrl.startsWith("data:image/")) {
            return imageUrl;
        }

        // Use Supabase service for resolution if available
        if (supabaseStorageService != null) {
            return supabaseStorageService.resolveCarImageUrl(imageUrl);
        }

        // Manual resolution fallback
        return manualResolve(imageUrl);
    }

    /**
     * Manual URL resolution when SupabaseStorageService is not available.
     */
    private String manualResolve(String imageUrl) {
        // Local upload path
        if (imageUrl.startsWith("/uploads/")) {
            return imageUrl; // Server-relative, works as-is
        }

        // Legacy car-images path
        if (imageUrl.startsWith("/car-images/") || imageUrl.startsWith("car-images/")) {
            String path = imageUrl.startsWith("/") ? imageUrl : "/" + imageUrl;
            return "/uploads" + path;
        }

        // Supabase storage path (e.g., cars/47/images/filename.jpg)
        if (imageUrl.startsWith("cars/") && !supabaseUrl.isBlank()) {
            return String.format("%s/storage/v1/object/public/cars-images/%s", supabaseUrl, imageUrl);
        }

        // Unknown format - log warning and return as-is
        log.warn("Unknown image URL format: {}", imageUrl);
        return imageUrl;
    }

    /**
     * Check if URL is a Supabase Storage URL.
     */
    public boolean isSupabaseUrl(String url) {
        return url != null && url.contains("supabase.co/storage");
    }

    /**
     * Check if URL is a Base64 data URI.
     */
    public boolean isBase64(String url) {
        return url != null && url.startsWith("data:image/");
    }

    /**
     * Check if URL is a local filesystem path.
     */
    public boolean isLocalPath(String url) {
        return url != null && 
               !isSupabaseUrl(url) && 
               !isBase64(url) && 
               (url.startsWith("/uploads/") || 
                url.startsWith("/car-images/") || 
                url.startsWith("car-images/"));
    }
}
