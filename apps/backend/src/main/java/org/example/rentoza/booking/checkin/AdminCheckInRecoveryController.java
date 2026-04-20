package org.example.rentoza.booking.checkin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.rentoza.booking.checkin.dto.AdminCheckInRecoveryCommandRequest;
import org.example.rentoza.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/bookings/{bookingId}/check-in")
@RequiredArgsConstructor
@PreAuthorize("@checkInAuthorization.canRunAdminRecovery(authentication)")
public class AdminCheckInRecoveryController {

    private final AdminCheckInRecoveryService recoveryService;
    private final CurrentUser currentUser;

    @PostMapping("/force-condition-ack")
    public ResponseEntity<Map<String, Object>> forceConditionAck(
            @PathVariable Long bookingId,
            @Valid @RequestBody AdminCheckInRecoveryCommandRequest request) {

        var booking = recoveryService.forceConditionAck(bookingId, currentUser.id(), request.reason());
        return ResponseEntity.ok(Map.of(
                "bookingId", booking.getId(),
                "status", booking.getStatus(),
                "checkInSessionId", booking.getCheckInSessionId(),
                "adminOverrideAt", booking.getCheckInAdminOverrideAt()
        ));
    }

    @PostMapping("/cancel-stuck")
    public ResponseEntity<Map<String, Object>> cancelStuck(
            @PathVariable Long bookingId,
            @Valid @RequestBody AdminCheckInRecoveryCommandRequest request) {

        var booking = recoveryService.cancelStuck(bookingId, currentUser.id(), request.reason());
        return ResponseEntity.ok(Map.of(
                "bookingId", booking.getId(),
                "status", booking.getStatus(),
                "checkInSessionId", booking.getCheckInSessionId()
        ));
    }

    @PostMapping("/reassign-session")
    public ResponseEntity<Map<String, Object>> reassignSession(
            @PathVariable Long bookingId,
            @Valid @RequestBody AdminCheckInRecoveryCommandRequest request) {

        var booking = recoveryService.reassignSession(bookingId, currentUser.id(), request.reason());
        return ResponseEntity.ok(Map.of(
                "bookingId", booking.getId(),
                "status", booking.getStatus(),
                "checkInSessionId", booking.getCheckInSessionId()
        ));
    }
}
