package org.example.rentoza.delivery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.example.rentoza.common.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

/**
 * OSRM (Open Source Routing Machine) client for calculating driving distances.
 * 
 * Uses the public OSRM demo server by default, with configurable URL for
 * self-hosted instances in production.
 * 
 * RATE LIMITS:
 * - Public demo server: ~20 requests/minute (unofficial limit)
 * - Self-hosted: unlimited (recommended for production)
 * 
 * FALLBACK BEHAVIOR:
 * When OSRM is unavailable or rate-limited, falls back to Haversine (great-circle)
 * distance with a 1.3x multiplier to approximate road distance.
 * 
 * @see <a href="http://project-osrm.org/docs/v5.5.1/api/">OSRM API Docs</a>
 * @since 2.4.0 (Geospatial Location Migration)
 */
@Service
public class OsrmRoutingService {

    private static final Logger log = LoggerFactory.getLogger(OsrmRoutingService.class);

    /**
     * Multiplier to convert Haversine distance to approximate road distance.
     * Based on analysis of Serbian road network vs straight-line distances.
     * Urban areas: ~1.2x, Rural/mountain: ~1.5x, Average: ~1.3x
     */
    private static final double HAVERSINE_TO_ROAD_MULTIPLIER = 1.3;

    private final RestTemplate restTemplate;

    @Value("${rentoza.osrm.base-url:https://router.project-osrm.org}")
    private String osrmBaseUrl;

    @Value("${rentoza.osrm.enabled:true}")
    private boolean osrmEnabled;

    public OsrmRoutingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Calculate driving distance between two points using OSRM.
     * 
     * Results are cached for 24 hours (route distance doesn't change frequently).
     * 
     * @param origin      Start point
     * @param destination End point
     * @return Driving distance in kilometers, or empty if calculation failed
     */
    @Cacheable(value = "osrm-routes", key = "#origin.latitude + ',' + #origin.longitude + '-' + #destination.latitude + ',' + #destination.longitude")
    public Optional<RoutingResult> calculateRoute(GeoPoint origin, GeoPoint destination) {
        if (!osrmEnabled) {
            log.debug("OSRM disabled, using Haversine fallback");
            return Optional.of(createHaversineFallback(origin, destination));
        }

        if (origin == null || destination == null) {
            log.warn("Cannot calculate route: null origin or destination");
            return Optional.empty();
        }

        try {
            // OSRM expects coordinates as lon,lat (not lat,lon!)
            String url = String.format(
                    "%s/route/v1/driving/%f,%f;%f,%f?overview=false&alternatives=false",
                    osrmBaseUrl,
                    origin.getLongitude(), origin.getLatitude(),
                    destination.getLongitude(), destination.getLatitude()
            );

            log.debug("OSRM request: {}", url);

            OsrmResponse response = restTemplate.getForObject(url, OsrmResponse.class);

            if (response != null && "Ok".equals(response.code) && 
                response.routes != null && !response.routes.isEmpty()) {
                
                OsrmRoute route = response.routes.get(0);
                double distanceKm = route.distance / 1000.0;
                double durationMinutes = route.duration / 60.0;

                log.debug("OSRM route: {} km, {} min", 
                        String.format("%.2f", distanceKm),
                        String.format("%.1f", durationMinutes));

                return Optional.of(new RoutingResult(
                        distanceKm,
                        durationMinutes,
                        RoutingSource.OSRM
                ));
            }

            log.warn("OSRM returned no routes, falling back to Haversine");
            return Optional.of(createHaversineFallback(origin, destination));

        } catch (RestClientException e) {
            log.warn("OSRM request failed: {}. Falling back to Haversine", e.getMessage());
            return Optional.of(createHaversineFallback(origin, destination));
        }
    }

    /**
     * Calculate driving distance with automatic Haversine fallback.
     * This method always returns a result (never empty).
     * 
     * @param origin      Start point
     * @param destination End point
     * @return Routing result (guaranteed non-null)
     */
    public RoutingResult calculateRouteWithFallback(GeoPoint origin, GeoPoint destination) {
        return calculateRoute(origin, destination)
                .orElseGet(() -> createHaversineFallback(origin, destination));
    }

    /**
     * Calculate straight-line distance with road multiplier.
     * Used as fallback when OSRM is unavailable.
     */
    private RoutingResult createHaversineFallback(GeoPoint origin, GeoPoint destination) {
        double haversineKm = origin.distanceTo(destination);
        double estimatedRoadKm = haversineKm * HAVERSINE_TO_ROAD_MULTIPLIER;

        // Estimate duration: assume 50 km/h average speed (Serbian road conditions)
        double estimatedMinutes = (estimatedRoadKm / 50.0) * 60.0;

        log.debug("Haversine fallback: {} km straight-line -> {} km estimated road distance",
                String.format("%.2f", haversineKm),
                String.format("%.2f", estimatedRoadKm));

        return new RoutingResult(
                estimatedRoadKm,
                estimatedMinutes,
                RoutingSource.HAVERSINE_FALLBACK
        );
    }

    /**
     * Get straight-line (Haversine) distance only, without road calculation.
     */
    public double getHaversineDistance(GeoPoint origin, GeoPoint destination) {
        return origin.distanceTo(destination);
    }

    // ========== RESPONSE DTOs ==========

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OsrmResponse {
        @JsonProperty("code")
        public String code;

        @JsonProperty("routes")
        public List<OsrmRoute> routes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OsrmRoute {
        @JsonProperty("distance")
        public double distance; // meters

        @JsonProperty("duration")
        public double duration; // seconds
    }

    // ========== RESULT CLASSES ==========

    /**
     * Result of a routing calculation
     */
    public record RoutingResult(
            double distanceKm,
            double durationMinutes,
            RoutingSource source
    ) {
        /**
         * Check if this result came from OSRM (accurate) or fallback (estimated)
         */
        public boolean isAccurate() {
            return source == RoutingSource.OSRM;
        }
    }

    /**
     * Source of routing data
     */
    public enum RoutingSource {
        /**
         * Calculated by OSRM routing engine (accurate road distance)
         */
        OSRM,

        /**
         * Estimated from Haversine with multiplier (less accurate)
         */
        HAVERSINE_FALLBACK
    }
}
