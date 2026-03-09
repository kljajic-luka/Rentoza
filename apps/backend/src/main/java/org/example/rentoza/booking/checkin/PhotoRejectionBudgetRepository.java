package org.example.rentoza.booking.checkin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PhotoRejectionBudgetRepository extends JpaRepository<PhotoRejectionBudget, Long> {

    Optional<PhotoRejectionBudget> findByBookingIdAndUserIdAndActorRoleAndPhotoTypeAndIpAddressHashAndDeviceFingerprintHash(
            Long bookingId,
            Long userId,
            CheckInActorRole actorRole,
            CheckInPhotoType photoType,
            String ipAddressHash,
            String deviceFingerprintHash
    );
}
