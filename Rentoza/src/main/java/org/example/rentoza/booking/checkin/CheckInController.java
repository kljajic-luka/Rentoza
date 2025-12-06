package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.dto.*;
import org.example.rentoza.idempotency.IdempotencyService;
import org.example.rentoza.idempotency.IdempotencyService.IdempotencyResult;
import org.example.rentoza.idempotency.IdempotencyService.IdempotencyStatus;
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
import java.util.Optional;

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
 * 
 * <h2>Idempotency (Phase 1 Critical Fix)</h2>
 * <p>Mutation endpoints (POST) support idempotency via {@code X-Idempotency-Key} header:
 * <ul>
 *   <li>Client provides UUID v4 key on first request</li>
 *   <li>Retries with same key return cached response (no duplicate execution)</li>
 *   <li>Keys are scoped per-user (24h TTL)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings/{bookingId}/check-in")
@PreAuthorize("isAuthenticated()")
@Slf4j
public class CheckInController {

    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    private final CheckInService checkInService;
    private final CheckInPhotoService photoService;
    private final IdempotencyService idempotencyService;
    private final CurrentUser currentUser;
    private final CheckInResponseOptimizer responseOptimizer;
    private final Counter photoUploadCounter;

    public CheckInController(
            CheckInService checkInService,
            CheckInPhotoService photoService,
            IdempotencyService idempotencyService,
            CurrentUser currentUser,
            CheckInResponseOptimizer responseOptimizer,
            MeterRegistry meterRegistry) {
        this.checkInService = checkInService;
        this.photoService = photoService;
        this.idempotencyService = idempotencyService;
        this.currentUser = currentUser;
        this.responseOptimizer = responseOptimizer;
        
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
     * 
     * <h3>Phase 3: API Optimizations</h3>
     * <ul>
     *   <li><b>ETag:</b> Supports If-None-Match header for 304 Not Modified</li>
     *   <li><b>Sparse Fieldsets:</b> ?fields=status,photos to reduce payload</li>
     *   <li><b>Compression:</b> Supports gzip via Accept-Encoding</li>
     * </ul>
     * 
     * @param bookingId The booking ID
     * @param ifNoneMatch ETag from previous response for conditional GET
     * @param fields Optional comma-separated list of fields to return
     */
    @GetMapping("/status")
    public ResponseEntity<CheckInStatusDTO> getCheckInStatus(
            @PathVariable Long bookingId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @RequestParam(value = "fields", required = false) String fields) {
        
        Long userId = currentUser.id();
        log.info("[CheckIn] DIAGNOSTIC: Controller status request - bookingId={}, userId={}, fields={}", 
            bookingId, userId, fields);
        
        CheckInStatusDTO status = checkInService.getCheckInStatus(bookingId, userId);
        
        // Apply sparse fieldset if requested
        if (fields != null && !fields.isBlank()) {
            status = responseOptimizer.applySparseFieldset(status, fields);
        }
        
        // Return optimized response with ETag support
        return responseOptimizer.buildOptimizedResponse(status, ifNoneMatch);
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
     * 
     * <p>Client GPS coordinates (clientLatitude/clientLongitude) are accepted as a
     * fallback for location verification when EXIF GPS is missing (e.g., Canvas
     * compression scenarios). This is defense-in-depth; piexifjs should preserve
     * EXIF in the frontend.
     */
    @PostMapping(value = "/host/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CheckInPhotoDTO> uploadHostPhoto(
            @PathVariable Long bookingId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("photoType") CheckInPhotoType photoType,
            @RequestParam(value = "clientTimestamp", required = false) String clientTimestampStr,
            @RequestParam(value = "clientLatitude", required = false) BigDecimal clientLatitude,
            @RequestParam(value = "clientLongitude", required = false) BigDecimal clientLongitude) throws IOException {
        
        Long userId = currentUser.id();
        log.info("[CheckIn] RAW UPLOAD REQUEST: bookingId={}, photoType={}", bookingId, photoType);
        log.info("[CheckIn] RAW PARAM: clientTimestampStr='{}'", clientTimestampStr);
        log.info("[CheckIn] RAW PARAM: clientLatitude={}", clientLatitude);
        log.info("[CheckIn] RAW PARAM: clientLongitude={}", clientLongitude);
        log.info("[CheckIn] RAW FILE: name={}, size={}, contentType={}", 
            file.getOriginalFilename(), file.getSize(), file.getContentType());
            
        log.debug("[CheckIn] Photo upload for booking {} by user {}, type: {}, clientGPS: ({}, {})", 
            bookingId, userId, photoType, clientLatitude, clientLongitude);
            
        // Manual parsing of timestamp to avoid Spring multipart binding issues
        Instant clientTimestamp = null;
        if (clientTimestampStr != null && !clientTimestampStr.isBlank()) {
            try {
                clientTimestamp = Instant.parse(clientTimestampStr);
            } catch (Exception e) {
                log.warn("[CheckIn] Failed to parse clientTimestamp: {}", clientTimestampStr);
            }
        }
        
        CheckInPhotoDTO photo = photoService.uploadPhoto(
            bookingId, 
            userId, 
            file, 
            photoType, 
            clientTimestamp,
            clientLatitude,
            clientLongitude
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
     * 
     * <h3>Idempotency</h3>
     * <p>Supports {@code X-Idempotency-Key} header to prevent duplicate state transitions.
     */
    @PostMapping("/host/complete")
    public ResponseEntity<CheckInStatusDTO> completeHostCheckIn(
            @PathVariable Long bookingId,
            @Valid @RequestBody HostCheckInSubmissionDTO submission,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        
        Long userId = currentUser.id();
        log.debug("[CheckIn] Host completing check-in for booking {} by user {}", bookingId, userId);
        
        // Idempotency check
        Optional<IdempotencyResult> cached = idempotencyService.checkIdempotency(idempotencyKey, userId);
        if (cached.isPresent()) {
            IdempotencyResult result = cached.get();
            if (result.getStatus() == IdempotencyStatus.PROCESSING) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(null); // Request is still processing
            }
            // Return cached successful response
            log.info("[CheckIn] Returning cached host-complete response for key: {}", 
                    idempotencyKey != null ? idempotencyKey.substring(0, 8) + "..." : "N/A");
            return ResponseEntity.status(result.getHttpStatus()).build();
        }
        
        // Mark as processing
        if (!idempotencyService.markProcessing(idempotencyKey, userId, "HOST_CHECK_IN_COMPLETE")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        
        try {
            // Ensure bookingId matches
            submission.setBookingId(bookingId);
            
            CheckInStatusDTO status = checkInService.completeHostCheckIn(submission, userId);
            
            // Store successful result
            idempotencyService.storeSuccess(idempotencyKey, userId, HttpStatus.OK, status);
            
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            // Remove idempotency record on transient errors to allow retry
            idempotencyService.remove(idempotencyKey, userId);
            throw e;
        }
    }

    // ========== GUEST WORKFLOW ==========

    /**
     * Guest acknowledges vehicle condition.
     * 
     * <p>Guest reviews host photos and confirms the vehicle condition.
     * Can optionally mark damage hotspots.
     * 
     * <h3>Idempotency</h3>
     * <p>Supports {@code X-Idempotency-Key} header to prevent duplicate acknowledgments.
     */
    @PostMapping("/guest/condition-ack")
    public ResponseEntity<CheckInStatusDTO> acknowledgeCondition(
            @PathVariable Long bookingId,
            @Valid @RequestBody GuestConditionAcknowledgmentDTO acknowledgment,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        
        Long userId = currentUser.id();
        log.debug("[CheckIn] Guest acknowledging condition for booking {} by user {}", 
            bookingId, userId);
        
        // Idempotency check
        Optional<IdempotencyResult> cached = idempotencyService.checkIdempotency(idempotencyKey, userId);
        if (cached.isPresent()) {
            IdempotencyResult result = cached.get();
            if (result.getStatus() == IdempotencyStatus.PROCESSING) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(null);
            }
            log.info("[CheckIn] Returning cached condition-ack response for key: {}", 
                    idempotencyKey != null ? idempotencyKey.substring(0, 8) + "..." : "N/A");
            return ResponseEntity.status(result.getHttpStatus()).build();
        }
        
        // Mark as processing
        if (!idempotencyService.markProcessing(idempotencyKey, userId, "GUEST_CONDITION_ACK")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        
        try {
            // Ensure bookingId matches
            acknowledgment.setBookingId(bookingId);
            
            CheckInStatusDTO status = checkInService.acknowledgeCondition(acknowledgment, userId);
            
            // Store successful result
            idempotencyService.storeSuccess(idempotencyKey, userId, HttpStatus.OK, status);
            
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            idempotencyService.remove(idempotencyKey, userId);
            throw e;
        }
    }

    // ========== HANDSHAKE ==========

    /**
     * Confirm handshake to start the trip.
     * 
     * <p>Both host and guest must confirm. For remote handoff (lockbox),
     * guest must pass geofence validation (within 100m of car).
     * 
     * <h3>Idempotency (Critical)</h3>
     * <p>Supports {@code X-Idempotency-Key} header. This is the most critical
     * endpoint for idempotency as it triggers trip start and payment capture.
     */
    @PostMapping("/handshake")
    public ResponseEntity<CheckInStatusDTO> confirmHandshake(
            @PathVariable Long bookingId,
            @Valid @RequestBody HandshakeConfirmationDTO confirmation,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        
        Long userId = currentUser.id();
        log.debug("[CheckIn] Handshake confirmation for booking {} by user {}", 
            bookingId, userId);
        
        // Idempotency check - critical for handshake to prevent duplicate trip starts
        Optional<IdempotencyResult> cached = idempotencyService.checkIdempotency(idempotencyKey, userId);
        if (cached.isPresent()) {
            IdempotencyResult result = cached.get();
            if (result.getStatus() == IdempotencyStatus.PROCESSING) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(null);
            }
            log.info("[CheckIn] Returning cached handshake response for key: {}", 
                    idempotencyKey != null ? idempotencyKey.substring(0, 8) + "..." : "N/A");
            return ResponseEntity.status(result.getHttpStatus()).build();
        }
        
        // Mark as processing
        if (!idempotencyService.markProcessing(idempotencyKey, userId, "HANDSHAKE_CONFIRM")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        
        try {
            // Ensure bookingId matches
            confirmation.setBookingId(bookingId);
            
            CheckInStatusDTO status = checkInService.confirmHandshake(confirmation, userId);
            
            // Store successful result
            idempotencyService.storeSuccess(idempotencyKey, userId, HttpStatus.OK, status);
            
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            idempotencyService.remove(idempotencyKey, userId);
            throw e;
        }
    }

    // ========== LOCKBOX (Remote Handoff) ==========

    /**
     * Reveal lockbox code for remote key handoff.
     * 
     * <p><b>Security (D4 Fix):</b> Authorization is enforced in the service layer
     * ({@link CheckInPhotoService#revealLockboxCode}) which validates:
     * <ul>
     *   <li>User must be the booking's renter (guest) - throws AccessDeniedException otherwise</li>
     *   <li>Booking must have a lockbox code configured</li>
     *   <li>Host must have completed check-in first</li>
     * </ul>
     * 
     * <p><b>Prerequisites for guest to reveal code:</b>
     * <ul>
     *   <li>Host has completed check-in with lockbox code</li>
     *   <li>Guest has acknowledged condition</li>
     *   <li>Guest is within geofence radius (if strict mode enabled)</li>
     * </ul>
     * 
     * @param bookingId the booking ID
     * @param latitude optional GPS latitude for geofence validation
     * @param longitude optional GPS longitude for geofence validation
     * @return lockbox code and reveal timestamp
     * @throws AccessDeniedException if user is not the guest for this booking
     * @throws IllegalStateException if booking is not in correct state
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

    @ExceptionHandler(IdempotencyService.InvalidIdempotencyKeyException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidIdempotencyKey(
            IdempotencyService.InvalidIdempotencyKeyException ex) {
        
        log.warn("[CheckIn] Invalid idempotency key: {}", ex.getMessage());
        
        return ResponseEntity.badRequest().body(Map.of(
            "error", "INVALID_IDEMPOTENCY_KEY",
            "message", ex.getMessage(),
            "hint", "X-Idempotency-Key must be a valid UUID v4 (e.g., 550e8400-e29b-41d4-a716-446655440000)"
        ));
    }

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
