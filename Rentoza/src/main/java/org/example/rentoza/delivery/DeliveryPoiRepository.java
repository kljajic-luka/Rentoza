package org.example.rentoza.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for DeliveryPoi entities with SPATIAL query support.
 * 
 * @see DeliveryPoi
 * @since 2.4.0 (Geospatial Location Migration)
 */
public interface DeliveryPoiRepository extends JpaRepository<DeliveryPoi, Long> {

    /**
     * Find a POI by its unique code
     */
    Optional<DeliveryPoi> findByCode(String code);

    /**
     * Find all active POIs
     */
    List<DeliveryPoi> findByActiveTrue();

    /**
     * Find active POIs by type
     */
    List<DeliveryPoi> findByPoiTypeAndActiveTrue(DeliveryPoi.PoiType poiType);

    /**
     * Find all POIs that contain a given point within their radius.
     * Uses MySQL ST_Distance_Sphere for accurate distance calculation.
     * 
     * Returns POIs ordered by priority (descending) so the highest-priority
     * POI can be selected for fee calculation.
     * 
     * CRITICAL: MySQL POINT uses (longitude, latitude) order!
     * 
     * @param latitude  Point latitude
     * @param longitude Point longitude
     * @return List of POIs containing the point, ordered by priority desc
     */
    @Query(value = """
        SELECT p.* FROM delivery_pois p
        WHERE p.active = true
          AND ST_Distance_Sphere(
                  ST_PointFromText(CONCAT('POINT(', p.longitude, ' ', p.latitude, ')'), 4326),
                  ST_PointFromText(CONCAT('POINT(', :longitude, ' ', :latitude, ')'), 4326)
              ) <= p.radius_km * 1000
        ORDER BY p.priority DESC, p.radius_km ASC
        """, nativeQuery = true)
    List<DeliveryPoi> findPoisContainingPoint(
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude);

    /**
     * Find the highest-priority POI that contains a given point.
     * Convenience method that returns only the single most relevant POI.
     * 
     * @param latitude  Point latitude
     * @param longitude Point longitude
     * @return Optional containing the highest-priority POI, if any
     */
    @Query(value = """
        SELECT p.* FROM delivery_pois p
        WHERE p.active = true
          AND ST_Distance_Sphere(
                  ST_PointFromText(CONCAT('POINT(', p.longitude, ' ', p.latitude, ')'), 4326),
                  ST_PointFromText(CONCAT('POINT(', :longitude, ' ', :latitude, ')'), 4326)
              ) <= p.radius_km * 1000
        ORDER BY p.priority DESC, p.radius_km ASC
        LIMIT 1
        """, nativeQuery = true)
    Optional<DeliveryPoi> findHighestPriorityPoiAtPoint(
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude);

    /**
     * Find POIs near a point, even if the point is outside the POI radius.
     * Useful for suggesting nearby POI pickup points to users.
     * 
     * @param latitude    Point latitude
     * @param longitude   Point longitude
     * @param maxDistance Maximum distance in km to search
     * @return List of nearby POIs with distance
     */
    @Query(value = """
        SELECT p.*,
               ST_Distance_Sphere(
                   ST_PointFromText(CONCAT('POINT(', p.longitude, ' ', p.latitude, ')'), 4326),
                   ST_PointFromText(CONCAT('POINT(', :longitude, ' ', :latitude, ')'), 4326)
               ) / 1000 AS distance_km
        FROM delivery_pois p
        WHERE p.active = true
          AND ST_Distance_Sphere(
                  ST_PointFromText(CONCAT('POINT(', p.longitude, ' ', p.latitude, ')'), 4326),
                  ST_PointFromText(CONCAT('POINT(', :longitude, ' ', :latitude, ')'), 4326)
              ) <= :maxDistanceKm * 1000
        ORDER BY distance_km ASC
        """, nativeQuery = true)
    List<DeliveryPoi> findPoisNearPoint(
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude,
            @Param("maxDistanceKm") Double maxDistanceKm);

    /**
     * Find all airports (for special airport pickup handling)
     */
    default List<DeliveryPoi> findActiveAirports() {
        return findByPoiTypeAndActiveTrue(DeliveryPoi.PoiType.AIRPORT);
    }

    /**
     * Check if a point is within any active POI
     */
    @Query(value = """
        SELECT COUNT(*) > 0 FROM delivery_pois p
        WHERE p.active = true
          AND ST_Distance_Sphere(
                  ST_PointFromText(CONCAT('POINT(', p.longitude, ' ', p.latitude, ')'), 4326),
                  ST_PointFromText(CONCAT('POINT(', :longitude, ' ', :latitude, ')'), 4326)
              ) <= p.radius_km * 1000
        """, nativeQuery = true)
    boolean isPointWithinAnyPoi(
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude);
}
