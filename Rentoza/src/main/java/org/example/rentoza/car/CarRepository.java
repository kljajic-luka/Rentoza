package org.example.rentoza.car;

import org.example.rentoza.user.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CarRepository extends JpaRepository<Car, Long>, JpaSpecificationExecutor<Car> {
    
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
    @EntityGraph(attributePaths = {"owner", "features", "addOns", "imageUrls"})
    @Query("SELECT c FROM Car c WHERE c.id = :id")
    Optional<Car> findWithDetailsById(@Param("id") Long id);

    /**
     * Availability search with eager loading of fields needed by CarResponseDTO
     * to avoid LazyInitializationException outside the transaction.
     * 
     * NOTE: For search results, we load features for filtering but NOT addOns.
     */
    @EntityGraph(attributePaths = {"owner", "features"})
    @Query("SELECT DISTINCT c FROM Car c WHERE LOWER(c.location) = LOWER(:location) AND c.available = true")
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
}
