package org.example.rentoza.booking.checkin.cqrs;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Custom repository operations for the check-in status read model.
 */
public interface CheckInStatusViewRepositoryCustom {

    /**
     * Insert the initial row or atomically increment {@code photoCount} if it already exists.
     *
     * <p>The implementation is intentionally database-portable so the same behavior works in
     * PostgreSQL production and H2-backed integration tests.
     */
    void upsertPhotoCount(
            Long bookingId,
            UUID sessionId,
            Long hostUserId,
            String hostName,
            String hostPhone,
            Long guestUserId,
            String guestName,
            String guestPhone,
            Long carId,
            String carBrand,
            String carModel,
            Integer carYear,
            String carImageUrl,
            String carLicensePlate,
            String status,
            String statusDisplay,
            LocalDateTime scheduledStartTime,
            Boolean lockboxAvailable,
            Integer geofenceDistanceMeters);
}
