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
       @Query("SELECT p FROM CheckInPhoto p WHERE p.booking.id = :bookingId AND p.deletedAt IS NULL AND p.uploadStatus = 'COMPLETED'")
    List<CheckInPhoto> findByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find all photos for a check-in session.
     */
       @Query("SELECT p FROM CheckInPhoto p WHERE p.checkInSessionId = :sessionId AND p.deletedAt IS NULL AND p.uploadStatus = 'COMPLETED'")
    List<CheckInPhoto> findByCheckInSessionId(@Param("sessionId") String sessionId);

    /**
     * Resolve booking ID from a session ID (check-in or checkout).
     * Returns the booking ID of the first photo found for this session.
     * Used by photo-serving controllers to authorize participant access.
     */
       @Query("SELECT p.booking.id FROM CheckInPhoto p WHERE p.checkInSessionId = :sessionId AND p.deletedAt IS NULL AND p.uploadStatus = 'COMPLETED' ORDER BY p.id ASC")
    List<Long> findBookingIdsBySessionId(@Param("sessionId") String sessionId);

    /**
     * Find photos by type for a booking.
     * Used to check if required photos have been uploaded.
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId AND p.photoType = :photoType AND p.deletedAt IS NULL AND p.uploadStatus = 'COMPLETED'")
    List<CheckInPhoto> findByBookingIdAndPhotoType(
            @Param("bookingId") Long bookingId, 
            @Param("photoType") CheckInPhotoType photoType);

    /**
     * Count photos by type for a booking.
     */
    @Query("SELECT COUNT(p) FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId AND p.photoType = :photoType AND p.deletedAt IS NULL AND p.uploadStatus = 'COMPLETED'")
    long countByBookingIdAndPhotoType(
            @Param("bookingId") Long bookingId, 
            @Param("photoType") CheckInPhotoType photoType);

    /**
     * Count ALL photos for a booking (regardless of type or validation status).
     * P1 FIX: Used for per-booking photo cap enforcement to prevent DoS.
     */
    @Query("SELECT COUNT(p) FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId AND p.deletedAt IS NULL AND p.uploadStatus <> 'FAILED_TERMINAL'")
    long countByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Count all valid photos for a booking (EXIF validation passed).
     * Includes VALID_WITH_WARNINGS for HEIC/modern formats validated via sidecar.
     */
    @Query("SELECT COUNT(p) FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.uploadStatus = 'COMPLETED' " +
           "AND p.exifValidationStatus IN ('VALID', 'VALID_NO_GPS', 'VALID_WITH_WARNINGS')")
    long countValidPhotosByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find photos pending EXIF validation.
     * Used by background validation job.
     */
       @Query("SELECT p FROM CheckInPhoto p WHERE p.exifValidationStatus = 'PENDING' AND p.deletedAt IS NULL AND p.uploadStatus = 'COMPLETED'")
    List<CheckInPhoto> findPendingValidation();

    /**
     * Find photos uploaded by a specific user.
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.uploadedBy.id = :userId AND p.deletedAt IS NULL " +
           "AND p.uploadStatus = 'COMPLETED' " +
           "ORDER BY p.uploadedAt DESC")
    List<CheckInPhoto> findByUploadedById(@Param("userId") Long userId);

    /**
     * Find photos with failed EXIF validation for a booking.
     * Used for error display to user.
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.uploadStatus = 'COMPLETED' " +
           "AND p.exifValidationStatus NOT IN ('VALID', 'VALID_NO_GPS', 'PENDING')")
    List<CheckInPhoto> findRejectedPhotosByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find all host photos for a booking (required for host check-in completion).
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.uploadStatus = 'COMPLETED' " +
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
           "AND p.uploadStatus = 'COMPLETED' " +
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

    // ========== IMAGE HASH FRAUD DETECTION (R1/R2 Remediation) ==========

    /**
     * Check if a photo with this hash exists on a different booking (fraud indicator).
     * Same-booking duplicates are excluded (retakes of the same slot are expected).
     *
     * @param imageHash SHA-256 hash of image bytes
     * @param excludeBookingId Current booking ID to exclude
     * @return true if duplicate found on another booking
     * @since R1 - Trust & evidence audit remediation
     */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM CheckInPhoto p " +
           "WHERE p.imageHash = :imageHash " +
           "AND p.booking.id != :excludeBookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.uploadStatus = 'COMPLETED'")
    boolean existsByImageHashOnOtherBooking(
            @Param("imageHash") String imageHash,
            @Param("excludeBookingId") Long excludeBookingId);

    /**
     * Find all photos with a matching image hash from other bookings.
     * Used for fraud investigation — returns the cross-booking duplicates.
     *
     * @param imageHash SHA-256 hash of image bytes
     * @param excludeBookingId Booking to exclude
     * @return List of matching photos from other bookings
     * @since R1 - Trust & evidence audit remediation
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.imageHash = :imageHash " +
           "AND p.booking.id != :excludeBookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.uploadStatus = 'COMPLETED'")
    List<CheckInPhoto> findByImageHashExcludingBooking(
            @Param("imageHash") String imageHash,
            @Param("excludeBookingId") Long excludeBookingId);

    // ========== CHECKOUT PHOTO QUERIES ==========

    /**
     * Count required guest checkout photo types for completion check.
     * Includes VALID_WITH_WARNINGS for HEIC/modern formats validated via sidecar.
     * P0 FIX: Now counts all 8 required types (was 6, missing interior photos).
     */
    @Query("SELECT COUNT(DISTINCT p.photoType) FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.uploadStatus = 'COMPLETED' " +
           "AND p.exifValidationStatus IN ('VALID', 'VALID_NO_GPS', 'VALID_WITH_WARNINGS') " +
           "AND p.photoType IN (" +
           "  'CHECKOUT_EXTERIOR_FRONT', 'CHECKOUT_EXTERIOR_REAR', 'CHECKOUT_EXTERIOR_LEFT', 'CHECKOUT_EXTERIOR_RIGHT', " +
           "  'CHECKOUT_INTERIOR_DASHBOARD', 'CHECKOUT_INTERIOR_REAR', " +
           "  'CHECKOUT_ODOMETER', 'CHECKOUT_FUEL_GAUGE'" +
           ")")
    long countCheckoutPhotoTypes(@Param("bookingId") Long bookingId);

    /**
     * Find all checkout photos for a booking.
     */
    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.uploadStatus = 'COMPLETED' " +
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
           "AND p.uploadStatus = 'COMPLETED' " +
           "AND p.photoType IN (" +
           "  'CHECKOUT_EXTERIOR_FRONT', 'CHECKOUT_EXTERIOR_REAR', 'CHECKOUT_EXTERIOR_LEFT', 'CHECKOUT_EXTERIOR_RIGHT', " +
           "  'CHECKOUT_INTERIOR_DASHBOARD', 'CHECKOUT_INTERIOR_REAR', 'CHECKOUT_ODOMETER', 'CHECKOUT_FUEL_GAUGE', " +
           "  'CHECKOUT_DAMAGE_NEW', 'CHECKOUT_CUSTOM', 'HOST_CHECKOUT_CONFIRMATION', 'HOST_CHECKOUT_DAMAGE_EVIDENCE'" +
           ")")
    List<CheckInPhoto> findCheckoutPhotosBySessionId(@Param("sessionId") String sessionId);

    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.photoType = :photoType " +
           "AND p.deletedAt IS NULL " +
           "AND p.uploadStatus = 'COMPLETED' " +
           "AND p.id <> :excludePhotoId")
    List<CheckInPhoto> findCompletedByBookingIdAndPhotoTypeExcludingId(
            @Param("bookingId") Long bookingId,
            @Param("photoType") CheckInPhotoType photoType,
            @Param("excludePhotoId") Long excludePhotoId);

    @Query("SELECT p FROM CheckInPhoto p " +
           "WHERE p.deletedAt IS NULL " +
           "AND p.uploadStatus IN ('PENDING_UPLOAD', 'PENDING_FINALIZE') " +
           "AND COALESCE(p.lastUploadAttemptAt, p.uploadedAt) < :staleBefore " +
           "ORDER BY p.uploadedAt ASC")
    List<CheckInPhoto> findStalePendingUploads(@Param("staleBefore") Instant staleBefore);
}
