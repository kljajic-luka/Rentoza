package org.example.rentoza.booking.checkin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminCheckInRecoveryService {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final CheckInEventService checkInEventService;
    private final AdminAuditService adminAuditService;

    @Transactional
    public Booking forceConditionAck(Long bookingId, Long adminUserId, String reason) {
        Booking booking = bookingRepository.findByIdWithLock(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));

        if (booking.getStatus() != BookingStatus.CHECK_IN_HOST_COMPLETE) {
            throw new IllegalStateException("Force condition-ack allowed only from CHECK_IN_HOST_COMPLETE. Current status: " + booking.getStatus());
        }

        booking.setGuestCheckInCompletedAt(Instant.now());
        booking.setCheckInAdminOverrideAt(Instant.now());
        booking.setStatus(BookingStatus.CHECK_IN_COMPLETE);
        bookingRepository.save(booking);

        checkInEventService.recordEvent(
                booking,
                booking.getCheckInSessionId(),
                CheckInEventType.ADMIN_FORCE_CONDITION_ACK,
                adminUserId,
                CheckInActorRole.SYSTEM,
                Map.of("reason", reason)
        );

        logAdminAction(adminUserId, booking, "FORCE_CONDITION_ACK", reason, Map.of(
                "statusBefore", BookingStatus.CHECK_IN_HOST_COMPLETE.name(),
                "statusAfter", BookingStatus.CHECK_IN_COMPLETE.name()
        ));

        return booking;
    }

    @Transactional
    public Booking cancelStuck(Long bookingId, Long adminUserId, String reason) {
        Booking booking = bookingRepository.findByIdWithLock(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));

        if (!isCancelableState(booking.getStatus())) {
            throw new IllegalStateException("cancel-stuck allowed only from CHECK_IN_OPEN/CHECK_IN_HOST_COMPLETE/CHECK_IN_COMPLETE. Current status: " + booking.getStatus());
        }

        String previousSessionId = booking.getCheckInSessionId();
        BookingStatus statusBefore = booking.getStatus();

        booking.setCheckInSessionId(null);
        booking.setCheckInOpenedAt(null);
        booking.setHostCheckInCompletedAt(null);
        booking.setGuestCheckInCompletedAt(null);
        booking.setGuestCheckinPhotoCount(null);
        booking.setGuestCheckinPhotosCompletedAt(null);
        booking.setHandshakeCompletedAt(null);
        booking.setTripStartedAt(null);
        booking.setHostCheckInLatitude(null);
        booking.setHostCheckInLongitude(null);
        booking.setGuestCheckInLatitude(null);
        booking.setGuestCheckInLongitude(null);
        booking.setGeofenceDistanceMeters(null);
        booking.setCheckInAdminOverrideAt(null);
        booking.setStatus(BookingStatus.ACTIVE);
        bookingRepository.save(booking);

        checkInEventService.recordEvent(
                booking,
                previousSessionId != null ? previousSessionId : "NO_SESSION",
                CheckInEventType.ADMIN_CANCEL_STUCK_CHECKIN,
                adminUserId,
                CheckInActorRole.SYSTEM,
                Map.of(
                        "reason", reason,
                        "statusBefore", statusBefore.name(),
                        "statusAfter", BookingStatus.ACTIVE.name(),
                        "previousSessionId", previousSessionId != null ? previousSessionId : "NONE"
                )
        );

        logAdminAction(adminUserId, booking, "CANCEL_STUCK", reason, Map.of(
                "statusBefore", statusBefore.name(),
                "statusAfter", BookingStatus.ACTIVE.name(),
                "previousSessionId", previousSessionId
        ));

        return booking;
    }

    @Transactional
    public Booking reassignSession(Long bookingId, Long adminUserId, String reason) {
        Booking booking = bookingRepository.findByIdWithLock(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Rezervacija nije pronađena"));

        if (!isCancelableState(booking.getStatus())) {
            throw new IllegalStateException("reassign-session allowed only from CHECK_IN_OPEN/CHECK_IN_HOST_COMPLETE/CHECK_IN_COMPLETE. Current status: " + booking.getStatus());
        }

        String oldSessionId = booking.getCheckInSessionId();
        String newSessionId = UUID.randomUUID().toString();
        booking.setCheckInSessionId(newSessionId);
        booking.setCheckInOpenedAt(Instant.now());
        booking.setHostCheckInCompletedAt(null);
        booking.setGuestCheckInCompletedAt(null);
        booking.setGuestCheckinPhotoCount(null);
        booking.setGuestCheckinPhotosCompletedAt(null);
        booking.setHandshakeCompletedAt(null);
        booking.setTripStartedAt(null);
        booking.setHostCheckInLatitude(null);
        booking.setHostCheckInLongitude(null);
        booking.setGuestCheckInLatitude(null);
        booking.setGuestCheckInLongitude(null);
        booking.setGeofenceDistanceMeters(null);
        booking.setCheckInAdminOverrideAt(null);
        booking.setStatus(BookingStatus.CHECK_IN_OPEN);
        bookingRepository.save(booking);

        checkInEventService.recordEvent(
                booking,
                newSessionId,
                CheckInEventType.ADMIN_REASSIGN_CHECKIN_SESSION,
                adminUserId,
                CheckInActorRole.SYSTEM,
                Map.of(
                        "reason", reason,
                        "oldSessionId", oldSessionId != null ? oldSessionId : "NONE",
                        "newSessionId", newSessionId
                )
        );

        logAdminAction(adminUserId, booking, "REASSIGN_SESSION", reason, Map.of(
                "oldSessionId", oldSessionId,
                "newSessionId", newSessionId
        ));

        return booking;
    }

    private boolean isCancelableState(BookingStatus status) {
        return status == BookingStatus.CHECK_IN_OPEN
                || status == BookingStatus.CHECK_IN_HOST_COMPLETE
                || status == BookingStatus.CHECK_IN_COMPLETE;
    }

    private void logAdminAction(Long adminUserId, Booking booking, String action, String reason, Map<String, Object> details) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));

        adminAuditService.logAction(
                admin,
                AdminAction.CONFIG_UPDATED,
                ResourceType.BOOKING,
                booking.getId(),
                null,
                adminAuditService.toJson(details),
                "check-in-recovery:" + action + " - " + reason
        );
    }
}
