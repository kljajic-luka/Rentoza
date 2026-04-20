package org.example.rentoza.booking.checkin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CheckInAttestationRepository extends JpaRepository<CheckInAttestation, Long> {

    default Optional<CheckInAttestation> findByBookingId(Long bookingId) {
        return findLatestFirstByBookingId(bookingId).stream().findFirst();
    }

    Optional<CheckInAttestation> findByBookingIdAndCheckInSessionId(Long bookingId, String checkInSessionId);

    Optional<CheckInAttestation> findByCheckInSessionId(String checkInSessionId);

    @Query("SELECT a FROM CheckInAttestation a WHERE a.booking.id = :bookingId ORDER BY a.createdAt DESC")
    List<CheckInAttestation> findLatestFirstByBookingId(@Param("bookingId") Long bookingId);
}
