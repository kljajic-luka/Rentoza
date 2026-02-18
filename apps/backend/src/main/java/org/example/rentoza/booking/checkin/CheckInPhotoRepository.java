package org.example.rentoza.booking.checkin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for check-in photos with EXIF validation support.
 *
 * @see CheckInPhoto
 * @see ExifValidationStatus
 */
public interface CheckInPhotoRepository extends JpaRepository<CheckInPhoto, Long> {

    /**
     * Find all photos for a booking (excluding soft-deleted).
     */
    @Query("SELECT p FROM CheckInPhoto p WHERE p.booking.id = :bookingId AND p.deletedAt IS NULL")
    List<CheckInPhoto> findByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find all photos for a check-in session.
     */
    @Query("SELECT p FROM CheckInPhoto p WHERE p.checkInSessionId = :sessionId AND p.deletedAt IS NULL")
    List<CheckInPhoto> findByCheckInSessionId(@Param("sessionId") String sessionId);

    /**
     * Find photos by type for a booking.
     * Used to check if required photos have been uploaded.
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId AND p.photoType = :photoType AND p.deletedAt IS NULL")
    List<CheckInPhoto> findByBookingIdAndPhotoType(
            @Param("bookingId") Long bookingId, 
            @Param("photoType") CheckInPhotoType photoType);

    /**
     * Count photos by type for a booking.
     */
    @Query("SELECT COUNT(p) FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId AND p.photoType = :photoType AND p.deletedAt IS NULL")
    long countByBookingIdAndPhotoType(
            @Param("bookingId") Long bookingId, 
            @Param("photoType") CheckInPhotoType photoType);

    /**
     * Count ALL photos for a booking (regardless of type or validation status).
     * P1 FIX: Used for per-booking photo cap enforcement to prevent DoS.
     */
    @Query("SELECT COUNT(p) FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId AND p.deletedAt IS NULL")
    long countByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Count all valid photos for a booking (EXIF validation passed).
     * Includes VALID_WITH_WARNINGS for HEIC/modern formats validated via sidecar.
     */
    @Query("SELECT COUNT(p) FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.exifValidationStatus IN ('VALID', 'VALID_NO_GPS', 'VALID_WITH_WARNINGS')")
    long countValidPhotosByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find photos pending EXIF validation.
     * Used by background validation job.
     */
    @Query("SELECT p FROM CheckInPhoto p WHERE p.exifValidationStatus = 'PENDING' AND p.deletedAt IS NULL")
    List<CheckInPhoto> findPendingValidation();

    /**
     * Find photos uploaded by a specific user.
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.uploadedBy.id = :userId AND p.deletedAt IS NULL " +
           "ORDER BY p.uploadedAt DESC")
    List<CheckInPhoto> findByUploadedById(@Param("userId") Long userId);

    /**
     * Find photos with failed EXIF validation for a booking.
     * Used for error display to user.
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.exifValidationStatus NOT IN ('VALID', 'VALID_NO_GPS', 'PENDING')")
    List<CheckInPhoto> findRejectedPhotosByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find all host photos for a booking (required for host check-in completion).
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.photoType IN (" +
           "  'HOST_EXTERIOR_FRONT', 'HOST_EXTERIOR_REAR', 'HOST_EXTERIOR_LEFT', 'HOST_EXTERIOR_RIGHT', " +
           "  'HOST_INTERIOR_DASHBOARD', 'HOST_INTERIOR_REAR', 'HOST_ODOMETER', 'HOST_FUEL_GAUGE'" +
           ")")
    List<CheckInPhoto> findHostPhotosByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Count required host photos for completion check.
     * Includes VALID_WITH_WARNINGS for HEIC/modern formats validated via sidecar.
     */
    @Query("SELECT COUNT(DISTINCT p.photoType) FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.exifValidationStatus IN ('VALID', 'VALID_NO_GPS', 'VALID_WITH_WARNINGS') " +
           "AND p.photoType IN (" +
           "  'HOST_EXTERIOR_FRONT', 'HOST_EXTERIOR_REAR', 'HOST_EXTERIOR_LEFT', 'HOST_EXTERIOR_RIGHT', " +
           "  'HOST_INTERIOR_DASHBOARD', 'HOST_INTERIOR_REAR', 'HOST_ODOMETER', 'HOST_FUEL_GAUGE'" +
           ")")
    long countRequiredHostPhotoTypes(@Param("bookingId") Long bookingId);

    /**
     * Check if photo exists by storage key (prevents duplicate uploads).
     */
    boolean existsByStorageKey(String storageKey);

    /**
     * Find photo by storage key.
     */
    Optional<CheckInPhoto> findByStorageKey(String storageKey);

    /**
     * Find photos uploaded within a time range (for analytics).
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.uploadedAt >= :startTime AND p.uploadedAt <= :endTime " +
           "ORDER BY p.uploadedAt ASC")
    List<CheckInPhoto> findByUploadedAtBetween(
            @Param("startTime") Instant startTime, 
            @Param("endTime") Instant endTime);

    // ========== CHECKOUT PHOTO QUERIES ==========

    /**
     * Count required guest checkout photo types for completion check.
     * Includes VALID_WITH_WARNINGS for HEIC/modern formats validated via sidecar.
     */
    @Query("SELECT COUNT(DISTINCT p.photoType) FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.exifValidationStatus IN ('VALID', 'VALID_NO_GPS', 'VALID_WITH_WARNINGS') " +
           "AND p.photoType IN (" +
           "  'CHECKOUT_EXTERIOR_FRONT', 'CHECKOUT_EXTERIOR_REAR', 'CHECKOUT_EXTERIOR_LEFT', 'CHECKOUT_EXTERIOR_RIGHT', " +
           "  'CHECKOUT_ODOMETER', 'CHECKOUT_FUEL_GAUGE'" +
           ")")
    long countCheckoutPhotoTypes(@Param("bookingId") Long bookingId);

    /**
     * Find all checkout photos for a booking.
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.photoType IN (" +
           "  'CHECKOUT_EXTERIOR_FRONT', 'CHECKOUT_EXTERIOR_REAR', 'CHECKOUT_EXTERIOR_LEFT', 'CHECKOUT_EXTERIOR_RIGHT', " +
           "  'CHECKOUT_INTERIOR_DASHBOARD', 'CHECKOUT_INTERIOR_REAR', 'CHECKOUT_ODOMETER', 'CHECKOUT_FUEL_GAUGE', " +
           "  'CHECKOUT_DAMAGE_NEW', 'CHECKOUT_CUSTOM', 'HOST_CHECKOUT_CONFIRMATION', 'HOST_CHECKOUT_DAMAGE_EVIDENCE'" +
           ")")
    List<CheckInPhoto> findCheckoutPhotosByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find checkout photos by session ID.
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.checkInSessionId = :sessionId " +
           "AND p.deletedAt IS NULL " +
           "AND p.photoType IN (" +
           "  'CHECKOUT_EXTERIOR_FRONT', 'CHECKOUT_EXTERIOR_REAR', 'CHECKOUT_EXTERIOR_LEFT', 'CHECKOUT_EXTERIOR_RIGHT', " +
           "  'CHECKOUT_INTERIOR_DASHBOARD', 'CHECKOUT_INTERIOR_REAR', 'CHECKOUT_ODOMETER', 'CHECKOUT_FUEL_GAUGE', " +
           "  'CHECKOUT_DAMAGE_NEW', 'CHECKOUT_CUSTOM', 'HOST_CHECKOUT_CONFIRMATION', 'HOST_CHECKOUT_DAMAGE_EVIDENCE'" +
           ")")
    List<CheckInPhoto> findCheckoutPhotosBySessionId(@Param("sessionId") String sessionId);
}
