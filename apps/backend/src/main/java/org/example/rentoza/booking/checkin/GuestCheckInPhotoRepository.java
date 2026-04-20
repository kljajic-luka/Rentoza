package org.example.rentoza.booking.checkin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for guest check-in photos with EXIF validation support.
 * 
 * <p>Guest check-in photos are the dual-party counterpart to host check-in photos.
 * When the guest arrives for pickup, they capture the same angles as the host
 * to establish bilateral evidence for any disputes.
 *
 * @see GuestCheckInPhoto
 * @see CheckInPhotoRepository for host photos
 * @see ExifValidationStatus
 */
public interface GuestCheckInPhotoRepository extends JpaRepository<GuestCheckInPhoto, Long> {

    /**
     * Find all guest photos for a booking (excluding soft-deleted).
     */
    @Query("SELECT p FROM GuestCheckInPhoto p WHERE p.booking.id = :bookingId AND p.deletedAt IS NULL")
    List<GuestCheckInPhoto> findByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find all guest photos for a check-in session.
     */
    @Query("SELECT p FROM GuestCheckInPhoto p WHERE p.checkInSessionId = :sessionId AND p.deletedAt IS NULL")
    List<GuestCheckInPhoto> findByCheckInSessionId(@Param("sessionId") String sessionId);

    /**
     * Find guest photos by type for a booking.
     */
    @Query("SELECT p FROM GuestCheckInPhoto p " +
           "WHERE p.booking.id = :bookingId AND p.photoType = :photoType AND p.deletedAt IS NULL")
    List<GuestCheckInPhoto> findByBookingIdAndPhotoType(
            @Param("bookingId") Long bookingId,
            @Param("photoType") CheckInPhotoType photoType);

    /**
     * Count guest photos by type for a booking.
     */
    @Query("SELECT COUNT(p) FROM GuestCheckInPhoto p " +
           "WHERE p.booking.id = :bookingId AND p.photoType = :photoType AND p.deletedAt IS NULL")
    long countByBookingIdAndPhotoType(
            @Param("bookingId") Long bookingId,
            @Param("photoType") CheckInPhotoType photoType);

    @Query("SELECT COUNT(p) FROM GuestCheckInPhoto p " +
           "WHERE p.checkInSessionId = :sessionId AND p.photoType = :photoType AND p.deletedAt IS NULL")
    long countByCheckInSessionIdAndPhotoType(
            @Param("sessionId") String sessionId,
            @Param("photoType") CheckInPhotoType photoType);

    /**
     * Count all valid guest photos for a booking (EXIF validation passed).
     */
    @Query("SELECT COUNT(p) FROM GuestCheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.exifValidationStatus IN ('VALID', 'VALID_NO_GPS', 'VALID_WITH_WARNINGS')")
    long countValidPhotosByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find guest photos pending EXIF validation.
     * Used by background validation job.
     */
    @Query("SELECT p FROM GuestCheckInPhoto p WHERE p.exifValidationStatus = 'PENDING' AND p.deletedAt IS NULL")
    List<GuestCheckInPhoto> findPendingValidation();

    /**
     * Find guest photos uploaded by a specific user.
     */
    @Query("SELECT p FROM GuestCheckInPhoto p " +
           "WHERE p.uploadedBy.id = :userId AND p.deletedAt IS NULL " +
           "ORDER BY p.uploadedAt DESC")
    List<GuestCheckInPhoto> findByUploadedById(@Param("userId") Long userId);

    /**
     * Find guest photos with failed EXIF validation for a booking.
     */
    @Query("SELECT p FROM GuestCheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.exifValidationStatus NOT IN ('VALID', 'VALID_NO_GPS', 'PENDING', 'VALID_WITH_WARNINGS')")
    List<GuestCheckInPhoto> findRejectedPhotosByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Count required guest photo types for completion check.
     * Guest must capture same photos as host for dual-party verification.
     */
    @Query("SELECT COUNT(DISTINCT p.photoType) FROM GuestCheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.exifValidationStatus IN ('VALID', 'VALID_NO_GPS', 'VALID_WITH_WARNINGS') " +
           "AND p.photoType IN (" +
           "  'GUEST_EXTERIOR_FRONT', 'GUEST_EXTERIOR_REAR', 'GUEST_EXTERIOR_LEFT', 'GUEST_EXTERIOR_RIGHT', " +
           "  'GUEST_INTERIOR_DASHBOARD', 'GUEST_INTERIOR_REAR', 'GUEST_ODOMETER', 'GUEST_FUEL_GAUGE'" +
           ")")
    long countRequiredGuestPhotoTypes(@Param("bookingId") Long bookingId);

    @Query("SELECT COUNT(DISTINCT p.photoType) FROM GuestCheckInPhoto p " +
           "WHERE p.checkInSessionId = :sessionId " +
           "AND p.deletedAt IS NULL " +
           "AND p.exifValidationStatus IN ('VALID', 'VALID_NO_GPS', 'VALID_WITH_WARNINGS') " +
           "AND p.photoType IN (" +
           "  'GUEST_EXTERIOR_FRONT', 'GUEST_EXTERIOR_REAR', 'GUEST_EXTERIOR_LEFT', 'GUEST_EXTERIOR_RIGHT', " +
           "  'GUEST_INTERIOR_DASHBOARD', 'GUEST_INTERIOR_REAR', 'GUEST_ODOMETER', 'GUEST_FUEL_GAUGE'" +
           ")")
    long countRequiredGuestPhotoTypesBySession(@Param("sessionId") String sessionId);

    /**
     * Find all guest photos for photo types required for dual-party verification.
     */
    @Query("SELECT p FROM GuestCheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.photoType IN (" +
           "  'GUEST_EXTERIOR_FRONT', 'GUEST_EXTERIOR_REAR', 'GUEST_EXTERIOR_LEFT', 'GUEST_EXTERIOR_RIGHT', " +
           "  'GUEST_INTERIOR_DASHBOARD', 'GUEST_INTERIOR_REAR', 'GUEST_ODOMETER', 'GUEST_FUEL_GAUGE'" +
           ")")
    List<GuestCheckInPhoto> findRequiredGuestPhotos(@Param("bookingId") Long bookingId);

    @Query("SELECT p FROM GuestCheckInPhoto p " +
           "WHERE p.checkInSessionId = :sessionId " +
           "AND p.deletedAt IS NULL " +
           "AND p.photoType IN (" +
           "  'GUEST_EXTERIOR_FRONT', 'GUEST_EXTERIOR_REAR', 'GUEST_EXTERIOR_LEFT', 'GUEST_EXTERIOR_RIGHT', " +
           "  'GUEST_INTERIOR_DASHBOARD', 'GUEST_INTERIOR_REAR', 'GUEST_ODOMETER', 'GUEST_FUEL_GAUGE'" +
           ")")
    List<GuestCheckInPhoto> findRequiredGuestPhotosBySession(@Param("sessionId") String sessionId);

    /**
     * Check if photo exists by storage key (prevents duplicate uploads).
     */
    boolean existsByStorageKey(String storageKey);

    /**
     * Find photo by storage key.
     */
    Optional<GuestCheckInPhoto> findByStorageKey(String storageKey);

    /**
     * Find guest photos uploaded within a time range (for analytics).
     */
    @Query("SELECT p FROM GuestCheckInPhoto p " +
           "WHERE p.uploadedAt >= :startTime AND p.uploadedAt <= :endTime " +
           "ORDER BY p.uploadedAt ASC")
    List<GuestCheckInPhoto> findByUploadedAtBetween(
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    /**
     * Find corresponding guest photo for a host photo type.
     * Used for side-by-side comparison in discrepancy detection.
     */
    @Query("SELECT p FROM GuestCheckInPhoto p " +
           "WHERE p.booking.id = :bookingId " +
           "AND p.deletedAt IS NULL " +
           "AND p.photoType = :photoType " +
           "ORDER BY p.uploadedAt DESC")
    Optional<GuestCheckInPhoto> findLatestByBookingIdAndPhotoType(
            @Param("bookingId") Long bookingId,
            @Param("photoType") CheckInPhotoType photoType);
}
