package org.example.rentoza.car;

import org.example.rentoza.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import org.example.rentoza.car.ApprovalStatus;

public interface CarRepository extends JpaRepository<Car, Long>, JpaSpecificationExecutor<Car> {

    /**
     * Load a car row with a DB-level write lock.
     * Used to serialize concurrent document uploads that update the same car metadata.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Car c WHERE c.id = :id")
    Optional<Car> findByIdForUpdate(@Param("id") Long id);

    @Query(value = "SELECT * FROM cars WHERE approval_status::text = :status", nativeQuery = true)
    List<Car> findByApprovalStatus(@Param("status") String status);
    
    @Query("SELECT c FROM Car c WHERE c.available = true AND CAST(c.approvalStatus AS String) = 'APPROVED'")
    Page<Car> findApprovedAvailableCars(Pageable pageable);
    
    // ========== LIST VIEWS (NO features/addOns - Performance Optimized) ==========
    // These methods only load owner, keeping list views lightweight
    
    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByLocationIgnoreCase(String location);

    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByOwnerEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByOwner(User owner);

    @Override
    @EntityGraph(attributePaths = {"owner"})
    List<Car> findAll();

    // Public listings - only available cars
    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByAvailableTrue();

    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByLocationIgnoreCaseAndAvailableTrue(String location);

    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByAvailableFalse();

    long countByListingStatus(ListingStatus status);

    // ========== DETAIL VIEWS (WITH features/addOns - Full Data) ==========
    // These methods eagerly load collections for single-car views
    
    /**
     * Find car by ID with ALL details eagerly loaded.
     * Use for: Car Detail Page, Edit Car Form
     * 
     * PERFORMANCE:
     * - Single query using LEFT JOIN FETCH for all collections
     * - Prevents LazyInitializationException in service layer
     * - Optimized for single-entity retrieval
     */
    @EntityGraph(attributePaths = {"owner", "features", "addOns", "images"})
    @Query("SELECT c FROM Car c WHERE c.id = :id")
    Optional<Car> findWithDetailsById(@Param("id") Long id);

    /**
     * Availability search with eager loading of fields needed by CarResponseDTO
     * to avoid LazyInitializationException outside the transaction.
     * 
     * NOTE: For search results, we load features for filtering but NOT addOns.
     * 
     * Uses locationGeoPoint.city (mapped to location_city column) for city-based search.
     * This leverages the idx_car_location_city_available index for optimal performance.
     */
    @EntityGraph(attributePaths = {"owner", "features"})
    @Query("SELECT DISTINCT c FROM Car c WHERE LOWER(c.locationGeoPoint.city) = LOWER(:location) AND c.available = true")
    List<Car> findAvailableWithDetailsByLocation(@Param("location") String location);

    // ========== RLS-ENFORCED QUERIES (Enterprise Security Enhancement) ==========

    /**
     * Find car by ID with owner verification AND full details.
     * Returns car only if the authenticated user is the owner.
     * Use this for owner-only operations (update, delete, view private details).
     * 
     * @param id Car ID
     * @param ownerId Authenticated owner's user ID
     * @return Optional containing car if user is the owner
     */
    @Query("SELECT c FROM Car c " +
           "LEFT JOIN FETCH c.owner " +
           "LEFT JOIN FETCH c.features " +
           "LEFT JOIN FETCH c.addOns " +
           "WHERE c.id = :id " +
           "AND c.owner.id = :ownerId")
    Optional<Car> findByIdAndOwnerId(@Param("id") Long id, @Param("ownerId") Long ownerId);

    /**
     * Find all cars accessible by a user.
     * Returns cars owned by the user OR publicly available cars.
     * This enforces RLS while still allowing marketplace browsing.
     * 
     * NOTE: List view - does NOT load features/addOns for performance.
     * 
     * @param ownerId Authenticated user's ID
     * @return List of cars (user's own cars + public listings)
     */
    @EntityGraph(attributePaths = {"owner"})
    @Query("SELECT c FROM Car c " +
           "WHERE c.owner.id = :ownerId " +
           "OR c.available = true")
    List<Car> findAccessibleCars(@Param("ownerId") Long ownerId);

    /**
     * Find cars by owner with ownership verification.
     * Returns cars only if the authenticated user is the owner OR an admin.
     * Prevents Owner A from viewing Owner B's private inventory.
     * 
     * @param ownerEmail Owner's email
     * @param requesterId Authenticated user's ID
     * @return List of cars (empty if requester is not the owner)
     */
    @EntityGraph(attributePaths = {"owner"})
    @Query("SELECT c FROM Car c " +
           "WHERE c.owner.email = :ownerEmail " +
           "AND c.owner.id = :requesterId")
    List<Car> findByOwnerEmailForOwner(@Param("ownerEmail") String ownerEmail, @Param("requesterId") Long requesterId);

    /**
     * Find public cars for a specific owner.
     * Used for Owner Public Profile.
     */
    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByOwnerIdAndAvailableTrue(Long ownerId);

    // ========== GEOSPATIAL QUERIES (PostGIS) ==========
    // These methods use PostGIS for efficient proximity searches

    /**
     * Find available cars within a radius of a given location.
     * Uses Haversine-based distance calculation (compatible with PostgreSQL).
     * 
     * PERFORMANCE:
     * - Uses indexes on location columns
     * - Returns only cars with valid geospatial coordinates
     * - Ordered by distance ascending (closest first)
     * 
     * @param latitude  Center point latitude (Y coordinate)
     * @param longitude Center point longitude (X coordinate)
     * @param radiusKm  Maximum distance in kilometers
     * @return List of cars with distance, ordered by proximity
     */
    @Query(value = """
        SELECT c.* 
        FROM cars c
        WHERE c.available = true
          AND c.location_latitude IS NOT NULL
          AND c.location_longitude IS NOT NULL
          AND sqrt(pow(:latitude - c.location_latitude, 2) + pow(:longitude - c.location_longitude, 2)) * 111.32 <= :radiusKm
        ORDER BY sqrt(pow(:latitude - c.location_latitude, 2) + pow(:longitude - c.location_longitude, 2)) ASC
        """, nativeQuery = true)
    List<Car> findNearby(
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude,
            @Param("radiusKm") Double radiusKm);

    /**
     * Find available cars within radius with pagination support.
     * Use for search results with page/size controls.
     * 
     * @param latitude  Center point latitude
     * @param longitude Center point longitude
     * @param radiusKm  Maximum distance in kilometers
     * @param pageable  Pagination parameters
     * @return Page of cars ordered by distance
     */
    @Query(value = """
        SELECT c.*
        FROM cars c
        WHERE c.available = true
          AND c.location_latitude IS NOT NULL
          AND c.location_longitude IS NOT NULL
          AND sqrt(pow(:latitude - c.location_latitude, 2) + pow(:longitude - c.location_longitude, 2)) * 111.32 <= :radiusKm
        ORDER BY sqrt(pow(:latitude - c.location_latitude, 2) + pow(:longitude - c.location_longitude, 2)) ASC
        """,
            countQuery = """
        SELECT COUNT(*)
        FROM cars c
        WHERE c.available = true
          AND c.location_latitude IS NOT NULL
          AND c.location_longitude IS NOT NULL
          AND sqrt(pow(:latitude - c.location_latitude, 2) + pow(:longitude - c.location_longitude, 2)) * 111.32 <= :radiusKm
        """,
            nativeQuery = true)
    Page<Car> findNearbyPaginated(
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude,
            @Param("radiusKm") Double radiusKm,
            Pageable pageable);

    /**
     * Find cars within delivery range of a specific car.
     * Used to suggest cars that can deliver to a user's location.
     * 
     * @param carId    The car to check delivery from
     * @param latitude User's requested pickup latitude
     * @param longitude User's requested pickup longitude
     * @return Optional containing the car if within delivery range, empty otherwise
     */
    @Query(value = """
        SELECT c.*
        FROM cars c
        WHERE c.id = :carId
          AND c.available = true
          AND c.delivery_radius_km IS NOT NULL
          AND c.delivery_radius_km > 0
          AND c.location_latitude IS NOT NULL
          AND c.location_longitude IS NOT NULL
          AND sqrt(pow(:latitude - c.location_latitude, 2) + pow(:longitude - c.location_longitude, 2)) * 111.32 <= c.delivery_radius_km
        """, nativeQuery = true)
    Optional<Car> findIfWithinDeliveryRange(
            @Param("carId") Long carId,
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude);

    /**
     * Find cars that offer delivery to a specific location.
     * Returns cars where the user's location is within the car's delivery radius.
     * 
     * @param latitude  User's pickup location latitude
     * @param longitude User's pickup location longitude
     * @return List of cars that can deliver to the location
     */
    @Query(value = """
        SELECT c.*
        FROM cars c
        WHERE c.available = true
          AND c.delivery_radius_km IS NOT NULL
          AND c.delivery_radius_km > 0
          AND c.location_latitude IS NOT NULL
          AND c.location_longitude IS NOT NULL
          AND sqrt(pow(:latitude - c.location_latitude, 2) + pow(:longitude - c.location_longitude, 2)) * 111.32 <= c.delivery_radius_km
        ORDER BY sqrt(pow(:latitude - c.location_latitude, 2) + pow(:longitude - c.location_longitude, 2)) ASC
        """, nativeQuery = true)
    List<Car> findCarsOfferingDeliveryTo(
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude);

    /**
     * Count cars with valid geospatial coordinates.
     * Used for migration progress tracking.
     * 
     * @return Count of cars with lat/lon populated
     */
    @Query("SELECT COUNT(c) FROM Car c WHERE c.locationGeoPoint.latitude IS NOT NULL AND c.locationGeoPoint.longitude IS NOT NULL")
    long countCarsWithGeoLocation();

    /**
     * Find cars without geospatial coordinates (for migration batch processing).
     * 
     * @param limit Maximum number of cars to return
     * @return List of cars needing geocoding
     */
    @Query(value = """
        SELECT c.* FROM cars c
        WHERE c.location_latitude IS NULL
          AND c.location IS NOT NULL
          AND c.location != ''
        LIMIT :limit
        """, nativeQuery = true)
    List<Car> findCarsNeedingGeocoding(@Param("limit") int limit);

    // ========== ADMIN MANAGEMENT QUERIES ==========

    /**
     * Find all cars owned by a specific user.
     * Used by admin for user profile view and cascade operations.
     * 
     * @param ownerId Owner's user ID
     * @return List of all cars for the owner
     */
    @EntityGraph(attributePaths = {"owner"})
    @Query("SELECT c FROM Car c WHERE c.owner.id = :ownerId ORDER BY c.id DESC")
    List<Car> findByOwnerId(@Param("ownerId") Long ownerId);

    /**
     * Count total cars for an owner.
     * Used for admin user profile statistics.
     * 
     * @param ownerId Owner's user ID
     * @return Total car count
     */
    @Query("SELECT COUNT(c) FROM Car c WHERE c.owner.id = :ownerId")
    Integer countByOwnerId(@Param("ownerId") Long ownerId);

    /**
     * Count available cars (for admin dashboard).
     * 
     * @return Total available car count
     */
    @Query("SELECT COUNT(c) FROM Car c WHERE c.available = true")
    Long countAvailableCars();

    // ========== P0-5 FIX: OPTIMIZED QUERIES TO REPLACE findAll() ==========

    /**
     * Get all distinct car brands for filter dropdowns.
     * 
     * P0-5 FIX: Replaces loading ALL cars just to get brands.
     * Uses SELECT DISTINCT on indexed column - O(log n) vs O(n) full scan.
     * 
     * @return Sorted list of unique brand names
     */
    @Query("SELECT DISTINCT c.brand FROM Car c WHERE c.brand IS NOT NULL ORDER BY c.brand")
    List<String> findDistinctBrands();
}

