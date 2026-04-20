package org.example.rentoza.booking.checkin.cqrs;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.BookingStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Portable insert-or-increment implementation for photo count updates.
 *
 * <p>Production previously relied on PostgreSQL-specific {@code ON CONFLICT}. H2 rejects that
 * syntax, so integration tests never exercised the photo-count path. This implementation keeps
 * the same semantics with an UPDATE-first / INSERT-with-retry strategy that works across both
 * PostgreSQL and H2.
 */
@Slf4j
public class CheckInStatusViewRepositoryImpl implements CheckInStatusViewRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionTemplate transactionTemplate;

    public CheckInStatusViewRepositoryImpl(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void upsertPhotoCount(
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
            Integer geofenceDistanceMeters) {
        RuntimeException lastFailure = null;

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Boolean completed = transactionTemplate.execute(statusTx -> {
                    Instant syncAt = Instant.now();
                    if (incrementExistingPhotoCount(bookingId, syncAt)) {
                        return true;
                    }

                    CheckInStatusView view = CheckInStatusView.fromBookingData(
                            bookingId,
                            sessionId,
                            hostUserId,
                            hostName,
                            hostPhone,
                            guestUserId,
                            guestName,
                            guestPhone,
                            carId,
                            carBrand,
                            carModel,
                            carYear,
                            carImageUrl,
                            carLicensePlate,
                            BookingStatus.valueOf(status),
                            scheduledStartTime
                    );

                    view.setStatusDisplay(statusDisplay);
                    view.setLockboxAvailable(Boolean.TRUE.equals(lockboxAvailable));
                    view.setGeofenceDistanceMeters(geofenceDistanceMeters);
                    view.setPhotoCount(1);
                    view.setVersion(0L);
                    view.setLastSyncAt(syncAt);

                    entityManager.persist(view);
                    entityManager.flush();
                    entityManager.clear();
                    return true;
                });

                if (Boolean.TRUE.equals(completed)) {
                    return;
                }
            } catch (RuntimeException insertException) {
                lastFailure = insertException;
                log.debug("[View-Sync] Photo-count upsert retry {}/3 for booking {} after {}",
                        attempt + 1, bookingId, insertException.getClass().getSimpleName());
            }
        }

        throw lastFailure != null
                ? lastFailure
                : new IllegalStateException("Photo-count upsert exhausted retries for booking " + bookingId);
    }

    private boolean incrementExistingPhotoCount(Long bookingId, Instant syncAt) {
        int updated = entityManager.createQuery("""
                UPDATE CheckInStatusView v
                SET v.photoCount = COALESCE(v.photoCount, 0) + 1,
                    v.version = COALESCE(v.version, 0) + 1,
                    v.lastSyncAt = :syncAt
                WHERE v.bookingId = :bookingId
                """)
                .setParameter("syncAt", syncAt)
                .setParameter("bookingId", bookingId)
                .executeUpdate();

        if (updated > 0) {
            entityManager.clear();
            return true;
        }
        return false;
    }
}
