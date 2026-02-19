package org.example.rentoza.booking.checkout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.CheckInPhotoService;
import org.example.rentoza.booking.checkin.CheckInPhotoType;
import org.example.rentoza.booking.checkin.dto.CheckInPhotoDTO;
import org.example.rentoza.booking.checkin.dto.PhotoUploadResponse;
import org.example.rentoza.booking.checkout.dto.*;
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
import java.util.List;
import java.util.Map;

/**
 * REST controller for checkout workflow endpoints.
 * 
 * <h2>Endpoints</h2>
 * <pre>
 * GET  /api/bookings/{bookingId}/checkout/status           - Get checkout status
 * POST /api/bookings/{bookingId}/checkout/initiate         - Initiate checkout
 * POST /api/bookings/{bookingId}/checkout/guest/photos     - Upload guest checkout photo
 * POST /api/bookings/{bookingId}/checkout/guest/complete   - Complete guest checkout
 * POST /api/bookings/{bookingId}/checkout/host/photos      - Upload host confirmation photo
 * POST /api/bookings/{bookingId}/checkout/host/confirm     - Host confirms checkout
 * </pre>
 * 
 * <h2>Security</h2>
 * <p>All endpoints require authentication. Additional role-based checks are performed
 * at the service layer (host vs guest access).
 */
@RestController
@RequestMapping("/api/bookings/{bookingId}/checkout")
@PreAuthorize("isAuthenticated()")
@Slf4j
public class CheckOutController {

    private final CheckOutService checkOutService;
    private final CheckInPhotoService photoService;
    private final CurrentUser currentUser;
    private final Counter photoUploadCounter;

    public CheckOutController(
            CheckOutService checkOutService,
            CheckInPhotoService photoService,
            CurrentUser currentUser,
            MeterRegistry meterRegistry) {
        this.checkOutService = checkOutService;
        this.photoService = photoService;
        this.currentUser = currentUser;
        
        this.photoUploadCounter = Counter.builder("checkout.photo.upload")
                .description("Checkout photo uploads")
                .register(meterRegistry);
    }

    // ========== STATUS ==========

    /**
     * Get current checkout status for a booking.
     * 
     * <p>Returns the complete checkout state including:
     * <ul>
     *   <li>Current status and phase completion</li>
     *   <li>Check-in photos (for comparison)</li>
     *   <li>Checkout photos</li>
     *   <li>Odometer/fuel readings (start vs end)</li>
     *   <li>Late return information</li>
     *   <li>Damage assessment status</li>
     * </ul>
     */
    @GetMapping("/status")
    public ResponseEntity<CheckOutStatusDTO> getCheckoutStatus(
            @PathVariable Long bookingId) {
        
        Long userId = currentUser.id();
        log.debug("[CheckOut] Status request for booking {} by user {}", bookingId, userId);
        
        CheckOutStatusDTO status = checkOutService.getCheckOutStatus(bookingId, userId);
        return ResponseEntity.ok(status);
    }

    // ========== CHECKOUT INITIATION ==========

    /**
     * Initiate checkout process.
     * 
     * <p>Can be called by guest for early return or host to remind guest.
     * Transitions booking from IN_TRIP to CHECKOUT_OPEN.
     */
    @PostMapping("/initiate")
    public ResponseEntity<CheckOutStatusDTO> initiateCheckout(
            @PathVariable Long bookingId,
            @RequestParam(value = "earlyReturn", defaultValue = "false") boolean earlyReturn) {
        
        Long userId = currentUser.id();
        log.debug("[CheckOut] Initiating checkout for booking {} by user {}, earlyReturn={}",
            bookingId, userId, earlyReturn);
        
        CheckOutStatusDTO status = checkOutService.initiateCheckout(bookingId, userId, earlyReturn);
        return ResponseEntity.ok(status);
    }

    // ========== GUEST WORKFLOW ==========

    /**
     * Upload a checkout photo (guest).
     * 
     * <p>Guest uploads photos documenting vehicle condition at return.
     * Photos are validated for EXIF metadata just like check-in photos.
     */
    @PostMapping(value = "/guest/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PhotoUploadResponse> uploadGuestCheckoutPhoto(
            @PathVariable Long bookingId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("photoType") CheckInPhotoType photoType,
            @RequestParam(value = "clientTimestamp", required = false) Instant clientTimestamp,
            @RequestParam(value = "clientLatitude", required = false) BigDecimal clientLatitude,
            @RequestParam(value = "clientLongitude", required = false) BigDecimal clientLongitude) throws IOException {
        
        // Validate photo type is a checkout type
        if (!photoType.isCheckoutPhoto() || photoType.isHostCheckoutPhoto()) {
            throw new IllegalArgumentException("Nevažeći tip fotografije za guest checkout: " + photoType);
        }
        
        Long userId = currentUser.id();
        log.debug("[CheckOut] Guest photo upload for booking {} by user {}, type: {}",
            bookingId, userId, photoType);
        
        PhotoUploadResponse response = photoService.uploadPhoto(
            bookingId,
            userId,
            file,
            photoType,
            clientTimestamp,
            clientLatitude,
            clientLongitude
        );
        
        photoUploadCounter.increment();
        
        HttpStatus status = response.isAccepted() ? HttpStatus.CREATED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Complete guest checkout with end readings.
     * 
     * <p>Validates that:
     * <ul>
     *   <li>User is the renter</li>
     *   <li>Booking is in CHECKOUT_OPEN status</li>
     *   <li>Required checkout photos are uploaded (6 minimum)</li>
     * </ul>
     */
    @PostMapping("/guest/complete")
    public ResponseEntity<CheckOutStatusDTO> completeGuestCheckout(
            @PathVariable Long bookingId,
            @Valid @RequestBody GuestCheckOutSubmissionDTO submission) {
        
        Long userId = currentUser.id();
        log.debug("[CheckOut] Guest completing checkout for booking {} by user {}", bookingId, userId);
        
        // Ensure bookingId matches
        submission.setBookingId(bookingId);
        
        CheckOutStatusDTO status = checkOutService.completeGuestCheckout(submission, userId);
        return ResponseEntity.ok(status);
    }

    // ========== HOST WORKFLOW ==========

    /**
     * Upload a host checkout confirmation photo.
     * 
     * <p>Host can upload photos documenting damage or confirming return condition.
     */
    @PostMapping(value = "/host/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PhotoUploadResponse> uploadHostCheckoutPhoto(
            @PathVariable Long bookingId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("photoType") CheckInPhotoType photoType,
            @RequestParam(value = "clientTimestamp", required = false) Instant clientTimestamp,
            @RequestParam(value = "clientLatitude", required = false) BigDecimal clientLatitude,
            @RequestParam(value = "clientLongitude", required = false) BigDecimal clientLongitude) throws IOException {
        
        // Validate photo type is a host checkout type
        if (!photoType.isHostCheckoutPhoto()) {
            throw new IllegalArgumentException("Nevažeći tip fotografije za host checkout: " + photoType);
        }
        
        Long userId = currentUser.id();
        log.debug("[CheckOut] Host photo upload for booking {} by user {}, type: {}",
            bookingId, userId, photoType);
        
        PhotoUploadResponse response = photoService.uploadPhoto(
            bookingId,
            userId,
            file,
            photoType,
            clientTimestamp,
            clientLatitude,
            clientLongitude
        );
        
        photoUploadCounter.increment();
        
        HttpStatus status = response.isAccepted() ? HttpStatus.CREATED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Host confirms vehicle return and condition.
     * 
     * <p>Host can either:
     * <ul>
     *   <li>Accept condition (no damage) - checkout completes</li>
     *   <li>Report damage - dispute process initiated</li>
     * </ul>
     */
    @PostMapping("/host/confirm")
    public ResponseEntity<CheckOutStatusDTO> confirmHostCheckout(
            @PathVariable Long bookingId,
            @Valid @RequestBody HostCheckOutConfirmationDTO confirmation) {
        
        Long userId = currentUser.id();
        log.debug("[CheckOut] Host confirming checkout for booking {} by user {}", bookingId, userId);
        
        // Ensure bookingId matches
        confirmation.setBookingId(bookingId);
        
        CheckOutStatusDTO status = checkOutService.confirmHostCheckout(confirmation, userId);
        return ResponseEntity.ok(status);
    }

    // ========== EXCEPTION HANDLERS ==========

    // ========== DAMAGE DISPUTE ENDPOINTS (VAL-010) ==========

    /**
     * Guest accepts the checkout damage claim.
     * 
     * <p>Allows deposit capture for damage charges and completes checkout.
     * Only callable when booking is in CHECKOUT_DAMAGE_DISPUTE status.
     */
    @PostMapping("/damage/accept")
    public ResponseEntity<Map<String, Object>> acceptDamageClaim(
            @PathVariable Long bookingId) {
        
        Long userId = currentUser.id();
        log.info("[CheckOut] Guest {} accepting damage claim for booking {}", userId, bookingId);
        
        checkOutService.acceptDamageClaim(bookingId, userId);
        
        return ResponseEntity.ok(Map.of(
            "status", "ACCEPTED",
            "message", "Prijava oštećenja prihvaćena. Depozit će biti zadržan za pokriće troškova.",
            "bookingId", bookingId
        ));
    }

    /**
     * Guest disputes the checkout damage claim.
     * 
     * <p>Escalates to admin for resolution. Deposit remains held.
     * Only callable when booking is in CHECKOUT_DAMAGE_DISPUTE status.
     */
    @PostMapping("/damage/dispute")
    public ResponseEntity<Map<String, Object>> disputeDamageClaim(
            @PathVariable Long bookingId,
            @RequestBody Map<String, Object> body) {
        
        Long userId = currentUser.id();
        String reason = body.containsKey("reason") ? String.valueOf(body.get("reason")) : "";
        
        // [P2] Accept optional evidence photo IDs from guest
        List<Long> evidencePhotoIds = null;
        if (body.containsKey("evidencePhotoIds") && body.get("evidencePhotoIds") instanceof List<?> rawList) {
            evidencePhotoIds = rawList.stream()
                    .map(item -> Long.valueOf(String.valueOf(item)))
                    .toList();
        }
        
        log.info("[CheckOut] Guest {} disputing damage claim for booking {}: {} (evidencePhotos: {})",
                userId, bookingId, reason, evidencePhotoIds != null ? evidencePhotoIds.size() : 0);
        
        checkOutService.disputeDamageClaim(bookingId, userId, reason, evidencePhotoIds);
        
        return ResponseEntity.ok(Map.of(
            "status", "DISPUTED",
            "message", "Prijava oštećenja osporena. Eskaliramo admin timu na pregled.",
            "bookingId", bookingId
        ));
    }

    /**
     * Get the damage claim details for a booking in dispute.
     */
    @GetMapping("/damage/status")
    public ResponseEntity<Map<String, Object>> getDamageClaimStatus(
            @PathVariable Long bookingId) {
        
        Long userId = currentUser.id();
        log.debug("[CheckOut] Damage claim status request for booking {} by user {}", bookingId, userId);
        
        CheckOutStatusDTO status = checkOutService.getCheckOutStatus(bookingId, userId);
        
        return ResponseEntity.ok(Map.of(
            "bookingId", bookingId,
            "bookingStatus", status.getStatus(),
            "damageReported", status.isNewDamageReported(),
            "damageDescription", status.getDamageDescription() != null ? status.getDamageDescription() : "",
            "damageClaimAmount", status.getDamageClaimAmount() != null ? status.getDamageClaimAmount() : 0,
            "damageClaimStatus", status.getDamageClaimStatus() != null ? status.getDamageClaimStatus() : "NONE"
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("[CheckOut] Illegal state: {}", ex.getMessage());
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "error", "INVALID_STATE",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[CheckOut] Illegal argument: {}", ex.getMessage());
        
        return ResponseEntity.badRequest().body(Map.of(
            "error", "INVALID_ARGUMENT",
            "message", ex.getMessage()
        ));
    }
}


