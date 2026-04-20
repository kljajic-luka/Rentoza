package org.example.rentoza.favorite;

import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Favorite entity with optimized queries
 */
@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    /**
     * Check if a specific car is favorited by user ID and car ID
     */
    @Query("SELECT CASE WHEN COUNT(f) > 0 THEN true ELSE false END " +
           "FROM Favorite f WHERE f.user.id = :userId AND f.car.id = :carId")
    boolean existsByUserIdAndCarId(@Param("userId") Long userId, @Param("carId") Long carId);

    /**
     * Find a favorite by user and car
     */
    Optional<Favorite> findByUserAndCar(User user, Car car);

    /**
     * Find a favorite by user ID and car ID
     */
    @Query("SELECT f FROM Favorite f WHERE f.user.id = :userId AND f.car.id = :carId")
    Optional<Favorite> findByUserIdAndCarId(@Param("userId") Long userId, @Param("carId") Long carId);

    /**
     * Get all favorites for a user with car details eagerly loaded
     */
    @Query("SELECT f FROM Favorite f " +
           "JOIN FETCH f.car c " +
           "WHERE f.user.id = :userId " +
           "ORDER BY f.createdAt DESC")
    List<Favorite> findAllByUserIdWithCarDetails(@Param("userId") Long userId);

    /**
     * Get all car IDs favorited by a user (lightweight query)
     */
    @Query("SELECT f.car.id FROM Favorite f WHERE f.user.id = :userId")
    List<Long> findFavoritedCarIdsByUserId(@Param("userId") Long userId);

    /**
     * Count favorites for a specific car
     */
    @Query("SELECT COUNT(f) FROM Favorite f WHERE f.car.id = :carId")
    long countByCarId(@Param("carId") Long carId);

    /**
     * Count favorites for a user
     */
    @Query("SELECT COUNT(f) FROM Favorite f WHERE f.user.id = :userId")
    long countByUserId(@Param("userId") Long userId);

    /**
     * Delete a favorite by user and car
     */
    @Modifying
    @Query("DELETE FROM Favorite f WHERE f.user.id = :userId AND f.car.id = :carId")
    void deleteByUserIdAndCarId(@Param("userId") Long userId, @Param("carId") Long carId);

    /**
     * Delete all favorites for a user
     */
    @Modifying
    @Query("DELETE FROM Favorite f WHERE f.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    /**
     * Delete all favorites for a car
     */
    @Modifying
    @Query("DELETE FROM Favorite f WHERE f.car.id = :carId")
    void deleteAllByCarId(@Param("carId") Long carId);
}
