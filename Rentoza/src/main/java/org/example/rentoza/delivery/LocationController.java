package org.example.rentoza.delivery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Public location endpoints for authenticated users.
 * 
 * Provides geocoding (address to coordinates) and reverse geocoding
 * (coordinates to address) for the frontend location picker.
 * 
 * Supports both Nominatim (OpenStreetMap) and Mapbox geocoding:
 * - Mapbox is preferred when token is configured (faster, more accurate)
 * - Nominatim is fallback (free, rate-limited)
 * 
 * Features:
 * - Per-user rate limiting (5 requests per second)
 * - Result caching (reduces API calls)
 * - Serbia bounds validation
 * 
 * @since 2.4.0 (Geospatial Location Migration)
 */
@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private static final Logger log = LoggerFactory.getLogger(LocationController.class);

    /**
     * Serbia bounding box for result validation and search bias
     */
    private static final double SERBIA_MIN_LAT = 42.2;
    private static final double SERBIA_MAX_LAT = 46.2;
    private static final double SERBIA_MIN_LON = 18.8;
    private static final double SERBIA_MAX_LON = 23.0;
    
    /**
     * Serbia bbox string for Mapbox API
     */
    private static final String SERBIA_BBOX = "18.8,42.2,23.0,46.2";

    /**
     * Rate limit: requests per user per second
     */
    private static final int RATE_LIMIT_PER_SECOND = 5;

    /**
     * In-memory rate limiter (user email -> last request timestamps)
     */
    private final Map<String, List<Long>> userRequestTimestamps = new ConcurrentHashMap<>();

    private final RestTemplate restTemplate;

    @Value("${rentoza.nominatim.base-url:https://nominatim.openstreetmap.org}")
    private String nominatimBaseUrl;

    @Value("${rentoza.nominatim.enabled:true}")
    private boolean nominatimEnabled;

    @Value("${rentoza.mapbox.access-token:}")
    private String mapboxAccessToken;

    @Value("${rentoza.mapbox.geocoding-enabled:true}")
    private boolean mapboxGeocodingEnabled;

    public LocationController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Geocode an address to coordinates.
     * Returns a list of suggestions for autocomplete.
     * 
     * Uses Mapbox if configured, falls back to Nominatim.
     * 
     * @param query Address search query
     * @param limit Maximum number of results (default 5)
     * @return List of geocoding suggestions
     */
    @GetMapping("/geocode")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<GeocodeSuggestion>> geocodeAddress(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int limit,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {
        
        // Rate limiting
        if (!checkRateLimit(userEmail != null ? userEmail : "anonymous")) {
            log.warn("Rate limit exceeded for user: {}", userEmail);
            return ResponseEntity.status(429).body(List.of());
        }

        if (query == null || query.isBlank() || query.length() < 2) {
            return ResponseEntity.ok(List.of());
        }

        // Try Mapbox first if configured
        if (isMapboxAvailable()) {
            try {
                List<GeocodeSuggestion> results = geocodeWithMapbox(query, limit);
                if (!results.isEmpty()) {
                    return ResponseEntity.ok(results);
                }
            } catch (Exception e) {
                log.warn("Mapbox geocoding failed, falling back to Nominatim: {}", e.getMessage());
            }
        }

        // Fallback to Nominatim
        if (nominatimEnabled) {
            try {
                List<GeocodeSuggestion> results = geocodeWithNominatim(query, limit);
                return ResponseEntity.ok(results);
            } catch (Exception e) {
                log.error("Nominatim geocoding failed: {}", e.getMessage());
            }
        }

        return ResponseEntity.ok(List.of());
    }

    /**
     * Reverse geocode coordinates to an address.
     * 
     * Uses Mapbox if configured, falls back to Nominatim.
     * 
     * @param latitude Latitude coordinate
     * @param longitude Longitude coordinate
     * @return Address information for the coordinates
     */
    @GetMapping("/reverse-geocode")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReverseGeocodeResult> reverseGeocode(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {
        
        // Rate limiting
        if (!checkRateLimit(userEmail != null ? userEmail : "anonymous")) {
            log.warn("Rate limit exceeded for user: {}", userEmail);
            return ResponseEntity.status(429).body(null);
        }

        // Validate coordinates are within Serbia bounds
        if (latitude < SERBIA_MIN_LAT || latitude > SERBIA_MAX_LAT ||
            longitude < SERBIA_MIN_LON || longitude > SERBIA_MAX_LON) {
            return ResponseEntity.badRequest().body(
                    new ReverseGeocodeResult(
                            "Lokacija izvan Srbije",
                            null, null, latitude, longitude
                    )
            );
        }

        // Try Mapbox first if configured
        if (isMapboxAvailable()) {
            try {
                ReverseGeocodeResult result = reverseGeocodeWithMapbox(latitude, longitude);
                if (result != null && result.address() != null) {
                    return ResponseEntity.ok(result);
                }
            } catch (Exception e) {
                log.warn("Mapbox reverse geocoding failed, falling back to Nominatim: {}", e.getMessage());
            }
        }

        // Fallback to Nominatim
        if (nominatimEnabled) {
            try {
                ReverseGeocodeResult result = reverseGeocodeWithNominatim(latitude, longitude);
                return ResponseEntity.ok(result);
            } catch (Exception e) {
                log.error("Nominatim reverse geocoding failed: {}", e.getMessage());
            }
        }

        // Last resort: return coordinates as address
        return ResponseEntity.ok(new ReverseGeocodeResult(
                String.format("%.6f, %.6f", latitude, longitude),
                null, null, latitude, longitude
        ));
    }

    // ========== Mapbox Geocoding ==========

    private boolean isMapboxAvailable() {
        return mapboxGeocodingEnabled && 
               mapboxAccessToken != null && 
               !mapboxAccessToken.isBlank();
    }

    /**
     * Geocode using Mapbox Geocoding API.
     * Cached for performance.
     */
    @Cacheable(value = "geocodeCache", key = "#query + '-' + #limit", unless = "#result.isEmpty()")
    public List<GeocodeSuggestion> geocodeWithMapbox(String query, int limit) {
        // Use sr-Latn for Latin script (not Cyrillic), add neighborhood for street-level results
        String url = String.format(
                "https://api.mapbox.com/geocoding/v5/mapbox.places/%s.json?access_token=%s&limit=%d&bbox=%s&language=sr-Latn,en&types=address,poi,place,locality,neighborhood",
                URLEncoder.encode(query, StandardCharsets.UTF_8),
                mapboxAccessToken,
                Math.min(limit, 10),
                SERBIA_BBOX
        );

        log.debug("Mapbox geocode request for query: {}", query);

        MapboxGeocodeResponse response = restTemplate.getForObject(url, MapboxGeocodeResponse.class);

        if (response == null || response.features == null) {
            return List.of();
        }

        List<GeocodeSuggestion> suggestions = new ArrayList<>();
        for (MapboxFeature feature : response.features) {
            if (feature.center != null && feature.center.length >= 2) {
                double lon = feature.center[0];
                double lat = feature.center[1];

                // Validate within Serbia bounds
                if (lat >= SERBIA_MIN_LAT && lat <= SERBIA_MAX_LAT &&
                    lon >= SERBIA_MIN_LON && lon <= SERBIA_MAX_LON) {
                    
                    suggestions.add(new GeocodeSuggestion(
                            feature.placeName,
                            lat,
                            lon,
                            extractCityFromMapboxContext(feature),
                            extractZipCodeFromMapboxContext(feature),
                            feature.placeType != null && feature.placeType.length > 0 
                                    ? feature.placeType[0] : null
                    ));
                }
            }
        }

        return suggestions;
    }

    /**
     * Reverse geocode using Mapbox Geocoding API.
     * Cached for performance.
     */
    @Cacheable(value = "reverseGeocodeCache", key = "#latitude + ',' + #longitude")
    public ReverseGeocodeResult reverseGeocodeWithMapbox(double latitude, double longitude) {
        // Use sr-Latn for Latin script (not Cyrillic)
        String url = String.format(
                "https://api.mapbox.com/geocoding/v5/mapbox.places/%f,%f.json?access_token=%s&language=sr-Latn,en&types=address,poi,place",
                longitude, latitude, // Mapbox uses lon,lat order
                mapboxAccessToken
        );

        log.debug("Mapbox reverse geocode request for: {}, {}", latitude, longitude);

        MapboxGeocodeResponse response = restTemplate.getForObject(url, MapboxGeocodeResponse.class);

        if (response == null || response.features == null || response.features.isEmpty()) {
            return null;
        }

        MapboxFeature feature = response.features.get(0);
        return new ReverseGeocodeResult(
                feature.placeName,
                extractCityFromMapboxContext(feature),
                extractZipCodeFromMapboxContext(feature),
                latitude,
                longitude
        );
    }

    private String extractCityFromMapboxContext(MapboxFeature feature) {
        if (feature.context == null) return null;
        
        for (MapboxContext ctx : feature.context) {
            if (ctx.id != null && (ctx.id.startsWith("place.") || ctx.id.startsWith("locality."))) {
                return ctx.text;
            }
        }
        return null;
    }

    private String extractZipCodeFromMapboxContext(MapboxFeature feature) {
        if (feature.context == null) return null;
        
        for (MapboxContext ctx : feature.context) {
            if (ctx.id != null && ctx.id.startsWith("postcode.")) {
                return ctx.text;
            }
        }
        return null;
    }

    // ========== Nominatim Geocoding ==========

    /**
     * Geocode using Nominatim (OpenStreetMap).
     * Cached for performance and rate-limit compliance.
     */
    @Cacheable(value = "geocodeCache", key = "'nom-' + #query + '-' + #limit", unless = "#result.isEmpty()")
    public List<GeocodeSuggestion> geocodeWithNominatim(String query, int limit) {
        // Add Serbia context for better results
        String searchQuery = query.contains("Serbia") || query.contains("Srbija")
                ? query
                : query + ", Serbia";

        String url = String.format(
                "%s/search?q=%s&format=json&limit=%d&countrycodes=rs&addressdetails=1",
                nominatimBaseUrl,
                URLEncoder.encode(searchQuery, StandardCharsets.UTF_8),
                Math.min(limit, 10)
        );

        log.debug("Nominatim geocode request for query: {}", query);

        // Add User-Agent header (required by Nominatim) and Accept-Language for Latin script
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Rentoza/2.4.0 (https://rentoza.rs)");
        headers.set("Accept-Language", "sr-Latn,en;q=0.9");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<NominatimSearchResult[]> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, NominatimSearchResult[].class
        );

        NominatimSearchResult[] results = response.getBody();
        if (results == null || results.length == 0) {
            return List.of();
        }

        List<GeocodeSuggestion> suggestions = new ArrayList<>();
        for (NominatimSearchResult result : results) {
            try {
                double lat = Double.parseDouble(result.lat);
                double lon = Double.parseDouble(result.lon);

                // Validate within Serbia bounds
                if (lat >= SERBIA_MIN_LAT && lat <= SERBIA_MAX_LAT &&
                    lon >= SERBIA_MIN_LON && lon <= SERBIA_MAX_LON) {
                    
                    suggestions.add(new GeocodeSuggestion(
                            result.displayName,
                            lat,
                            lon,
                            extractCityFromNominatim(result),
                            extractZipCodeFromNominatim(result),
                            result.type
                    ));
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid coordinates in Nominatim result: {}", e.getMessage());
            }
        }

        return suggestions;
    }

    /**
     * Reverse geocode using Nominatim.
     * Cached for performance.
     */
    @Cacheable(value = "reverseGeocodeCache", key = "'nom-' + #latitude + ',' + #longitude")
    public ReverseGeocodeResult reverseGeocodeWithNominatim(double latitude, double longitude) {
        String url = String.format(
                "%s/reverse?lat=%f&lon=%f&format=json&addressdetails=1",
                nominatimBaseUrl,
                latitude,
                longitude
        );

        log.debug("Nominatim reverse geocode request for: {}, {}", latitude, longitude);

        // Add User-Agent header (required by Nominatim) and Accept-Language for Latin script
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Rentoza/2.4.0 (https://rentoza.rs)");
        headers.set("Accept-Language", "sr-Latn,en;q=0.9");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<NominatimReverseResult> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, NominatimReverseResult.class
        );

        NominatimReverseResult result = response.getBody();
        if (result == null || result.error != null) {
            return new ReverseGeocodeResult(
                    String.format("%.6f, %.6f", latitude, longitude),
                    null, null, latitude, longitude
            );
        }

        return new ReverseGeocodeResult(
                result.displayName,
                extractCityFromNominatimAddress(result.address),
                extractZipCodeFromNominatimAddress(result.address),
                latitude,
                longitude
        );
    }

    private String extractCityFromNominatim(NominatimSearchResult result) {
        if (result.address == null) return null;
        return extractCityFromNominatimAddress(result.address);
    }

    private String extractZipCodeFromNominatim(NominatimSearchResult result) {
        if (result.address == null) return null;
        return result.address.postcode;
    }

    private String extractCityFromNominatimAddress(NominatimAddress address) {
        if (address == null) return null;
        
        if (address.city != null) return address.city;
        if (address.town != null) return address.town;
        if (address.village != null) return address.village;
        if (address.municipality != null) return address.municipality;
        
        return null;
    }

    private String extractZipCodeFromNominatimAddress(NominatimAddress address) {
        if (address == null) return null;
        return address.postcode;
    }

    // ========== Rate Limiting ==========

    /**
     * Simple in-memory rate limiter.
     * Returns true if request is allowed, false if rate limit exceeded.
     */
    private boolean checkRateLimit(String userKey) {
        long now = System.currentTimeMillis();
        long windowStart = now - TimeUnit.SECONDS.toMillis(1);

        userRequestTimestamps.compute(userKey, (key, timestamps) -> {
            if (timestamps == null) {
                timestamps = new ArrayList<>();
            }
            // Remove timestamps outside the window
            timestamps.removeIf(ts -> ts < windowStart);
            return timestamps;
        });

        List<Long> timestamps = userRequestTimestamps.get(userKey);
        if (timestamps.size() >= RATE_LIMIT_PER_SECOND) {
            return false;
        }

        timestamps.add(now);
        return true;
    }

    // ========== Public DTOs ==========

    /**
     * Geocoding suggestion for autocomplete
     */
    public record GeocodeSuggestion(
            String displayName,
            double latitude,
            double longitude,
            String city,
            String zipCode,
            String type
    ) {}

    /**
     * Reverse geocoding result
     */
    public record ReverseGeocodeResult(
            String address,
            String city,
            String zipCode,
            double latitude,
            double longitude
    ) {}

    // ========== Mapbox Response DTOs ==========

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class MapboxGeocodeResponse {
        public List<MapboxFeature> features;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class MapboxFeature {
        public double[] center; // [longitude, latitude]
        
        @JsonProperty("place_name")
        public String placeName;
        
        @JsonProperty("place_type")
        public String[] placeType;
        
        public List<MapboxContext> context;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class MapboxContext {
        public String id;
        public String text;
    }

    // ========== Nominatim Response DTOs ==========

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class NominatimSearchResult {
        public String lat;
        public String lon;
        
        @JsonProperty("display_name")
        public String displayName;
        
        public String type;
        public NominatimAddress address;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class NominatimReverseResult {
        @JsonProperty("display_name")
        public String displayName;
        
        public NominatimAddress address;
        public String error;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class NominatimAddress {
        public String road;
        
        @JsonProperty("house_number")
        public String houseNumber;
        
        public String city;
        public String town;
        public String village;
        public String municipality;
        public String county;
        public String state;
        public String postcode;
        public String country;
        
        @JsonProperty("country_code")
        public String countryCode;
    }
}
