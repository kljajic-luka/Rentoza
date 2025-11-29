package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.dto.*;
import org.example.rentoza.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * REST controller for check-in workflow endpoints.
 * 
 * <h2>Endpoints</h2>
 * <pre>
 * GET  /api/bookings/{bookingId}/check-in/status     - Get check-in status
 * POST /api/bookings/{bookingId}/check-in/host/photos - Upload host photo
 * POST /api/bookings/{bookingId}/check-in/host/complete - Complete host check-in
 * POST /api/bookings/{bookingId}/check-in/guest/condition-ack - Guest acknowledges condition
 * POST /api/bookings/{bookingId}/check-in/handshake  - Confirm handshake
 * </pre>
 * 
 * <h2>Security</h2>
 * <p>All endpoints require authentication. Additional role-based checks are performed
 * at the service layer (host vs guest access).
 */
@RestController
@RequestMapping("/api/bookings/{bookingId}/check-in")
@PreAuthorize("isAuthenticated()")
@Slf4j
public class CheckInController {

    private final CheckInService checkInService;
    private final CheckInPhotoService photoService;
    private final CurrentUser currentUser;
    private final Counter photoUploadCounter;

    public CheckInController(
            CheckInService checkInService,
            CheckInPhotoService photoService,
            CurrentUser currentUser,
            MeterRegistry meterRegistry) {
        this.checkInService = checkInService;
        this.photoService = photoService;
        this.currentUser = currentUser;
        
        this.photoUploadCounter = Counter.builder("checkin.photo.upload")
                .description("Check-in photo uploads")
                .register(meterRegistry);
    }

    // ========== STATUS ==========

    /**
     * Get current check-in status for a booking.
     * 
     * <p>Returns the complete check-in state including:
     * <ul>
     *   <li>Current status and phase completion</li>
     *   <li>Photos (visible to guest only after host completes)</li>
     *   <li>Odometer/fuel readings</li>
     *   <li>No-show deadline countdown</li>
     * </ul>
     */
    @GetMapping("/status")
    public ResponseEntity<CheckInStatusDTO> getCheckInStatus(
            @PathVariable Long bookingId) {
        
        Long userId = currentUser.id();
        log.debug("[CheckIn] Status request for booking {} by user {}", bookingId, userId);
        
        CheckInStatusDTO status = checkInService.getCheckInStatus(bookingId, userId);
        return ResponseEntity.ok(status);
    }

    // ========== HOST WORKFLOW ==========

    /**
     * Upload a check-in photo.
     * 
     * <p>Photos are validated for EXIF metadata to prevent fraud:
     * <ul>
     *   <li>Must have EXIF timestamp (no screenshots)</li>
     *   <li>Must be recent (within 24 hours)</li>
     *   <li>GPS coordinates extracted if present</li>
     * </ul>
     */
    @PostMapping(value = "/host/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CheckInPhotoDTO> uploadHostPhoto(
            @PathVariable Long bookingId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("photoType") CheckInPhotoType photoType,
            @RequestParam(value = "clientTimestamp", required = false) Instant clientTimestamp) throws IOException {
        
        Long userId = currentUser.id();
        log.debug("[CheckIn] Photo upload for booking {} by user {}, type: {}", 
            bookingId, userId, photoType);
        
        CheckInPhotoDTO photo = photoService.uploadPhoto(
            bookingId, 
            userId, 
            file, 
            photoType, 
            clientTimestamp
        );
        
        photoUploadCounter.increment();
        
        return ResponseEntity.status(HttpStatus.CREATED).body(photo);
    }

    /**
     * Complete host check-in with odometer/fuel readings.
     * 
     * <p>Validates that:
     * <ul>
     *   <li>User is the car owner</li>
     *   <li>Booking is in CHECK_IN_OPEN status</li>
     *   <li>Required photos are uploaded (8 minimum)</li>
     * </ul>
     */
    @PostMapping("/host/complete")
    public ResponseEntity<CheckInStatusDTO> completeHostCheckIn(
            @PathVariable Long bookingId,
            @Valid @RequestBody HostCheckInSubmissionDTO submission) {
        
        Long userId = currentUser.id();
        log.debug("[CheckIn] Host completing check-in for booking {} by user {}", bookingId, userId);
        
        // Ensure bookingId matches
        submission.setBookingId(bookingId);
        
        CheckInStatusDTO status = checkInService.completeHostCheckIn(submission, userId);
        return ResponseEntity.ok(status);
    }

    // ========== GUEST WORKFLOW ==========

    /**
     * Guest acknowledges vehicle condition.
     * 
     * <p>Guest reviews host photos and confirms the vehicle condition.
     * Can optionally mark damage hotspots.
     */
    @PostMapping("/guest/condition-ack")
    public ResponseEntity<CheckInStatusDTO> acknowledgeCondition(
            @PathVariable Long bookingId,
            @Valid @RequestBody GuestConditionAcknowledgmentDTO acknowledgment) {
        
        Long userId = currentUser.id();
        log.debug("[CheckIn] Guest acknowledging condition for booking {} by user {}", 
            bookingId, userId);
        
        // Ensure bookingId matches
        acknowledgment.setBookingId(bookingId);
        
        CheckInStatusDTO status = checkInService.acknowledgeCondition(acknowledgment, userId);
        return ResponseEntity.ok(status);
    }

    // ========== HANDSHAKE ==========

    /**
     * Confirm handshake to start the trip.
     * 
     * <p>Both host and guest must confirm. For remote handoff (lockbox),
     * guest must pass geofence validation (within 100m of car).
     */
    @PostMapping("/handshake")
    public ResponseEntity<CheckInStatusDTO> confirmHandshake(
            @PathVariable Long bookingId,
            @Valid @RequestBody HandshakeConfirmationDTO confirmation) {
        
        Long userId = currentUser.id();
        log.debug("[CheckIn] Handshake confirmation for booking {} by user {}", 
            bookingId, userId);
        
        // Ensure bookingId matches
        confirmation.setBookingId(bookingId);
        
        CheckInStatusDTO status = checkInService.confirmHandshake(confirmation, userId);
        return ResponseEntity.ok(status);
    }

    // ========== LOCKBOX (Remote Handoff) ==========

    /**
     * Reveal lockbox code for remote key handoff.
     * 
     * <p>Only available to guest after:
     * <ul>
     *   <li>Host has completed check-in with lockbox code</li>
     *   <li>Guest has acknowledged condition</li>
     *   <li>Guest is within geofence radius (if strict mode enabled)</li>
     * </ul>
     */
    @GetMapping("/lockbox-code")
    public ResponseEntity<Map<String, Object>> revealLockboxCode(
            @PathVariable Long bookingId,
            @RequestParam(value = "latitude", required = false) Double latitude,
            @RequestParam(value = "longitude", required = false) Double longitude) {
        
        Long userId = currentUser.id();
        log.debug("[CheckIn] Lockbox code request for booking {} by user {}", 
            bookingId, userId);
        
        String code = photoService.revealLockboxCode(
            bookingId, 
            userId,
            latitude != null ? BigDecimal.valueOf(latitude) : null,
            longitude != null ? BigDecimal.valueOf(longitude) : null
        );
        
        return ResponseEntity.ok(Map.of(
            "lockboxCode", code,
            "revealedAt", Instant.now().toString()
        ));
    }

    // ========== EXCEPTION HANDLERS ==========

    @ExceptionHandler(CheckInService.GeofenceViolationException.class)
    public ResponseEntity<Map<String, Object>> handleGeofenceViolation(
            CheckInService.GeofenceViolationException ex) {
        
        log.warn("[CheckIn] Geofence violation: {}", ex.getMessage());
        
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
            "error", "GEOFENCE_VIOLATION",
            "message", ex.getMessage(),
            "messagesr", ex.getMessage() // Serbian message already in exception
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        
        log.warn("[CheckIn] Illegal state: {}", ex.getMessage());
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "error", "INVALID_STATE",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        
        log.warn("[CheckIn] Illegal argument: {}", ex.getMessage());
        
        return ResponseEntity.badRequest().body(Map.of(
            "error", "INVALID_ARGUMENT",
            "message", ex.getMessage()
        ));
    }
}
