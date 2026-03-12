package org.example.rentoza.booking;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RentalAgreementRepository extends JpaRepository<RentalAgreement, Long> {

    Optional<RentalAgreement> findByBookingId(Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")
    })
    @Query("SELECT ra FROM RentalAgreement ra WHERE ra.bookingId = :bookingId")
    Optional<RentalAgreement> findByBookingIdForUpdate(@Param("bookingId") Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")
    })
    @Query("SELECT ra FROM RentalAgreement ra WHERE ra.id = :id")
    Optional<RentalAgreement> findByIdForUpdate(@Param("id") Long id);

    boolean existsByBookingId(Long bookingId);

    @Query("SELECT ra FROM RentalAgreement ra WHERE ra.bookingId IN :bookingIds")
    List<RentalAgreement> findByBookingIdIn(@Param("bookingIds") List<Long> bookingIds);

    @Query("SELECT ra.bookingId FROM RentalAgreement ra WHERE ra.bookingId IN :bookingIds")
    List<Long> findBookingIdsWithAgreements(@Param("bookingIds") List<Long> bookingIds);

    List<RentalAgreement> findByOwnerUserIdAndStatus(Long ownerUserId, RentalAgreementStatus status);

    @Query("SELECT ra FROM RentalAgreement ra WHERE ra.status IN :statuses AND ra.acceptanceDeadlineAt IS NOT NULL AND ra.acceptanceDeadlineAt <= :deadline")
    List<RentalAgreement> findPendingForDeadlineResolution(@Param("statuses") List<RentalAgreementStatus> statuses,
                                                           @Param("deadline") LocalDateTime deadline);

    @Query("SELECT ra.id FROM RentalAgreement ra WHERE ra.status IN :statuses AND ra.acceptanceDeadlineAt IS NOT NULL AND ra.acceptanceDeadlineAt <= :deadline")
    List<Long> findPendingIdsForDeadlineResolution(@Param("statuses") List<RentalAgreementStatus> statuses,
                                                   @Param("deadline") LocalDateTime deadline);

    @Query("SELECT ra FROM RentalAgreement ra WHERE ra.status IN :statuses AND ra.acceptanceDeadlineAt IS NOT NULL AND ra.acceptanceDeadlineAt > :windowStart AND ra.acceptanceDeadlineAt <= :windowEnd")
    List<RentalAgreement> findPendingForReminderWindow(@Param("statuses") List<RentalAgreementStatus> statuses,
                                                       @Param("windowStart") LocalDateTime windowStart,
                                                       @Param("windowEnd") LocalDateTime windowEnd);

    @Query("SELECT ra.id FROM RentalAgreement ra WHERE ra.status IN :statuses AND ra.acceptanceDeadlineAt IS NULL")
    List<Long> findIdsByStatusInAndAcceptanceDeadlineAtIsNull(@Param("statuses") List<RentalAgreementStatus> statuses);
}
