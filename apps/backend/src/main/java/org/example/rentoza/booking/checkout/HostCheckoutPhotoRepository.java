package org.example.rentoza.booking.checkout;

import org.example.rentoza.booking.checkin.CheckInPhotoType;
import org.example.rentoza.booking.checkin.ExifValidationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for host checkout photos with EXIF validation support.
 * 
 * <p>Host checkout photos are the dual-party counterpart to guest checkout photos.
 * When the vehicle is returned, the host captures the same angles as the guest
 * to establish bilateral evidence for damage disputes.
 *
 * @see HostCheckoutPhoto
 * @see ExifValidationStatus
 */
public interface HostCheckoutPhotoRepository extends JpaRepository<HostCheckoutPhoto, Long> {

    /**
     * Find all host checkout photos for a booking (excluding soft-deleted).
     */
    @Query("SELECT p FROM HostCheckoutPhoto p WHERE p.booking.id = :bookingId AND p.deletedAt IS NULL")
    List<HostCheckoutPhoto> findByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find all host checkout photos for a checkout session.
     */
    @Query("SELECT p FROM HostCheckoutPhoto p WHERE p.checkoutSessionId = :sessionId AND p.deletedAt IS NULL")
    List<HostCheckoutPhoto> findByCheckoutSessionId(@Param("sessionId") String sessionId);

    /**
     * Find host checkout photos by type for a booking.
     */
    @Query("SELECT p FROM HostCheckoutPhoto p " +
           "WHERE p.booking.id = :bookingId AND p.photoType = :photoType AND p.deletedAt IS NULL")
    List<HostCheckoutPhoto> findByBookingIdAndPhotoType(
            @Param("bookingId") Long bookingId,
            @Param("photoType") CheckInPhotoType photoType);

    /**
     * Count host checkout photos by type for a booking.
     */
    @Query("SELECT COUNT(p) FROM HostCheckoutPhoto p " +
           "WHERE p.booking.id = :bookingId AND p.photoType = :photoType AND p.deletedAt IS NULL")
    long countByBookingIdAndPhotoType(
            @Param("bookingId") Long bookingId,
            @Param("photoType") CheckInPhotoType photoType);

    /**
     * Count all valid host checkout photos for a booking (EXIF validation passed).
     */
    @Query("SELECT COUNT(p) FROM HostCheckoutPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.exifValidationStatus IN ('VALID', 'VALID_NO_GPS', 'VALID_WITH_WARNINGS')")
    long countValidPhotosByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find host checkout photos pending EXIF validation.
     * Used by background validation job.
     */
    @Query("SELECT p FROM HostCheckoutPhoto p WHERE p.exifValidationStatus = 'PENDING' AND p.deletedAt IS NULL")
    List<HostCheckoutPhoto> findPendingValidation();

    /**
     * Find host checkout photos uploaded by a specific user.
     */
    @Query("SELECT p FROM HostCheckoutPhoto p " +
           "WHERE p.uploadedBy.id = :userId AND p.deletedAt IS NULL " +
           "ORDER BY p.uploadedAt DESC")
    List<HostCheckoutPhoto> findByUploadedById(@Param("userId") Long userId);

    /**
     * Find host checkout photos with failed EXIF validation for a booking.
     */
    @Query("SELECT p FROM HostCheckoutPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.exifValidationStatus NOT IN ('VALID', 'VALID_NO_GPS', 'PENDING', 'VALID_WITH_WARNINGS')")
    List<HostCheckoutPhoto> findRejectedPhotosByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Count required host checkout photo types for completion check.
     */
    @Query("SELECT COUNT(DISTINCT p.photoType) FROM HostCheckoutPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.exifValidationStatus IN ('VALID', 'VALID_NO_GPS', 'VALID_WITH_WARNINGS') " +
           "AND p.photoType IN (" +
           "  'HOST_CHECKOUT_EXTERIOR_FRONT', 'HOST_CHECKOUT_EXTERIOR_REAR', " +
           "  'HOST_CHECKOUT_EXTERIOR_LEFT', 'HOST_CHECKOUT_EXTERIOR_RIGHT', " +
           "  'HOST_CHECKOUT_INTERIOR_DASHBOARD', 'HOST_CHECKOUT_INTERIOR_REAR', " +
           "  'HOST_CHECKOUT_ODOMETER', 'HOST_CHECKOUT_FUEL_GAUGE'" +
           ")")
    long countRequiredHostCheckoutPhotoTypes(@Param("bookingId") Long bookingId);

    /**
     * Find all host checkout photos for required types.
     */
    @Query("SELECT p FROM HostCheckoutPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.photoType IN (" +
           "  'HOST_CHECKOUT_EXTERIOR_FRONT', 'HOST_CHECKOUT_EXTERIOR_REAR', " +
           "  'HOST_CHECKOUT_EXTERIOR_LEFT', 'HOST_CHECKOUT_EXTERIOR_RIGHT', " +
           "  'HOST_CHECKOUT_INTERIOR_DASHBOARD', 'HOST_CHECKOUT_INTERIOR_REAR', " +
           "  'HOST_CHECKOUT_ODOMETER', 'HOST_CHECKOUT_FUEL_GAUGE'" +
           ")")
    List<HostCheckoutPhoto> findRequiredHostCheckoutPhotos(@Param("bookingId") Long bookingId);

    /**
     * Check if photo exists by storage key (prevents duplicate uploads).
     */
    boolean existsByStorageKey(String storageKey);

    /**
     * Find photo by storage key.
     */
    Optional<HostCheckoutPhoto> findByStorageKey(String storageKey);

    /**
     * Find host checkout photos uploaded within a time range (for analytics).
     */
    @Query("SELECT p FROM HostCheckoutPhoto p " +
           "WHERE p.uploadedAt >= :startTime AND p.uploadedAt <= :endTime " +
           "ORDER BY p.uploadedAt ASC")
    List<HostCheckoutPhoto> findByUploadedAtBetween(
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    /**
     * Find corresponding host checkout photo for a guest checkout photo type.
     * Used for side-by-side comparison in discrepancy detection.
     */
    @Query("SELECT p FROM HostCheckoutPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.photoType = :photoType " +
           "ORDER BY p.uploadedAt DESC")
    Optional<HostCheckoutPhoto> findLatestByBookingIdAndPhotoType(
            @Param("bookingId") Long bookingId,
            @Param("photoType") CheckInPhotoType photoType);
}
