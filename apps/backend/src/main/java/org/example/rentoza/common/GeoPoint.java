package org.example.rentoza.common;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.PrePersist;
import jakarta.validation.ValidationException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Random;

/**
 * Geospatial location value object for embedding in entities.
 * 
 * <p>Provides immutable location snapshots with:
 * <ul>
 *   <li>Latitude/Longitude coordinates (DECIMAL precision)</li>
 *   <li>Human-readable address context</li>
 *   <li>Serbia bounds validation</li>
 *   <li>Haversine distance calculation</li>
 *   <li>Privacy obfuscation for unbooked guests</li>
 * </ul>
 * 
 * <p><b>Coordinate Order:</b>
 * PostGIS ST_DistanceSphere uses standard (longitude, latitude) order.
 * Native queries use: {@code ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)}
 * 
 * <p><b>Serbia Bounds:</b>
 * <ul>
 *   <li>Latitude: 42.2°N to 46.2°N</li>
 *   <li>Longitude: 18.8°E to 23.0°E</li>
 * </ul>
 * 
 * @see <a href="https://en.wikipedia.org/wiki/Haversine_formula">Haversine formula</a>
 */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeoPoint {

    // Serbia bounding box (approximate)
    private static final double SERBIA_MIN_LAT = 42.2;
    private static final double SERBIA_MAX_LAT = 46.2;
    private static final double SERBIA_MIN_LON = 18.8;
    private static final double SERBIA_MAX_LON = 23.0;

    // Earth radius in meters for Haversine
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    /**
     * Latitude in degrees (-90 to +90).
     * Precision: DECIMAL(10, 8) = ±1.1mm accuracy.
     */
    @Column(name = "latitude", precision = 10, scale = 8)
    private BigDecimal latitude;

    /**
     * Longitude in degrees (-180 to +180).
     * Precision: DECIMAL(11, 8) = ±1.1mm accuracy.
     */
    @Column(name = "longitude", precision = 11, scale = 8)
    private BigDecimal longitude;

    /**
     * Human-readable street address (from geocoding).
     * Example: "Terazije 26, Beograd"
     */
    @Column(name = "address", length = 255)
    private String address;

    /**
     * City name for UI grouping and search.
     * Example: "Belgrade", "Novi Sad", "Niš"
     */
    @Column(name = "city", length = 50)
    private String city;

    /**
     * Postal/ZIP code.
     * Example: "11000" (Belgrade), "21000" (Novi Sad)
     */
    @Column(name = "zip_code", length = 10)
    private String zipCode;

    /**
     * GPS accuracy in meters (null = unknown).
     * Used to assess location confidence from device GPS.
     */
    @Column(name = "accuracy_meters")
    private Integer accuracyMeters;

    /**
     * Constructor for coordinates only (without address info).
     * Useful for search queries and geofence validation.
     */
    public GeoPoint(BigDecimal latitude, BigDecimal longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /**
     * Constructor with double coordinates (convenience).
     */
    public GeoPoint(double latitude, double longitude) {
        this.latitude = BigDecimal.valueOf(latitude);
        this.longitude = BigDecimal.valueOf(longitude);
    }

    /**
     * Validate that this point is within Serbia bounds.
     * Throws ValidationException if coordinates are outside allowed range.
     * 
     * <p>Called automatically via @PrePersist on owning entity,
     * or manually before any location-critical operation.
     */
    @PrePersist
    public void validate() {
        if (latitude == null || longitude == null) {
            return; // Allow null (optional location)
        }

        double lat = latitude.doubleValue();
        double lon = longitude.doubleValue();

        if (lat < SERBIA_MIN_LAT || lat > SERBIA_MAX_LAT) {
            throw new ValidationException(
                String.format("Latitude %.6f is outside Serbia bounds (%.1f to %.1f)",
                    lat, SERBIA_MIN_LAT, SERBIA_MAX_LAT));
        }

        if (lon < SERBIA_MIN_LON || lon > SERBIA_MAX_LON) {
            throw new ValidationException(
                String.format("Longitude %.6f is outside Serbia bounds (%.1f to %.1f)",
                    lon, SERBIA_MIN_LON, SERBIA_MAX_LON));
        }
    }

    /**
     * Calculate Haversine distance to another point (in meters).
     * 
     * <p>The Haversine formula calculates the great-circle distance between
     * two points on a sphere given their latitudes and longitudes.
     * 
     * @param other The target point
     * @return Distance in meters (air distance, not driving distance)
     */
    public double distanceTo(GeoPoint other) {
        if (this.latitude == null || this.longitude == null ||
            other == null || other.getLatitude() == null || other.getLongitude() == null) {
            return Double.MAX_VALUE;
        }

        return haversineDistance(
            this.latitude.doubleValue(), this.longitude.doubleValue(),
            other.getLatitude().doubleValue(), other.getLongitude().doubleValue()
        );
    }

    /**
     * Static Haversine distance calculation.
     * 
     * @param lat1 Point 1 latitude (degrees)
     * @param lon1 Point 1 longitude (degrees)
     * @param lat2 Point 2 latitude (degrees)
     * @param lon2 Point 2 longitude (degrees)
     * @return Distance in meters
     */
    public static double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_METERS * c;
    }

    /**
     * Return obfuscated coordinates (randomized within ±radiusMeters) for privacy.
     * 
     * <p>Used when returning car locations to guests who haven't booked yet.
     * Prevents asset stalking by randomizing the exact position.
     * 
     * <p><b>Privacy Guarantee:</b>
     * <ul>
     *   <li>Actual location shifted by random bearing (0-360°)</li>
     *   <li>Random distance within [0, radiusMeters]</li>
     *   <li>City name preserved for UI grouping</li>
     *   <li>Exact address removed</li>
     * </ul>
     * 
     * @param random Random generator (use SecureRandom for production)
     * @param radiusMeters Maximum offset distance (typically 500m)
     * @return New GeoPoint with fuzzy coordinates
     */
    public GeoPoint obfuscate(Random random, int radiusMeters) {
        if (latitude == null || longitude == null) {
            return new GeoPoint(null, null, null, city, zipCode, null);
        }

        // Random bearing (0 to 360 degrees)
        double bearing = random.nextDouble() * 360;
        double bearingRad = Math.toRadians(bearing);

        // Random distance (0 to radiusMeters)
        double distance = random.nextDouble() * radiusMeters;

        // Calculate new position using spherical geometry
        double lat1 = Math.toRadians(latitude.doubleValue());
        double lon1 = Math.toRadians(longitude.doubleValue());
        double angularDistance = distance / EARTH_RADIUS_METERS;

        double newLat = Math.asin(
            Math.sin(lat1) * Math.cos(angularDistance)
            + Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearingRad)
        );

        double newLon = lon1 + Math.atan2(
            Math.sin(bearingRad) * Math.sin(angularDistance) * Math.cos(lat1),
            Math.cos(angularDistance) - Math.sin(lat1) * Math.sin(newLat)
        );

        return new GeoPoint(
            BigDecimal.valueOf(Math.toDegrees(newLat)),
            BigDecimal.valueOf(Math.toDegrees(newLon)),
            null,           // Address hidden
            city,           // City preserved for UI grouping
            null,           // ZIP code hidden
            null            // Accuracy unknown
        );
    }

    /**
     * Check if coordinates are set (non-null).
     */
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    /**
     * Check if this point is within Serbia bounds.
     */
    public boolean isInSerbia() {
        if (!hasCoordinates()) {
            return false;
        }

        double lat = latitude.doubleValue();
        double lon = longitude.doubleValue();

        return lat >= SERBIA_MIN_LAT && lat <= SERBIA_MAX_LAT
            && lon >= SERBIA_MIN_LON && lon <= SERBIA_MAX_LON;
    }

    /**
     * Create a GeoPoint from primitive doubles.
     */
    public static GeoPoint of(double latitude, double longitude) {
        return new GeoPoint(latitude, longitude);
    }

    /**
     * Create a GeoPoint with full address context.
     */
    public static GeoPoint of(double latitude, double longitude, String address, String city) {
        GeoPoint point = new GeoPoint(latitude, longitude);
        point.setAddress(address);
        point.setCity(city);
        return point;
    }

    @Override
    public String toString() {
        if (!hasCoordinates()) {
            return "GeoPoint[unset]";
        }
        return String.format("GeoPoint[%.6f, %.6f, %s]",
            latitude.doubleValue(), longitude.doubleValue(), city);
    }
}
