package org.example.rentoza.booking.checkin.verification;

import org.example.rentoza.booking.checkin.CheckInIdVerification;
import org.example.rentoza.booking.checkin.IdVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ID verification records.
 * 
 * <p><b>IMPORTANT:</b> This table contains PII. Access should be restricted.
 */
public interface IdVerificationRepository extends JpaRepository<CheckInIdVerification, Long> {

    /**
     * Find verification by booking ID.
     * Returns the single verification record for a booking.
     */
    Optional<CheckInIdVerification> findByBookingId(Long bookingId);

    /**
     * Find verification by check-in session ID.
     */
    Optional<CheckInIdVerification> findByCheckInSessionId(String sessionId);

    /**
     * Check if verification exists for a booking.
     */
    boolean existsByBookingId(Long bookingId);

    /**
     * Find verifications pending manual review.
     */
    @Query("SELECT v FROM CheckInIdVerification v WHERE v.verificationStatus = 'MANUAL_REVIEW' ORDER BY v.createdAt ASC")
    List<CheckInIdVerification> findPendingManualReview();

    /**
     * Count verifications by status.
     */
    @Query("SELECT COUNT(v) FROM CheckInIdVerification v WHERE v.verificationStatus = :status")
    long countByStatus(@Param("status") IdVerificationStatus status);

    /**
     * Find verifications for a guest (across all bookings).
     * Useful for checking repeat guests.
     */
    @Query("SELECT v FROM CheckInIdVerification v WHERE v.guest.id = :guestId ORDER BY v.createdAt DESC")
    List<CheckInIdVerification> findByGuestId(@Param("guestId") Long guestId);

    /**
     * Check if guest has been successfully verified before.
     * If a guest was verified in a previous booking, we may skip reverification.
     */
    @Query("SELECT COUNT(v) > 0 FROM CheckInIdVerification v " +
           "WHERE v.guest.id = :guestId " +
           "AND v.verificationStatus IN ('PASSED', 'OVERRIDE_APPROVED')")
    boolean hasGuestBeenVerified(@Param("guestId") Long guestId);
}


