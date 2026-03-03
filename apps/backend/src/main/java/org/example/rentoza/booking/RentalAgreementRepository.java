package org.example.rentoza.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RentalAgreementRepository extends JpaRepository<RentalAgreement, Long> {

    Optional<RentalAgreement> findByBookingId(Long bookingId);

    boolean existsByBookingId(Long bookingId);

    @Query("SELECT ra FROM RentalAgreement ra WHERE ra.bookingId IN :bookingIds")
    List<RentalAgreement> findByBookingIdIn(@Param("bookingIds") List<Long> bookingIds);

    @Query("SELECT ra.bookingId FROM RentalAgreement ra WHERE ra.bookingId IN :bookingIds")
    List<Long> findBookingIdsWithAgreements(@Param("bookingIds") List<Long> bookingIds);

    List<RentalAgreement> findByOwnerUserIdAndStatus(Long ownerUserId, RentalAgreementStatus status);
}
