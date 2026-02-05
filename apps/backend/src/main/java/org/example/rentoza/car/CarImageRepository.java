package org.example.rentoza.car;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository for car images.
 * 
 * <p>Supports ordered retrieval and batch operations for image management.
 */
public interface CarImageRepository extends JpaRepository<CarImage, Long> {

    /**
     * Find all images for a car, ordered by display order.
     */
    @Query("SELECT ci FROM CarImage ci WHERE ci.car.id = :carId ORDER BY ci.displayOrder ASC")
    List<CarImage> findByCarIdOrderByDisplayOrder(@Param("carId") Long carId);

    /**
     * Find primary image (displayOrder = 0) for a car.
     */
    @Query("SELECT ci FROM CarImage ci WHERE ci.car.id = :carId AND ci.displayOrder = 0")
    CarImage findPrimaryByCarId(@Param("carId") Long carId);

    /**
     * Count images for a car.
     */
    long countByCarId(Long carId);

    /**
     * Delete all images for a car.
     */
    @Modifying
    @Query("DELETE FROM CarImage ci WHERE ci.car.id = :carId")
    void deleteByCarId(@Param("carId") Long carId);

    /**
     * Find all images with Base64 data (for migration).
     */
    @Query("SELECT ci FROM CarImage ci WHERE ci.imageUrl LIKE 'data:image/%'")
    List<CarImage> findBase64Images();

    /**
     * Find all images with local paths (for migration).
     */
    @Query("SELECT ci FROM CarImage ci WHERE ci.imageUrl LIKE '/car-images/%' OR ci.imageUrl LIKE 'car-images/%'")
    List<CarImage> findLocalPathImages();
}
