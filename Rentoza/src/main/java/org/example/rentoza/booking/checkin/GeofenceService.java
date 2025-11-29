package org.example.rentoza.booking.checkin;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Service for geofence validation during check-in handshake.
 * 
 * <h2>Purpose</h2>
 * <p>Validates that the guest is physically present near the car during
 * remote handoff (lockbox key pickup). Uses Haversine formula for accurate
 * great-circle distance calculation.
 * 
 * <h2>Dynamic Radius (GPS Drift Fix)</h2>
 * <p>Urban canyons (New Belgrade high-rises, Vračar narrow streets) can cause
 * 50m+ GPS drift due to multipath interference. We dynamically adjust the
 * geofence radius based on location type:
 * <ul>
 *   <li><b>Urban (dense):</b> 150m radius - high-rise areas with GPS multipath</li>
 *   <li><b>Suburban:</b> 100m radius - residential areas</li>
 *   <li><b>Rural:</b> 50m radius - open areas with better GPS accuracy</li>
 * </ul>
 * 
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code app.checkin.geofence.threshold-meters}: Default radius</li>
 *   <li>{@code app.checkin.geofence.dynamic-radius-enabled}: Enable density-based radius</li>
 *   <li>{@code app.checkin.geofence.strict}: Block or warn on violation</li>
 * </ul>
 *
 * @see CheckInService#confirmHandshake
 */
@Service
@Slf4j
public class GeofenceService {

    private static final double EARTH_RADIUS_METERS = 6371000.0;

    @Value("${app.checkin.geofence.threshold-meters:100}")
    private int defaultRadiusMeters;

    @Value("${app.checkin.geofence.radius-urban-meters:150}")
    private int urbanRadiusMeters;

    @Value("${app.checkin.geofence.radius-suburban-meters:100}")
    private int suburbanRadiusMeters;

    @Value("${app.checkin.geofence.radius-rural-meters:50}")
    private int ruralRadiusMeters;

    @Value("${app.checkin.geofence.dynamic-radius-enabled:true}")
    private boolean dynamicRadiusEnabled;

    @Value("${app.checkin.geofence.strict:false}")
    private boolean strictMode;

    /**
     * Location density categories for dynamic radius calculation.
     */
    public enum LocationDensity {
        URBAN,      // High-rise areas, narrow streets (GPS multipath issues)
        SUBURBAN,   // Residential areas
        RURAL       // Open areas (better GPS accuracy)
    }

    /**
     * Validate that guest is within allowed radius of car location.
     * Uses default radius (no dynamic adjustment).
     * 
     * @param carLat      Car's GPS latitude
     * @param carLon      Car's GPS longitude
     * @param guestLat    Guest's GPS latitude
     * @param guestLon    Guest's GPS longitude
     * @return Geofence validation result
     */
    public GeofenceResult validateProximity(
            BigDecimal carLat, BigDecimal carLon,
            BigDecimal guestLat, BigDecimal guestLon) {
        return validateProximity(carLat, carLon, guestLat, guestLon, null);
    }

    /**
     * Validate that guest is within allowed radius of car location.
     * Uses dynamic radius based on location density if enabled.
     * 
     * @param carLat      Car's GPS latitude
     * @param carLon      Car's GPS longitude
     * @param guestLat    Guest's GPS latitude
     * @param guestLon    Guest's GPS longitude
     * @param density     Location density for dynamic radius (null = use default)
     * @return Geofence validation result
     */
    public GeofenceResult validateProximity(
            BigDecimal carLat, BigDecimal carLon,
            BigDecimal guestLat, BigDecimal guestLon,
            LocationDensity density) {
        
        // Determine effective radius
        int effectiveRadius = calculateEffectiveRadius(density);
        
        if (carLat == null || carLon == null) {
            log.debug("[Geofence] Car location not set, skipping validation");
            return GeofenceResult.builder()
                .withinRadius(true)
                .distanceMeters(0)
                .requiredRadiusMeters(effectiveRadius)
                .skipped(true)
                .reason("Car location not set")
                .locationDensity(density)
                .dynamicRadiusApplied(false)
                .build();
        }
        
        if (guestLat == null || guestLon == null) {
            if (strictMode) {
                return GeofenceResult.builder()
                    .withinRadius(false)
                    .distanceMeters(-1)
                    .requiredRadiusMeters(effectiveRadius)
                    .skipped(false)
                    .reason("Lokacija gosta nije dostupna")
                    .locationDensity(density)
                    .dynamicRadiusApplied(dynamicRadiusEnabled && density != null)
                    .build();
            } else {
                log.debug("[Geofence] Guest location not available, skipping in non-strict mode");
                return GeofenceResult.builder()
                    .withinRadius(true)
                    .distanceMeters(-1)
                    .requiredRadiusMeters(effectiveRadius)
                    .skipped(true)
                    .reason("Guest location not available")
                    .locationDensity(density)
                    .dynamicRadiusApplied(false)
                    .build();
            }
        }
        
        int distance = (int) Math.round(haversineDistance(
            carLat.doubleValue(), carLon.doubleValue(),
            guestLat.doubleValue(), guestLon.doubleValue()
        ));
        
        boolean withinRadius = distance <= effectiveRadius;
        
        log.info("[Geofence] Distance: {}m, Radius: {}m (density={}), Within: {}", 
            distance, effectiveRadius, density, withinRadius);
        
        String reason = null;
        if (!withinRadius) {
            reason = String.format("Morate biti unutar %dm od vozila. Trenutna udaljenost: %dm",
                effectiveRadius, distance);
        }
        
        return GeofenceResult.builder()
            .withinRadius(withinRadius)
            .distanceMeters(distance)
            .requiredRadiusMeters(effectiveRadius)
            .skipped(false)
            .reason(reason)
            .locationDensity(density)
            .dynamicRadiusApplied(dynamicRadiusEnabled && density != null)
            .build();
    }

    /**
     * Calculate effective radius based on location density.
     * 
     * @param density Location density (null = use default)
     * @return Effective radius in meters
     */
    private int calculateEffectiveRadius(LocationDensity density) {
        if (!dynamicRadiusEnabled || density == null) {
            return defaultRadiusMeters;
        }
        
        return switch (density) {
            case URBAN -> urbanRadiusMeters;
            case SUBURBAN -> suburbanRadiusMeters;
            case RURAL -> ruralRadiusMeters;
        };
    }

    /**
     * Infer location density from GPS coordinates.
     * 
     * <p>This is a simplified heuristic based on known Serbian urban areas.
     * For production, consider using a proper geocoding service or
     * population density data.
     * 
     * <h3>Known Urban Areas (GPS multipath prone):</h3>
     * <ul>
     *   <li>Belgrade (New Belgrade high-rises, Vračar narrow streets)</li>
     *   <li>Novi Sad city center</li>
     *   <li>Niš city center</li>
     * </ul>
     * 
     * @param lat Latitude
     * @param lon Longitude
     * @return Inferred location density
     */
    public LocationDensity inferLocationDensity(BigDecimal lat, BigDecimal lon) {
        if (lat == null || lon == null) {
            return null;
        }
        
        double latitude = lat.doubleValue();
        double longitude = lon.doubleValue();
        
        // Belgrade metropolitan area (approximate bounds)
        // New Belgrade: ~44.80-44.83 lat, ~20.38-20.44 lon (high-rises)
        // Vračar/Old Town: ~44.78-44.82 lat, ~20.46-20.50 lon (narrow streets)
        if (latitude >= 44.75 && latitude <= 44.90 && 
            longitude >= 20.35 && longitude <= 20.55) {
            return LocationDensity.URBAN;
        }
        
        // Novi Sad city center
        if (latitude >= 45.24 && latitude <= 45.27 && 
            longitude >= 19.82 && longitude <= 19.86) {
            return LocationDensity.URBAN;
        }
        
        // Niš city center
        if (latitude >= 43.31 && latitude <= 43.33 && 
            longitude >= 21.88 && longitude <= 21.92) {
            return LocationDensity.URBAN;
        }
        
        // Other Serbian cities (suburban approximation)
        // Kragujevac, Subotica, Zrenjanin, Pančevo
        if (latitude >= 42.5 && latitude <= 46.0 && 
            longitude >= 19.0 && longitude <= 23.0) {
            // Default to suburban for other Serbian locations
            return LocationDensity.SUBURBAN;
        }
        
        // Outside known areas - use rural (best GPS accuracy)
        return LocationDensity.RURAL;
    }

    /**
     * Calculate distance between two GPS coordinates using Haversine formula.
     * 
     * <p>The Haversine formula calculates the great-circle distance between
     * two points on a sphere given their latitudes and longitudes.
     * 
     * @param lat1 First point latitude (degrees)
     * @param lon1 First point longitude (degrees)
     * @param lat2 Second point latitude (degrees)
     * @param lon2 Second point longitude (degrees)
     * @return Distance in meters
     */
    public double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return EARTH_RADIUS_METERS * c;
    }

    /**
     * Check if strict geofence mode is enabled.
     * In strict mode, failed geofence checks block the handshake.
     * In non-strict mode, failed checks generate warnings but allow continuation.
     */
    public boolean isStrictMode() {
        return strictMode;
    }

    /**
     * Get the default geofence radius.
     */
    public int getDefaultRadiusMeters() {
        return defaultRadiusMeters;
    }

    /**
     * Result of geofence validation.
     */
    @Data
    @Builder
    public static class GeofenceResult {
        /** True if guest is within allowed radius or validation was skipped */
        private boolean withinRadius;
        
        /** Calculated distance in meters (-1 if coordinates not available) */
        private int distanceMeters;
        
        /** Required radius in meters (may be dynamically adjusted) */
        private int requiredRadiusMeters;
        
        /** True if validation was skipped (missing coordinates in non-strict mode) */
        private boolean skipped;
        
        /** Human-readable reason (error message or skip reason) */
        private String reason;
        
        /** Location density used for dynamic radius (null if not inferred) */
        private LocationDensity locationDensity;
        
        /** True if dynamic radius was applied based on location density */
        private boolean dynamicRadiusApplied;
        
        /**
         * Check if handshake should be blocked based on this result.
         */
        public boolean shouldBlock() {
            return !withinRadius && !skipped;
        }
    }
}
